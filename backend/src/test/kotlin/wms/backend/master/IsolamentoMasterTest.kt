package wms.backend.master

import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.routing.*
import io.ktor.server.testing.*
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import wms.backend.auth.JwtService
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Entrega 1.1 — isolamento entre administração da plataforma e administração de tenant. Sem banco: toda
 * recusa testada aqui acontece ANTES de qualquer consulta (token errado nem chega no repositório).
 */
class IsolamentoMasterTest {
    private val tenantA = UUID.randomUUID()
    private val tokenAdminTenant = JwtService.gerar(UUID.randomUUID(), tenantA, "ADMINISTRADOR")
    private val tokenOperador = JwtService.gerar(UUID.randomUUID(), tenantA, "OPERADOR")
    private val tokenMaster = JwtService.gerarMaster(UUID.randomUUID(), "master@produto.com")

    // ─── Tokens ───────────────────────────────────────────────────────────

    @Test
    fun `token de ADMINISTRADOR de tenant nao vale como Master`() {
        assertNull(JwtService.validarMaster(tokenAdminTenant))
        assertNull(JwtService.validarMaster(tokenOperador))
    }

    @Test
    fun `token Master nao vale como usuario de tenant (nem acessa rota operacional)`() {
        assertNull(JwtService.validar(tokenMaster))
        assertNotNull(JwtService.validarMaster(tokenMaster))
    }

    @Test
    fun `token de tenant continua valido pro proprio tenant`() {
        val c = JwtService.validar(tokenAdminTenant)
        assertEquals(tenantA, c?.tenantId)
        assertEquals("ADMINISTRADOR", c?.perfil)
    }

    @Test
    fun `token adulterado e recusado nos dois sentidos`() {
        val partes = tokenMaster.split(".")
        val adulterado = partes[0] + "." + partes[1] + "x." + partes[2]
        assertNull(JwtService.validarMaster(adulterado))
        assertNull(JwtService.validar(tokenAdminTenant.dropLast(2) + "aa"))
    }

    @Test
    fun `perfil de tenant chamado MASTER nao vira Master`() {
        val promovido = JwtService.gerar(UUID.randomUUID(), tenantA, "MASTER_PLATAFORMA")
        assertNull(JwtService.validarMaster(promovido))
    }

    // ─── Rotas HTTP ───────────────────────────────────────────────────────

    private fun testarRotas(bloco: suspend ApplicationTestBuilder.() -> Unit) = testApplication {
        install(ContentNegotiation) { json() }
        routing { masterRoutes() }
        bloco()
    }

    @Test
    fun `admin de tenant nao lista nem altera tenants pela API da plataforma`() = testarRotas {
        val outro = UUID.randomUUID()
        val chamadas = listOf(
            client.get("/api/master/tenants") { bearerAuth(tokenAdminTenant) },
            client.get("/api/master/tenants/$outro") { bearerAuth(tokenAdminTenant) },
            client.put("/api/master/tenants/$outro/modulos") {
                bearerAuth(tokenAdminTenant); contentType(ContentType.Application.Json)
                setBody("""{"modulos":["liberacao_herdada"],"confirmacao":"negri"}""")
            },
            client.put("/api/master/tenants/$outro/parametros") {
                bearerAuth(tokenAdminTenant); contentType(ContentType.Application.Json); setBody("""{"parametros":{}}""")
            },
            client.put("/api/master/tenants/$outro/liberador") {
                bearerAuth(tokenAdminTenant); contentType(ContentType.Application.Json); setBody("""{"usuario":"x","senha":"y"}""")
            },
            client.put("/api/master/tenants/$outro/integracao") {
                bearerAuth(tokenAdminTenant); contentType(ContentType.Application.Json); setBody("""{"baseUrl":"https://atacante"}""")
            },
            client.put("/api/master/tenants/$outro/status") {
                bearerAuth(tokenAdminTenant); contentType(ContentType.Application.Json); setBody("""{"status":"suspended"}""")
            },
            client.get("/api/master/tenants/$outro/diagnostico") { bearerAuth(tokenOperador) },
            client.get("/api/master/auditoria") { bearerAuth(tokenAdminTenant) },
            client.post("/api/master/tenants") {
                bearerAuth(tokenAdminTenant); contentType(ContentType.Application.Json); setBody("""{"slug":"novo","nome":"Novo"}""")
            },
        )
        chamadas.forEach { assertEquals(HttpStatusCode.Unauthorized, it.status, it.request.url.toString()) }
    }

    @Test
    fun `sem token - nada da plataforma responde`() = testarRotas {
        assertEquals(HttpStatusCode.Unauthorized, client.get("/api/master/tenants").status)
        assertEquals(HttpStatusCode.Unauthorized, client.get("/api/master/instalacao").status)
    }

    @Test
    fun `rotas antigas de tenants (slug na URL, qualquer ADMINISTRADOR) nao existem mais`() = testarRotas {
        assertEquals(HttpStatusCode.NotFound, client.get("/api/tenants") { bearerAuth(tokenAdminTenant) }.status)
        assertEquals(HttpStatusCode.NotFound, client.delete("/api/tenants/negri") { bearerAuth(tokenAdminTenant) }.status)
        assertEquals(HttpStatusCode.NotFound, client.post("/api/tenants/negri/erp-connections") { bearerAuth(tokenAdminTenant) }.status)
    }

    // ─── Confirmação e auditoria ──────────────────────────────────────────

    @Test
    fun `confirmacao critica - so o slug exato do tenant alterado`() {
        assertTrue(ConfirmacaoCritica.valida("negri", "negri"))
        assertTrue(ConfirmacaoCritica.valida("negri", " negri "))
        assertFalse(ConfirmacaoCritica.valida("negri", "outro"))
        assertFalse(ConfirmacaoCritica.valida("negri", "NEGRI"))
        assertFalse(ConfirmacaoCritica.valida("negri", null))
        assertFalse(ConfirmacaoCritica.valida("negri", ""))
    }

    @Test
    fun `auditoria nunca guarda segredo, em nenhum nivel`() {
        val detalhe = buildJsonObject {
            put("usuario", "liberador"); put("senha", "s3gr3d0"); put("credenciais", "{...}")
            putJsonObject("depois") { put("clientSecret", "abc"); put("xToken", "def"); put("baseUrl", "https://x") }
        }
        val limpo = AuditoriaAdmin.sanitizar(detalhe).toString()
        assertFalse("s3gr3d0" in limpo)
        assertFalse("abc" in limpo)
        assertFalse("def" in limpo)
        assertTrue("liberador" in limpo)
        assertTrue("https://x" in limpo)
    }

    @Test
    fun `pedidos com senha nao expoem a senha em log`() {
        assertFalse("pw-123" in LoginMasterRequest("m@x.com", "pw-123").toString())
        assertFalse("pw-123" in LiberadorRequest("lib", "pw-123").toString())
        assertFalse("pw-123" in IntegracaoRequest("https://x", clientSecret = "pw-123").toString())
    }

    @Test
    fun `niveis - ADMINISTRADOR e ADMIN_TENANT, demais operacionais, nenhum vira Master`() {
        assertEquals("ADMIN_TENANT", nivelDoPerfil("ADMINISTRADOR"))
        assertEquals("TV", nivelDoPerfil("TV"))
        assertEquals("USUARIO_OPERACIONAL", nivelDoPerfil("OPERADOR"))
        assertEquals("USUARIO_OPERACIONAL", nivelDoPerfil("MASTER_PLATAFORMA"))
    }
}
