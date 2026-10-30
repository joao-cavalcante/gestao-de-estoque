package wms.backend.master

import io.ktor.client.request.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.routing.*
import io.ktor.server.testing.*
import wms.backend.auth.JwtService
import wms.backend.configconferencia.configConferenciaRoutes
import wms.backend.impressao.impressaoOcRoutes
import wms.backend.liberacaocorte.liberacaoCorteRoutes
import wms.backend.separacao.separacaoRoutes
import wms.backend.tarefas.tarefasRoutes
import wms.backend.usuarios.usuariosRoutes
import java.util.Base64
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Entrega 1.2 — auditoria de acesso cruzado. Nenhuma combinação de perfil, slug, id de tenant, payload, cabeçalho,
 * token ou URL leva um usuário de tenant à API da plataforma, nem o token Master às rotas dos tenants. Toda recusa
 * aqui acontece antes de qualquer consulta ao banco.
 */
class AutorizacaoCruzadaTest {
    private val tenantA = UUID.randomUUID()
    private val tenantB = UUID.randomUUID()
    private val adminA = JwtService.gerar(UUID.randomUUID(), tenantA, "ADMINISTRADOR")
    private val master = JwtService.gerarMaster(UUID.randomUUID(), "master@produto.com")

    private fun app(bloco: suspend ApplicationTestBuilder.() -> Unit) = testApplication {
        install(ContentNegotiation) { json() }
        routing {
            masterRoutes()
            usuariosRoutes()
            separacaoRoutes()
            tarefasRoutes()
            liberacaoCorteRoutes()
            configConferenciaRoutes()
            impressaoOcRoutes()
        }
        bloco()
    }

    private fun forjar(token: String, troca: (String) -> String): String {
        val (c, p, a) = token.split(".")
        val novo = Base64.getUrlEncoder().withoutPadding().encodeToString(troca(String(Base64.getUrlDecoder().decode(p))).toByteArray())
        return "$c.$novo.$a"
    }

    @Test
    fun `admin de tenant nao chega na plataforma por cabecalho, slug, id ou corpo`() = app {
        val rotas = listOf(
            "/api/master/tenants", "/api/master/tenants/$tenantA", "/api/master/tenants/$tenantB/usuarios",
            "/api/master/tenants/$tenantB/diagnostico", "/api/master/tenants/$tenantB/auditoria", "/api/master/auditoria",
            "/api/master/instalacao", "/api/master/auth/eu", "/api/master/campos-personalizados",
            "/api/master/tenants/negri", "/api/master/tenants/negri/usuarios",
        )
        for (rota in rotas) {
            val r = client.get(rota) {
                bearerAuth(adminA)
                header("X-Tenant", "negri"); header("X-Tenant-Id", tenantB.toString())
                header("X-Perfil", "MASTER"); header("X-Escopo", "plataforma"); header("X-Real-IP", "127.0.0.1")
            }
            assertEquals(HttpStatusCode.Unauthorized, r.status, rota)
        }
        val escrita = client.put("/api/master/tenants/$tenantB/status") {
            bearerAuth(adminA); contentType(ContentType.Application.Json)
            setBody("""{"status":"suspended","confirmacao":"negri","tenantId":"$tenantA","perfil":"MASTER","escopo":"plataforma"}""")
        }
        assertEquals(HttpStatusCode.Unauthorized, escrita.status)
    }

    @Test
    fun `token de tenant com perfil ou escopo forjado nao passa em lugar nenhum`() = app {
        val forjados = listOf(
            forjar(adminA) { it.replace("\"ADMINISTRADOR\"", "\"MASTER\"") },
            forjar(adminA) { it.replaceFirst("{", "{\"escopo\":\"plataforma\",\"iss\":\"wms-plataforma\",\"aud\":\"wms-master\",") },
            forjar(adminA) { it.replace(tenantA.toString(), tenantB.toString()) },
        )
        for (t in forjados) {
            assertEquals(HttpStatusCode.Unauthorized, client.get("/api/master/tenants") { bearerAuth(t) }.status)
            assertEquals(HttpStatusCode.Unauthorized, client.get("/api/usuarios") { bearerAuth(t) }.status)
        }
    }

    @Test
    fun `token Master nao opera tenant nenhum`() = app {
        val operacionais = listOf(
            "/api/usuarios", "/api/tarefas?tenant=negri", "/api/separacao/sessoes/${UUID.randomUUID()}?tenant=negri",
            "/api/liberacao-corte/pendentes", "/api/config-conferencia", "/api/impressao/agente/proximo",
        )
        for (rota in operacionais) {
            assertEquals(HttpStatusCode.Unauthorized, client.get(rota) { bearerAuth(master) }.status, rota)
        }
        val criarUsuario = client.post("/api/usuarios") {
            bearerAuth(master); contentType(ContentType.Application.Json)
            setBody("""{"nome":"x","email":"x@x.com","senha":"123456789012","perfil":"ADMINISTRADOR"}""")
        }
        assertEquals(HttpStatusCode.Unauthorized, criarUsuario.status)
    }

    @Test
    fun `nao existe rota de cadastro Master nem rota antiga de tenants`() = app {
        for ((metodo, rota) in listOf(
            HttpMethod.Post to "/api/master/usuarios", HttpMethod.Post to "/api/master/auth/registrar",
            HttpMethod.Post to "/api/master/masters", HttpMethod.Post to "/api/master/auth/cadastro",
        )) {
            val r = client.request(rota) { method = metodo; bearerAuth(master) }
            assertEquals(HttpStatusCode.NotFound, r.status, rota)
        }
        for (rota in listOf("/api/tenants", "/api/tenants/negri", "/api/admin/tenants")) {
            assertEquals(HttpStatusCode.NotFound, client.get(rota) { bearerAuth(adminA) }.status, rota)
        }
    }

    @Test
    fun `id de tenant malformado na URL nao vira slug nem lista`() = app {
        // Com token Master inválido a recusa é 401 antes de resolver o tenant; o resolvedor só aceita UUID (tenantDaRota).
        val falso = forjar(master) { it.replace("plataforma", "plataforma2") }
        assertEquals(HttpStatusCode.Unauthorized, client.get("/api/master/tenants/negri") { bearerAuth(falso) }.status)
        assertEquals(HttpStatusCode.Unauthorized, client.get("/api/master/tenants/%2e%2e") { bearerAuth(adminA) }.status)
    }
}
