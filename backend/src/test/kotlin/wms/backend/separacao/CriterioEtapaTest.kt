package wms.backend.separacao

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Critério de etapa da conferência — por peso (ativo) e por temperatura (desativado, mantido pra voltar). */
class CriterioEtapaTest {

    @Test
    fun `padrao sem variavel de ambiente e por peso`() {
        assertEquals(CriterioEtapa.Criterio.PESO, CriterioEtapa.ATIVO)
        assertEquals(listOf(4, 5), CriterioEtapa.TIPOS_ATIVOS)
    }

    // ─── Critério PESO ─────────────────────────────────────────────────────

    @Test
    fun `peso - pesavel vai pra etapa Pesaveis seja qual for a temperatura`() {
        for (ad in 1..3) assertEquals(CriterioEtapa.PESAVEL, CriterioEtapa.tipo(ad.toShort(), pesavel = true, porPeso = true))
    }

    @Test
    fun `peso - nao pesavel vai pra etapa Nao pesaveis seja qual for a temperatura`() {
        for (ad in 1..3) assertEquals(CriterioEtapa.NAO_PESAVEL, CriterioEtapa.tipo(ad.toShort(), pesavel = false, porPeso = true))
    }

    @Test
    fun `peso - item fora do pedido sem etapa ativa nasce em Nao pesaveis`() {
        assertEquals(CriterioEtapa.NAO_PESAVEL, CriterioEtapa.TIPO_PADRAO)
    }

    // ─── Critério TEMPERATURA (desativado) ─────────────────────────────────

    @Test
    fun `temperatura - mantem AD_TIPOSEPARACAO e ignora o pesavel`() {
        for (ad in 1..3) {
            assertEquals(ad.toShort(), CriterioEtapa.tipo(ad.toShort(), pesavel = true, porPeso = false))
            assertEquals(ad.toShort(), CriterioEtapa.tipo(ad.toShort(), pesavel = false, porPeso = false))
        }
    }

    // ─── Critério da sessão (sincronização não remexe sessão aberta) ───────

    @Test
    fun `sessao aberta no criterio antigo continua por temperatura`() {
        assertFalse(CriterioEtapa.sessaoPorPeso(setOf(1, 2, 3)))
        assertFalse(CriterioEtapa.sessaoPorPeso(setOf(2)))
    }

    @Test
    fun `sessao aberta por peso continua por peso`() {
        assertTrue(CriterioEtapa.sessaoPorPeso(setOf(4, 5)))
        assertTrue(CriterioEtapa.sessaoPorPeso(setOf(5)))
    }

    @Test
    fun `sessao sem itens segue o criterio ativo`() {
        assertTrue(CriterioEtapa.sessaoPorPeso(emptySet()))
    }

    // ─── Carregamento ──────────────────────────────────────────────────────

    @Test
    fun `nao pesaveis carregam na conferencia e pesaveis vao pro checklist como o Refrigerado`() {
        assertTrue(4 in CriterioEtapa.CARREGA_NA_CONFERENCIA)
        assertFalse(5 in CriterioEtapa.CARREGA_NA_CONFERENCIA)
        assertFalse(2 in CriterioEtapa.CARREGA_NA_CONFERENCIA)
    }

    @Test
    fun `sessoes antigas mantem a regra Secos e Congelado carregam`() {
        assertTrue(1 in CriterioEtapa.CARREGA_NA_CONFERENCIA)
        assertTrue(3 in CriterioEtapa.CARREGA_NA_CONFERENCIA)
    }

    @Test
    fun `nomes das etapas`() {
        assertEquals("Não pesáveis", CriterioEtapa.nome(4))
        assertEquals("Pesáveis", CriterioEtapa.nome(5))
        assertEquals("Secos", CriterioEtapa.nome(1))
        assertEquals("Refrigerado", CriterioEtapa.nome(2))
        assertEquals("Congelado", CriterioEtapa.nome(3))
    }
}
