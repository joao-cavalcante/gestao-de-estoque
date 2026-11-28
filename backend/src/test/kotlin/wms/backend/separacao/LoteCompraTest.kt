package wms.backend.separacao

import java.math.BigDecimal
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** D3 — o lido sem lote vai ao Sankhya dividido pelos lotes dos paletes. */
class LoteCompraTest {
    private fun d(s: String) = BigDecimal(s)
    private fun linha(lote: String, q: String) = "P1" to LoteCompra.LinhaLote(lote, d(q), LocalDate.of(2027, 5, 1), null)
    private val g = SeparacaoRepository.GrupoConferido(3016, " ", d("30"))

    @Test
    fun `divide proporcional e a ultima parte leva o resto`() {
        val r = LoteCompra.dividir(listOf(g), setOf(3016), mapOf(3016 to listOf(linha("A", "10"), linha("B", "20"), linha("A", "0"))), mapOf(3016 to d("30")))
        assertEquals(listOf("A", "B"), r.map { it.controle })
        assertEquals(d("10.00000"), r[0].qtdTotal)
        assertEquals(0, d("30").compareTo(r.fold(BigDecimal.ZERO) { a, x -> a + x.qtdTotal }))
        assertTrue(r.all { it.controleOrigem == " " })
    }

    @Test
    fun `pesavel divide liquido e tara`() {
        val p = g.copy(qtdTotal = d("100"), pesoLiquido = d("100"), pesoBruto = d("110"))
        val r = LoteCompra.dividir(listOf(p), setOf(3016), mapOf(3016 to listOf(linha("A", "60"), linha("B", "40"))), mapOf(3016 to d("100")))
        assertEquals(0, d("60").compareTo(r[0].pesoLiquido))
        assertEquals(0, d("66").compareTo(r[0].pesoBruto))
        assertEquals(0, d("44").compareTo(r[1].pesoBruto))
    }

    @Test
    fun `lote pendente ou lido fora de palete bloqueia`() {
        assertFailsWith<LoteCompra.LoteFaltandoException> {
            LoteCompra.dividir(listOf(g), setOf(3016), mapOf(3016 to listOf(linha("", "30"))), mapOf(3016 to d("30")))
        }
        assertFailsWith<LoteCompra.LoteFaltandoException> {
            LoteCompra.dividir(listOf(g), setOf(3016), mapOf(3016 to listOf(linha("A", "20"))), mapOf(3016 to d("30")))
        }
    }

    @Test
    fun `produto sem lote ou grupo ja com lote passa igual`() {
        val outro = g.copy(codprod = 1)
        val comLote = g.copy(controle = "L9")
        assertEquals(listOf(outro, comLote), LoteCompra.dividir(listOf(outro, comLote), setOf(3016), emptyMap(), emptyMap()))
    }

    @Test
    fun `corpo do salvarInformacoesLote`() {
        val c = LoteCompra.corpoValidades(listOf(Triple(2, LocalDate.of(2027, 5, 1), LocalDate.of(2026, 11, 1))), 74000).toString()
        assertTrue(c.contains("\"DTVALID\":\"01/05/2027\""))
        assertTrue(c.contains("\"SEQUENCIA\":\"2\""))
        assertTrue(c.contains("\"CAMPOS\":{}"))
    }

    @Test
    fun `detecta produto com lote no JSON com ou sem espacos`() {
        assertTrue(LoteCompra.temLote("""{"Produto.TIPCONTEST": "L", "Produto.LISCONTEST": null}"""))
        assertTrue(LoteCompra.temLote("""{"Produto.TIPCONTEST":"L"}"""))
        assertEquals(false, LoteCompra.temLote("""{"Produto.TIPCONTEST": "N"}"""))
        assertEquals(false, LoteCompra.temLote("lixo"))
    }

    @Test
    fun `corpo do dividirLotes soma o mesmo lote de varios paletes`() {
        val lotes = mapOf(
            3016 to listOf("P1" to LoteCompra.LinhaLote("A", d("120"), null, null), "P2" to LoteCompra.LinhaLote("A", d("40"), null, null),
                "P2" to LoteCompra.LinhaLote("B", d("80"), null, null)),
            347 to listOf("P3" to LoteCompra.LinhaLote("F1", d("200"), null, null)),
        )
        val c = LoteCompra.corpoDividir(73714, lotes, setOf(3016)).toString()
        assertTrue(c.contains("\"NUNOTA\":\"73714\""))
        assertTrue(c.contains("{\"CODPROD\":\"3016\",\"LOTE\":\"A\",\"QTD\":\"160\"}"))
        assertTrue(c.contains("\"LOTE\":\"B\",\"QTD\":\"80\""))
        assertTrue(!c.contains("F1"))
    }
}
