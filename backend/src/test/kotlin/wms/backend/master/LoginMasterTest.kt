package wms.backend.master

import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.routing.*
import io.ktor.server.testing.*
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.mindrot.jbcrypt.BCrypt
import wms.backend.auth.JwtService
import java.time.Instant
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Entrega 1.2 — login Master: limite de tentativas no "banco" (fontes em memória), sem revelar contas, com TOTP. */
class LoginMasterTest {

    private class Tentativa(val em: Instant, val email: String, val ip: String, val sucesso: Boolean, val bloqueada: Boolean)

    /** Mesma semântica de MasterRepository.contarFalhas, em memória. */
    private class FontesMemoria(var agora: Instant) : FontesLoginMaster {
        val contas = mutableMapOf<String, MasterRepository.MasterLogin>()
        val tentativas = mutableListOf<Tentativa>()
        val auditoria = mutableListOf<Pair<JsonObject, String>>()
        val passos = mutableMapOf<UUID, Long>()

        override fun buscar(email: String) = contas[email]
        override fun contarFalhas(email: String, ip: String, desde: Instant): ContagemFalhas {
            val ultimoSucesso = tentativas.indexOfLast { it.email == email && it.sucesso }
            val daConta = tentativas.drop(ultimoSucesso + 1).filter { it.email == email && !it.sucesso && !it.bloqueada && it.em > desde }
            val doIp = tentativas.count { it.ip == ip && !it.sucesso && !it.bloqueada && it.em > desde }
            return ContagemFalhas(daConta.count { it.ip == ip }, daConta.size, doIp)
        }
        override fun registrarTentativa(email: String, ip: String, sucesso: Boolean, bloqueada: Boolean) {
            tentativas += Tentativa(agora, email, ip, sucesso, bloqueada)
        }
        override fun consumirPassoMfa(masterId: UUID, passo: Long): Boolean {
            val ultimo = passos[masterId]
            if (ultimo != null && passo <= ultimo) return false
            passos[masterId] = passo
            return true
        }
        override fun auditar(ator: AtorAdmin, detalhe: JsonObject, resultado: String) {
            auditoria += detalhe to resultado
        }
    }

    private val agora = Instant.parse("2026-10-30T15:00:00Z")
    private val senha = "senha-forte-do-master-123"
    private val segredo = MfaTotp.novoSegredo()
    private val fontes = FontesMemoria(agora).apply {
        contas["master@produto.com"] = MasterRepository.MasterLogin(UUID.randomUUID(), "Master", "master@produto.com", BCrypt.hashpw(senha, BCrypt.gensalt(4)), true, segredo)
    }
    private val login = LoginMaster(fontes, mfaObrigatorio = true) { fontes.agora }

    private fun codigoAgora(deslocamentoPassos: Long = 0) = MfaTotp.codigo(segredo, MfaTotp.passo(fontes.agora) + deslocamentoPassos)

    // ─── Serviço ──────────────────────────────────────────────────────────

    @Test
    fun `senha e codigo corretos autenticam`() {
        assertIs<ResultadoLoginMaster.Autenticado>(login.autenticar("Master@Produto.com ", senha, codigoAgora(), "10.0.0.1"))
        assertEquals("ok", fontes.auditoria.last().second)
    }

    @Test
    fun `senha certa sem codigo ou com codigo errado e recusada igual a senha errada`() {
        assertEquals(ResultadoLoginMaster.Recusado, login.autenticar("master@produto.com", senha, null, "10.0.0.1"))
        assertEquals(ResultadoLoginMaster.Recusado, login.autenticar("master@produto.com", senha, "000000".takeIf { it != codigoAgora() } ?: "111111", "10.0.0.1"))
        assertEquals(ResultadoLoginMaster.Recusado, login.autenticar("master@produto.com", "errada", codigoAgora(), "10.0.0.1"))
        assertEquals(listOf("mfa_invalido", "mfa_invalido", "credenciais"), fontes.auditoria.map { it.first["motivo"]!!.jsonPrimitive.content })
    }

    @Test
    fun `codigo TOTP nao vale duas vezes`() {
        val codigo = codigoAgora()
        assertIs<ResultadoLoginMaster.Autenticado>(login.autenticar("master@produto.com", senha, codigo, "10.0.0.1"))
        assertEquals(ResultadoLoginMaster.Recusado, login.autenticar("master@produto.com", senha, codigo, "10.0.0.1"))
        assertEquals("mfa_reutilizado", fontes.auditoria.last().first["motivo"]!!.jsonPrimitive.content)
    }

    @Test
    fun `codigo de um passo vizinho vale, de dois passos nao`() {
        assertIs<ResultadoLoginMaster.Autenticado>(login.autenticar("master@produto.com", senha, codigoAgora(-1), "10.0.0.1"))
        fontes.agora = fontes.agora.plusSeconds(120)
        assertEquals(ResultadoLoginMaster.Recusado, login.autenticar("master@produto.com", senha, codigoAgora(-2), "10.0.0.1"))
    }

    @Test
    fun `conta sem MFA cadastrado nao entra quando MFA e obrigatorio`() {
        fontes.contas["semmfa@produto.com"] = MasterRepository.MasterLogin(UUID.randomUUID(), "Sem", "semmfa@produto.com", BCrypt.hashpw(senha, BCrypt.gensalt(4)), true, null)
        assertEquals(ResultadoLoginMaster.Recusado, login.autenticar("semmfa@produto.com", senha, null, "10.0.0.1"))
        val semObrigacao = LoginMaster(fontes, mfaObrigatorio = false) { fontes.agora }
        assertIs<ResultadoLoginMaster.Autenticado>(semObrigacao.autenticar("semmfa@produto.com", senha, null, "10.0.0.1"))
    }

    @Test
    fun `conta inativa e recusada mesmo com tudo certo`() {
        val c = fontes.contas.getValue("master@produto.com")
        fontes.contas["master@produto.com"] = c.copy(ativo = false)
        assertEquals(ResultadoLoginMaster.Recusado, login.autenticar("master@produto.com", senha, codigoAgora(), "10.0.0.1"))
    }

    @Test
    fun `cinco erros do mesmo IP bloqueiam a conta naquele IP - temporariamente`() {
        repeat(PoliticaLimiteLogin.MAX_FALHAS_PAR) { login.autenticar("master@produto.com", "errada", null, "10.0.0.1") }
        assertEquals(ResultadoLoginMaster.LimiteExcedido, login.autenticar("master@produto.com", senha, codigoAgora(), "10.0.0.1"))
        // outro IP ainda entra (o limite por e-mail é maior)
        assertIs<ResultadoLoginMaster.Autenticado>(login.autenticar("master@produto.com", senha, codigoAgora(), "10.0.0.2"))
        // passada a janela, o primeiro IP volta a poder tentar
        fontes.agora = fontes.agora.plus(PoliticaLimiteLogin.JANELA).plusSeconds(1)
        assertIs<ResultadoLoginMaster.Autenticado>(login.autenticar("master@produto.com", senha, codigoAgora(), "10.0.0.1"))
    }

    @Test
    fun `tentativas durante o bloqueio nao prolongam o bloqueio`() {
        repeat(PoliticaLimiteLogin.MAX_FALHAS_PAR) { login.autenticar("master@produto.com", "errada", null, "10.0.0.1") }
        fontes.agora = fontes.agora.plusSeconds(600)
        repeat(50) { login.autenticar("master@produto.com", "errada", null, "10.0.0.1") }
        fontes.agora = fontes.agora.plusSeconds(301) // 15 min + 1 s depois do 5º erro
        assertIs<ResultadoLoginMaster.Autenticado>(login.autenticar("master@produto.com", senha, codigoAgora(), "10.0.0.1"))
    }

    @Test
    fun `forca bruta distribuida contra uma conta e limitada pelo e-mail`() {
        repeat(PoliticaLimiteLogin.MAX_FALHAS_EMAIL) { login.autenticar("master@produto.com", "errada", null, "10.1.0.$it") }
        assertEquals(ResultadoLoginMaster.LimiteExcedido, login.autenticar("master@produto.com", senha, codigoAgora(), "10.9.9.9"))
    }

    @Test
    fun `varredura de contas a partir de um IP e limitada`() {
        repeat(PoliticaLimiteLogin.MAX_FALHAS_IP) { login.autenticar("conta$it@produto.com", "x", null, "10.0.0.66") }
        assertEquals(ResultadoLoginMaster.LimiteExcedido, login.autenticar("master@produto.com", senha, codigoAgora(), "10.0.0.66"))
    }

    @Test
    fun `conta inexistente e conta existente tem o mesmo comportamento no limite`() {
        repeat(PoliticaLimiteLogin.MAX_FALHAS_PAR) {
            assertEquals(ResultadoLoginMaster.Recusado, login.autenticar("naoexiste@produto.com", "x", null, "10.0.0.5"))
            assertEquals(ResultadoLoginMaster.Recusado, login.autenticar("master@produto.com", "x", null, "10.0.0.5"))
        }
        assertEquals(ResultadoLoginMaster.LimiteExcedido, login.autenticar("naoexiste@produto.com", "x", null, "10.0.0.5"))
        assertEquals(ResultadoLoginMaster.LimiteExcedido, login.autenticar("master@produto.com", "x", null, "10.0.0.5"))
    }

    @Test
    fun `tentativas negadas sao auditadas sem senha nem codigo`() {
        val codigo = codigoAgora()
        login.autenticar("master@produto.com", "senha-errada-XYZ", codigo, "10.0.0.1")
        repeat(10) { login.autenticar("master@produto.com", "senha-errada-XYZ", codigo, "10.0.0.1") }
        val trilha = fontes.auditoria.joinToString { it.toString() }
        assertTrue(fontes.auditoria.all { it.second == "negado" })
        assertTrue("limite_tentativas" in trilha)
        assertFalse("senha-errada-XYZ" in trilha)
        assertFalse(codigo in trilha)
    }

    // ─── Rota HTTP ────────────────────────────────────────────────────────

    private fun rotas(bloco: suspend ApplicationTestBuilder.() -> Unit) = testApplication {
        install(ContentNegotiation) { json() }
        routing { masterRoutes(login) }
        bloco()
    }

    private suspend fun ApplicationTestBuilder.entrar(email: String, senha: String, codigo: String?) =
        client.post("/api/master/auth/login") {
            contentType(ContentType.Application.Json)
            setBody(Json.encodeToString(LoginMasterRequest.serializer(), LoginMasterRequest(email, senha, codigo)))
        }

    @Test
    fun `rota - mesma resposta para conta inexistente, senha errada e codigo errado`() = rotas {
        val respostas = listOf(
            entrar("naoexiste@produto.com", senha, codigoAgora()),
            entrar("master@produto.com", "errada", codigoAgora()),
            entrar("master@produto.com", senha, null),
        )
        respostas.forEach { assertEquals(HttpStatusCode.Unauthorized, it.status) }
        assertEquals(1, respostas.map { it.bodyAsText() }.distinct().size, "corpo idêntico — não revela quem existe")
    }

    @Test
    fun `rota - login certo devolve token Master valido e o limite responde 429`() = rotas {
        val ok = entrar("master@produto.com", senha, codigoAgora())
        assertEquals(HttpStatusCode.OK, ok.status)
        val token = Json.parseToJsonElement(ok.bodyAsText()).jsonObject["token"]!!.jsonPrimitive.content
        assertNotNull(JwtService.validarMaster(token))
        assertNull(JwtService.validar(token))

        repeat(PoliticaLimiteLogin.MAX_FALHAS_PAR) { entrar("master@produto.com", "errada", null) }
        assertEquals(HttpStatusCode.TooManyRequests, entrar("master@produto.com", senha, codigoAgora(1)).status)
    }

    @Test
    fun `rota - corpo invalido e 400 sem tocar no limite`() = rotas {
        val r = client.post("/api/master/auth/login") { contentType(ContentType.Application.Json); setBody("""{"email":""}""") }
        assertEquals(HttpStatusCode.BadRequest, r.status)
        assertTrue(fontes.tentativas.isEmpty())
    }

    @Test
    fun `pedido de login nao expoe senha nem codigo em log`() {
        val texto = LoginMasterRequest("m@x.com", "pw-123", "654321").toString()
        assertFalse("pw-123" in texto)
        assertFalse("654321" in texto)
    }
}
