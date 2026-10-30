package wms.backend.master

import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import wms.backend.auth.ConfigMasterJwt
import wms.backend.auth.JwtService
import java.time.Instant
import java.util.Base64
import java.util.Date
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Entrega 1.2 — chave própria do token Master. O segredo de teste vem do build.gradle.kts (tasks.test); o dos
 * clientes, sem WMS_JWT_SECRET no teste, é o valor de desenvolvimento.
 */
class MasterJwtTest {
    private val segredoMaster = System.getenv("WMS_MASTER_JWT_SECRET")!!
    private val segredoTenantsDev = "CHANGE_ME_EM_PRODUCAO_JWT_SECRET"
    private val masterId = UUID.randomUUID()

    private fun tokenMaster(
        algoritmo: Algorithm = Algorithm.HMAC256(segredoMaster),
        emissor: String? = JwtService.EMISSOR_MASTER,
        audiencia: String? = JwtService.AUDIENCIA_MASTER,
        escopo: String? = "plataforma",
        expira: Instant = Instant.now().plusSeconds(600),
    ): String = JWT.create()
        .apply { emissor?.let { withIssuer(it) }; audiencia?.let { withAudience(it) }; escopo?.let { withClaim("escopo", it) } }
        .withSubject(masterId.toString())
        .withClaim("email", "master@produto.com")
        .withExpiresAt(Date.from(expira))
        .sign(algoritmo)

    /** Troca o payload do JWT mantendo cabeçalho e assinatura originais. */
    private fun adulterarPayload(token: String, troca: (String) -> String): String {
        val (cab, payload, assinatura) = token.split(".")
        val json = String(Base64.getUrlDecoder().decode(payload))
        val novo = Base64.getUrlEncoder().withoutPadding().encodeToString(troca(json).toByteArray())
        return "$cab.$novo.$assinatura"
    }

    @Test
    fun `token Master gerado pelo servico e aceito so pela validacao Master`() {
        val token = JwtService.gerarMaster(masterId, "master@produto.com")
        assertEquals(masterId, JwtService.validarMaster(token)?.masterId)
        assertNull(JwtService.validar(token), "token Master não autentica rota operacional")
    }

    @Test
    fun `token de cliente nao autentica rota Master`() {
        val tokenCliente = JwtService.gerar(UUID.randomUUID(), UUID.randomUUID(), "ADMINISTRADOR")
        assertNull(JwtService.validarMaster(tokenCliente))
    }

    @Test
    fun `token Master assinado com o segredo dos clientes e recusado`() {
        // Mesmas claims de um token Master legítimo, mas com a chave dos tenants (o que a Entrega 1.1 fazia).
        assertNull(JwtService.validarMaster(tokenMaster(Algorithm.HMAC256(segredoTenantsDev))))
    }

    @Test
    fun `token assinado com segredo incorreto e recusado`() {
        assertNull(JwtService.validarMaster(tokenMaster(Algorithm.HMAC256("outro-segredo-qualquer-com-mais-de-32-caracteres"))))
    }

    @Test
    fun `token Master expirado e recusado`() {
        assertNull(JwtService.validarMaster(tokenMaster(expira = Instant.now().minusSeconds(120))))
    }

    @Test
    fun `emissor, audiencia e escopo sao obrigatorios`() {
        assertNotNull(JwtService.validarMaster(tokenMaster()))
        assertNull(JwtService.validarMaster(tokenMaster(emissor = null)))
        assertNull(JwtService.validarMaster(tokenMaster(emissor = "outro")))
        assertNull(JwtService.validarMaster(tokenMaster(audiencia = null)))
        assertNull(JwtService.validarMaster(tokenMaster(audiencia = "wms-tenant")))
        assertNull(JwtService.validarMaster(tokenMaster(escopo = null)))
        assertNull(JwtService.validarMaster(tokenMaster(escopo = "tenant")))
    }

    @Test
    fun `perfil ou escopo alterado a mao no payload nao concede acesso`() {
        val cliente = JwtService.gerar(UUID.randomUUID(), UUID.randomUUID(), "OPERADOR")
        val virouAdmin = adulterarPayload(cliente) { it.replace("\"OPERADOR\"", "\"ADMINISTRADOR\"") }
        assertTrue("ADMINISTRADOR" in String(Base64.getUrlDecoder().decode(virouAdmin.split(".")[1])))
        assertNull(JwtService.validar(virouAdmin))

        val comEscopo = adulterarPayload(cliente) { it.replaceFirst("{", "{\"escopo\":\"plataforma\",\"iss\":\"wms-plataforma\",\"aud\":\"wms-master\",") }
        assertNull(JwtService.validarMaster(comEscopo))
        assertNull(JwtService.validar(comEscopo))

        val master = JwtService.gerarMaster(masterId, "master@produto.com")
        val masterComTenant = adulterarPayload(master) { it.replaceFirst("{", "{\"tenant_id\":\"${UUID.randomUUID()}\",\"perfil\":\"ADMINISTRADOR\",") }
        assertNull(JwtService.validar(masterComTenant))
        assertNull(JwtService.validarMaster(masterComTenant))
    }

    @Test
    fun `algoritmo none nao e aceito`() {
        val semAssinatura = tokenMaster(Algorithm.none())
        assertNull(JwtService.validarMaster(semAssinatura))
    }

    // ─── Configuração da chave ────────────────────────────────────────────

    @Test
    fun `sem segredo Master a plataforma fica indisponivel - sem reserva no segredo dos clientes`() {
        assertIs<ConfigMasterJwt.Indisponivel>(ConfigMasterJwt.carregar(null, "segredo-dos-clientes"))
        assertIs<ConfigMasterJwt.Indisponivel>(ConfigMasterJwt.carregar("   ", "segredo-dos-clientes"))
    }

    @Test
    fun `segredo Master curto ou igual ao dos clientes e recusado`() {
        assertIs<ConfigMasterJwt.Indisponivel>(ConfigMasterJwt.carregar("curto-demais", "x"))
        val igual = "um-segredo-compartilhado-com-mais-de-32-caracteres"
        assertIs<ConfigMasterJwt.Indisponivel>(ConfigMasterJwt.carregar(igual, igual))
        assertIs<ConfigMasterJwt.Pronta>(ConfigMasterJwt.carregar(segredoMaster, segredoTenantsDev))
    }

    @Test
    fun `motivo de indisponibilidade nunca inclui o valor do segredo`() {
        val motivo = (ConfigMasterJwt.carregar("abc-segredo-curto", "x") as ConfigMasterJwt.Indisponivel).motivo
        assertTrue("abc-segredo-curto" !in motivo)
        assertNull(JwtService.motivoMasterIndisponivel(), "nos testes a chave Master está configurada")
    }
}
