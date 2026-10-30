package wms.backend.auth

import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.server.testing.*
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Entrega 1.2 — tenant suspenso perde o acesso também com token emitido ANTES da suspensão (sem esperar as 8h),
 * sem derrubar outro tenant, e volta com a reativação. Situação do tenant vem de um "banco" em memória.
 */
class SuspensaoTenantTest {
    private val tenantA = UUID.randomUUID()
    private val tenantB = UUID.randomUUID()
    private val status = mutableMapOf(tenantA to "active", tenantB to "active")
    private val consultas = AtomicInteger()
    private var relogio = 1_000_000L

    @BeforeTest
    fun preparar() {
        EstadoTenant.limpar()
        EstadoTenant.consultar = { id -> consultas.incrementAndGet(); status[id] }
        EstadoTenant.relogio = { relogio }
    }

    @AfterTest
    fun restaurar() {
        EstadoTenant.limpar()
        EstadoTenant.consultar = wms.backend.tenancy.TenantRepository::statusDe
        EstadoTenant.relogio = System::currentTimeMillis
    }

    private fun app(bloco: suspend ApplicationTestBuilder.() -> Unit) = testApplication {
        install(ContentNegotiation) { json() }
        routing {
            get("/api/operacao") { call.exigirAuth() ?: return@get; call.respond(mapOf("ok" to "sim")) }
            get("/api/admin") { call.exigirAdmin() ?: return@get; call.respond(mapOf("ok" to "sim")) }
        }
        bloco()
    }

    private suspend fun ApplicationTestBuilder.operar(token: String) = client.get("/api/operacao") { bearerAuth(token) }

    /** O que o Painel Master faz ao trocar a situação (MasterRoutes /status). */
    private fun alterarSituacao(id: UUID, novo: String) {
        status[id] = novo
        EstadoTenant.invalidar(id)
    }

    @Test
    fun `tenant ativo acessa normalmente`() = app {
        assertEquals(HttpStatusCode.OK, operar(JwtService.gerar(UUID.randomUUID(), tenantA, "OPERADOR")).status)
        status[tenantA] = "trial"; EstadoTenant.invalidar(tenantA)
        assertEquals(HttpStatusCode.OK, operar(JwtService.gerar(UUID.randomUUID(), tenantA, "OPERADOR")).status)
    }

    @Test
    fun `token emitido antes da suspensao perde o acesso na hora`() = app {
        val tokenAntigo = JwtService.gerar(UUID.randomUUID(), tenantA, "ADMINISTRADOR")
        assertEquals(HttpStatusCode.OK, operar(tokenAntigo).status)
        alterarSituacao(tenantA, "suspended")
        val r = operar(tokenAntigo)
        assertEquals(HttpStatusCode.Unauthorized, r.status, "401 faz o front encerrar a sessão")
        assertTrue("TENANT_DESATIVADO" in r.bodyAsText())
        assertEquals(HttpStatusCode.Unauthorized, client.get("/api/admin") { bearerAuth(tokenAntigo) }.status)
    }

    @Test
    fun `cancelado e tenant inexistente tambem nao acessam`() = app {
        alterarSituacao(tenantA, "cancelled")
        assertEquals(HttpStatusCode.Unauthorized, operar(JwtService.gerar(UUID.randomUUID(), tenantA, "OPERADOR")).status)
        assertEquals(HttpStatusCode.Unauthorized, operar(JwtService.gerar(UUID.randomUUID(), UUID.randomUUID(), "OPERADOR")).status)
    }

    @Test
    fun `suspender um tenant nao interrompe outro`() = app {
        val tokenB = JwtService.gerar(UUID.randomUUID(), tenantB, "OPERADOR")
        alterarSituacao(tenantA, "suspended")
        assertEquals(HttpStatusCode.OK, operar(tokenB).status)
        assertEquals(HttpStatusCode.Unauthorized, operar(JwtService.gerar(UUID.randomUUID(), tenantA, "OPERADOR")).status)
    }

    @Test
    fun `reativacao devolve o acesso ao mesmo token`() = app {
        val token = JwtService.gerar(UUID.randomUUID(), tenantA, "OPERADOR")
        alterarSituacao(tenantA, "suspended")
        assertEquals(HttpStatusCode.Unauthorized, operar(token).status)
        alterarSituacao(tenantA, "active")
        assertEquals(HttpStatusCode.OK, operar(token).status)
    }

    @Test
    fun `outra replica (sem invalidacao) enxerga a suspensao em no maximo o TTL`() = app {
        val token = JwtService.gerar(UUID.randomUUID(), tenantA, "OPERADOR")
        assertEquals(HttpStatusCode.OK, operar(token).status)
        status[tenantA] = "suspended" // gravado por outra réplica: este processo não recebeu invalidar()
        relogio += EstadoTenant.TTL_MS - 1
        assertEquals(HttpStatusCode.OK, operar(token).status)
        relogio += 2
        assertEquals(HttpStatusCode.Unauthorized, operar(token).status)
    }

    @Test
    fun `situacao em cache - uma consulta por tenant por TTL, nao uma por requisicao`() = app {
        val token = JwtService.gerar(UUID.randomUUID(), tenantA, "OPERADOR")
        repeat(50) { operar(token) }
        assertEquals(1, consultas.get())
    }

    @Test
    fun `token Master nao autentica rota operacional nem administrativa do tenant`() = app {
        val master = JwtService.gerarMaster(UUID.randomUUID(), "master@produto.com")
        assertEquals(HttpStatusCode.Unauthorized, operar(master).status)
        assertEquals(HttpStatusCode.Unauthorized, client.get("/api/admin") { bearerAuth(master) }.status)
        assertEquals(0, consultas.get(), "recusado antes de consultar a situação do tenant")
    }
}
