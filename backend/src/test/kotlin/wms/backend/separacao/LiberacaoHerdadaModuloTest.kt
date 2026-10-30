package wms.backend.separacao

import kotlinx.coroutines.runBlocking
import wms.backend.tenancy.Modulos
import wms.backend.tenancy.ParametrosLiberacaoHerdada
import wms.backend.tenancy.ParametrosTenant
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Entrega 1, Fase 1 (M21) — liberação herdada só com o módulo `liberacao_herdada` e a configuração do tenant. */
class LiberacaoHerdadaModuloTest {

    private val negri = UUID.randomUUID()
    private val outro = UUID.randomUUID()
    private val perfilNegri = ParametrosLiberacaoHerdada(eventos = listOf(3), compararValor = false)

    /** Configuração por tenant (mapa) — o que cada tenant enxerga é só o que está na entrada dele. */
    private class PortaPorTenant(
        val modulosPorTenant: Map<UUID, Set<String>>,
        val parametrosPorTenant: Map<UUID, ParametrosLiberacaoHerdada>,
        val linhas: List<Map<String, String?>>,
        val configuracaoQuebrada: Boolean = false,
    ) : LiberacaoHerdada.Porta {
        val sqls = mutableListOf<String>()
        var credencialLida = false
        val liberadas = mutableListOf<Map<String, String?>>()

        override fun modulos(tenantId: UUID): Set<String> {
            if (configuracaoQuebrada) throw IllegalStateException("banco fora")
            return modulosPorTenant[tenantId].orEmpty()
        }
        override fun parametros(tenantId: UUID) = parametrosPorTenant[tenantId] ?: ParametrosLiberacaoHerdada()
        override suspend fun pendentes(tenantSlug: String, sql: String): List<Map<String, String?>> { sqls += sql; return linhas }
        override fun credenciais(tenantId: UUID): Pair<String, String>? { credencialLida = true; return "liberador" to "segredo" }
        override suspend fun validar(tenantSlug: String, usuario: String, senha: String) = "27"
        override suspend fun liberar(tenantSlug: String, linha: Map<String, String?>, codusu: String, obs: String) { liberadas += linha }
    }

    private fun pendente(seq: String, atual: String, origem: String) = mapOf(
        "NUCHAVE" to "70000", "TABELA" to "TGFITE", "EVENTO" to "3", "SEQUENCIA" to seq, "SEQCASCATA" to "0", "NUCLL" to "0",
        "VLRATUAL" to atual, "NUNOTAORIG" to "69995", "CODUSULIB" to "8", "VLRORIG" to origem, "DHLIBORIG" to "x", "NOMEUSU" to "L",
    )

    private val linhas = listOf(pendente("1", "21215.21", "19953.92"), pendente("2", "100.00", "150.00"))

    @Test
    fun `tenant sem modulo nao executa - nem consulta o Sankhya nem le o liberador`() = runBlocking {
        val porta = PortaPorTenant(emptyMap(), mapOf(outro to perfilNegri), linhas)
        assertEquals(0, LiberacaoHerdada.herdar("outro", outro, 70000, porta))
        assertTrue(porta.sqls.isEmpty())
        assertFalse(porta.credencialLida)
    }

    @Test
    fun `instalacao padrao - parametros vazios nao executam`() {
        assertNull(LiberacaoHerdada.configuracao(emptySet(), ParametrosTenant().liberacaoHerdada))
        assertNull(LiberacaoHerdada.configuracao(setOf(Modulos.LIBERACAO_HERDADA), ParametrosTenant().liberacaoHerdada))
    }

    @Test
    fun `modulo ligado sem eventos configurados nao executa`() = runBlocking {
        val porta = PortaPorTenant(mapOf(outro to setOf(Modulos.LIBERACAO_HERDADA)), emptyMap(), linhas)
        assertEquals(0, LiberacaoHerdada.herdar("outro", outro, 70000, porta))
        assertTrue(porta.sqls.isEmpty())
    }

    @Test
    fun `evento invalido na configuracao nao executa`() {
        assertNull(LiberacaoHerdada.configuracao(setOf(Modulos.LIBERACAO_HERDADA), ParametrosLiberacaoHerdada(eventos = listOf(0))))
    }

    @Test
    fun `falha ao ler a configuracao nao resulta em liberacao`() = runBlocking {
        val porta = PortaPorTenant(mapOf(negri to setOf(Modulos.LIBERACAO_HERDADA)), mapOf(negri to perfilNegri), linhas, configuracaoQuebrada = true)
        assertEquals(0, LiberacaoHerdada.herdar("negri", negri, 70000, porta))
        assertTrue(porta.sqls.isEmpty())
        assertTrue(porta.liberadas.isEmpty())
    }

    @Test
    fun `Negri - modulo ligado, evento 3, valor nao comparado - libera as duas`() = runBlocking {
        val porta = PortaPorTenant(mapOf(negri to setOf(Modulos.LIBERACAO_HERDADA)), mapOf(negri to perfilNegri), linhas)
        assertEquals(2, LiberacaoHerdada.herdar("negri", negri, 70000, porta))
        assertTrue("L.EVENTO IN (3)" in porta.sqls.single())
    }

    @Test
    fun `tenant com modulo e comparacao de valor - so herda o que cabe no liberado na origem`() = runBlocking {
        val cfg = ParametrosLiberacaoHerdada(eventos = listOf(3, 7), compararValor = true)
        val porta = PortaPorTenant(mapOf(outro to setOf(Modulos.LIBERACAO_HERDADA)), mapOf(outro to cfg), linhas)
        assertEquals(1, LiberacaoHerdada.herdar("outro", outro, 70000, porta))
        assertEquals("2", porta.liberadas.single()["SEQUENCIA"])
        assertTrue("L.EVENTO IN (3, 7)" in porta.sqls.single())
    }

    @Test
    fun `comparacao de valor - limite exato herda, valor ausente nao`() {
        assertTrue(LiberacaoHerdada.valorCobertoPelaOrigem(mapOf("VLRATUAL" to "150.00", "VLRORIG" to "150")))
        assertFalse(LiberacaoHerdada.valorCobertoPelaOrigem(mapOf("VLRATUAL" to "150.01", "VLRORIG" to "150")))
        assertFalse(LiberacaoHerdada.valorCobertoPelaOrigem(mapOf("VLRATUAL" to null, "VLRORIG" to "150")))
        assertFalse(LiberacaoHerdada.valorCobertoPelaOrigem(mapOf("VLRATUAL" to "1", "VLRORIG" to "")))
    }

    @Test
    fun `sem liberacao cruzada - a configuracao da Negri nao vale pra outro tenant na mesma porta`() = runBlocking {
        val porta = PortaPorTenant(mapOf(negri to setOf(Modulos.LIBERACAO_HERDADA)), mapOf(negri to perfilNegri), linhas)
        assertEquals(0, LiberacaoHerdada.herdar("outro", outro, 70000, porta))
        assertTrue(porta.sqls.isEmpty())
        assertEquals(2, LiberacaoHerdada.herdar("negri", negri, 70000, porta))
    }

    @Test
    fun `parametros - JSON da migracao V62 e lido como perfil Negri`() {
        val p = ParametrosTenant.ler("""{"liberacaoHerdada": {"eventos": [3], "compararValor": false}, "chaveDesconhecida": 1}""")
        assertEquals(perfilNegri, p.liberacaoHerdada)
    }

    @Test
    fun `parametros - vazio ou ilegivel vale os padroes seguros`() {
        assertEquals(ParametrosTenant(), ParametrosTenant.ler("{}"))
        assertEquals(ParametrosTenant(), ParametrosTenant.ler(null))
        assertEquals(ParametrosTenant(), ParametrosTenant.ler("{nao json"))
        assertEquals(emptyList(), ParametrosTenant().liberacaoHerdada.eventos)
        assertTrue(ParametrosTenant().liberacaoHerdada.compararValor)
    }
}
