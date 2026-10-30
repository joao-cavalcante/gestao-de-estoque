package wms.backend.liberacaocorte

import kotlinx.serialization.json.Json
import wms.backend.tenancy.CredencialLiberadorRequest
import wms.backend.tenancy.ErpConnectionDto
import wms.backend.tenancy.ParametrosTenant
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Entrega 1, Fase 2 (M22) — credencial do liberador de serviço por tenant; variável de ambiente só no legado. */
class LiberadorServicoTest {
    private val ambiente = mapOf("LIBERADOR_USUARIO" to "liberador.env", "LIBERADOR_SENHA" to "senha-env-teste")
    private val semAmbiente = emptyMap<String, String>()

    @Test
    fun `credencial do tenant - usada mesmo com a variavel de ambiente definida`() {
        val r = LiberadorServico.resolver({ "lib.tenant" to "s1" }, fallbackPermitido = true, variavel = ambiente::get)
        assertEquals("lib.tenant", r.credencial?.usuario)
        assertEquals("tenant", r.credencial?.origem)
    }

    @Test
    fun `tenant sem credencial e sem reserva - variavel global NAO autoriza`() {
        val r = LiberadorServico.resolver({ null }, fallbackPermitido = false, variavel = ambiente::get)
        assertNull(r.credencial)
        assertEquals("liberador de serviço não configurado no tenant", r.motivo)
    }

    @Test
    fun `instalacao legada - sem credencial, reserva ligada, usa a variavel`() {
        val r = LiberadorServico.resolver({ null }, fallbackPermitido = true, variavel = ambiente::get)
        assertEquals("liberador.env", r.credencial?.usuario)
        assertEquals("variavel_ambiente_legado", r.credencial?.origem)
    }

    @Test
    fun `reserva ligada mas variavel ausente - indisponivel`() {
        assertNull(LiberadorServico.resolver({ null }, fallbackPermitido = true, variavel = semAmbiente::get).credencial)
        assertNull(LiberadorServico.resolver({ null }, true, mapOf("LIBERADOR_USUARIO" to "x", "LIBERADOR_SENHA" to " ")::get).credencial)
    }

    @Test
    fun `credencial gravada que nao decifra - bloqueia, sem cair na variavel`() {
        val r = LiberadorServico.resolver({ throw javax.crypto.AEADBadTagException("tag") }, fallbackPermitido = true, variavel = ambiente::get)
        assertNull(r.credencial)
        assertTrue(r.motivo!!.contains("não pôde ser lida"))
        assertFalse(r.motivo!!.contains("tag"))
    }

    @Test
    fun `dois tenants - cada um com a sua credencial`() {
        val a = UUID.randomUUID()
        val b = UUID.randomUUID()
        val gravadas = mapOf(a to ("lib.a" to "sa"), b to ("lib.b" to "sb"))
        fun de(t: UUID) = LiberadorServico.resolver({ gravadas[t] }, fallbackPermitido = false, variavel = ambiente::get).credencial
        assertEquals("lib.a", de(a)?.usuario)
        assertEquals("sb", de(b)?.senha)
        assertNull(de(UUID.randomUUID()))
    }

    @Test
    fun `senha nunca aparece em texto de log nem na leitura do tenant`() {
        val c = LiberadorServico.resolver({ "lib" to "senha-secreta-123" }, false, semAmbiente::get).credencial
        assertNotNull(c)
        assertFalse("senha-secreta-123" in c.toString())
        assertFalse("senha-secreta-123" in CredencialLiberadorRequest("lib", "senha-secreta-123").toString())
        val dto = Json.encodeToString(ErpConnectionDto.serializer(), ErpConnectionDto(erpType = "sankhya", baseUrl = "x", liberadorConfigurado = true))
        assertTrue("\"liberadorConfigurado\":true" in dto)
        assertFalse("senha" in dto.lowercase())
    }

    @Test
    fun `Negri - parametros depois da V62 e V63 ligam a reserva legada`() {
        val p = ParametrosTenant.ler(
            """{"liberacaoHerdada": {"eventos": [3], "compararValor": false}, "liberador": {"fallbackVariavelAmbiente": true}}""",
        )
        assertTrue(p.liberador.fallbackVariavelAmbiente)
        assertFalse(ParametrosTenant().liberador.fallbackVariavelAmbiente)
    }
}
