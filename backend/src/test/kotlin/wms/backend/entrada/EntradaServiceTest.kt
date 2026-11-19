package wms.backend.entrada

import java.math.BigDecimal
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Tela Entrada e mesa de liberação — regras puras (situação, tipo do item, janela, leitura do Sankhya). */
class EntradaServiceTest {

    private fun bd(s: String) = BigDecimal(s)

    @Test
    fun `compra e pedido de compra sao entrada - venda e pedido nao`() {
        assertTrue(EntradaService.ehEntrada("C"))
        assertTrue(EntradaService.ehEntrada(" o "))
        assertFalse(EntradaService.ehEntrada("V"))
        assertFalse(EntradaService.ehEntrada("P"))
        assertFalse(EntradaService.ehEntrada(null))
    }

    @Test
    fun `situacao segue a conferencia ate concluir`() {
        assertEquals(SituacaoRecebimento.AGUARDANDO_CONFERENCIA, EntradaService.situacao("aguardando", null))
        assertEquals(SituacaoRecebimento.AGUARDANDO_CONFERENCIA, EntradaService.situacao("aguardando_liberacao", null))
        assertEquals(SituacaoRecebimento.EM_CONFERENCIA, EntradaService.situacao("andamento", null))
        assertEquals(SituacaoRecebimento.EM_CONFERENCIA, EntradaService.situacao("recontagem_andamento", null))
        assertEquals(SituacaoRecebimento.EM_CONFERENCIA, EntradaService.situacao("aguardando_recontagem", true))
        assertEquals(SituacaoRecebimento.DIVERGENCIA, EntradaService.situacao("aguardando_corte", null))
    }

    @Test
    fun `conferida aguarda liberacao ate a nota ser confirmada`() {
        assertEquals(SituacaoRecebimento.AGUARDANDO_LIBERACAO, EntradaService.situacao("concluido", null))
        assertEquals(SituacaoRecebimento.AGUARDANDO_LIBERACAO, EntradaService.situacao("concluido_divergente", false))
        assertEquals(SituacaoRecebimento.LIBERADA, EntradaService.situacao("concluido", true))
        assertEquals(SituacaoRecebimento.LIBERADA, EntradaService.situacao("recontagem_concluida_divergente", true))
    }

    @Test
    fun `tipo do item na mesa`() {
        assertEquals(TipoItemMesa.OK, EntradaService.tipoItem(bd("10"), bd("10.000"), false, null))
        assertEquals(TipoItemMesa.FALTA, EntradaService.tipoItem(bd("10"), bd("8"), false, null))
        assertEquals(TipoItemMesa.SOBRA, EntradaService.tipoItem(bd("10"), bd("12"), false, null))
        // Diferença dentro da tolerância que foi para a nota de ajuste de peso.
        assertEquals(TipoItemMesa.AJUSTE, EntradaService.tipoItem(bd("12.708"), bd("12.958"), false, bd("0.250")))
        assertEquals(TipoItemMesa.OK, EntradaService.tipoItem(bd("10"), bd("10"), false, bd("0")))
        assertEquals(TipoItemMesa.FORA_PEDIDO, EntradaService.tipoItem(bd("0"), bd("3"), true, null))
    }

    @Test
    fun `janela pela data de negociacao`() {
        val hoje = LocalDate.of(2026, 11, 19)
        assertTrue(EntradaService.dentroDaJanela("04/11/2026 00:00:00", 15, hoje))
        assertFalse(EntradaService.dentroDaJanela("03/11/2026", 15, hoje))
        assertTrue(EntradaService.dentroDaJanela(null, 15, hoje))
        assertTrue(EntradaService.dentroDaJanela("ilegível", 15, hoje))
    }

    @Test
    fun `numero do DbExplorer`() {
        assertEquals(84146L, EntradaSituacao.longo("84146"))
        assertEquals(84146L, EntradaSituacao.longo("84146.0"))
        assertNull(EntradaSituacao.longo("0"))
        assertNull(EntradaSituacao.longo(""))
        assertNull(EntradaSituacao.longo(null))
    }

    @Test
    fun `consulta da situacao le conferencia devolucao e complemento`() {
        val sql = EntradaSituacao.sql(listOf(1L, 2L))
        assertTrue(sql.startsWith("SELECT"))
        assertTrue("LEFT JOIN TGFCON2 K ON K.NUCONF = C.NUCONFATUAL" in sql)
        assertTrue("K.NUNOTADEV" in sql && "K.NUPEDCOMP" in sql)
        assertTrue(sql.endsWith("WHERE C.NUNOTA IN (1,2)"))
    }
}
