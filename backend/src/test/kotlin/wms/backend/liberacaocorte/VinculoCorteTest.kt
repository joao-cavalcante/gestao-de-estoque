package wms.backend.liberacaocorte

import wms.backend.liberacaocorte.VinculoCorte.Chave
import wms.backend.liberacaocorte.VinculoCorte.LinhaDetalhe
import java.math.BigDecimal
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Regra da SEQUENCIA (ConferenciaHelper.ajustarDemanda) com casos reais da Negri (02/10/2026). */
class VinculoCorteTest {

    private fun pedido(cp: Int, vol: String, qtd: String) = LinhaDetalhe('P', cp, " ", vol, BigDecimal(qtd), 0)
    private fun conferido(cp: Int, vol: String, qtd: String, seqconf: Int) = LinhaDetalhe('C', cp, " ", vol, BigDecimal(qtd), seqconf)
    private fun naNota(vararg cps: Int) = cps.map { LinhaDetalhe('N', it, " ", null, BigDecimal.ZERO, 0) }
    private fun k(cp: Int) = Chave(cp, " ")
    private fun Map<Int, List<Chave>>.simples() = mapValues { it.value.single().codprod }

    @Test
    fun `unidade igual - SEQUENCIA e a propria SEQCONF (nuconf 1090)`() {
        val linhas = listOf(
            pedido(244, "KG", "204"), conferido(244, "KG", "203", 1),
            pedido(243, "KG", "11.7"), conferido(243, "KG", "11.5", 2),
            pedido(245, "KG", "18.7"), conferido(245, "KG", "18.5", 3),
            pedido(30, "KG", "10"), conferido(30, "KG", "10", 4), // sem divergência: não pede liberação
            pedido(27, "KG", "1255"), conferido(27, "KG", "1257", 5),
        ) + naNota(244, 243, 245, 30, 27)
        assertEquals(mapOf(5 to 27, 3 to 245, 2 to 243, 1 to 244), VinculoCorte.preverSequencias(linhas).simples())
    }

    @Test
    fun `unidade diferente (pesavel PC x KG) - contador na ordem SEQCONF decrescente (nuconf 1109)`() {
        val linhas = listOf(
            pedido(1460, "PC", "1"), conferido(1460, "KG", "5.78", 1),
            pedido(1461, "PC", "1"), conferido(1461, "KG", "4.14", 2),
            pedido(214, "PC", "1"), conferido(214, "KG", "4.26", 3),
        ) + naNota(1460, 1461, 214)
        assertEquals(mapOf(1 to 214, 2 to 1461, 3 to 1460), VinculoCorte.preverSequencias(linhas).simples())
    }

    @Test
    fun `misto - SEQCONF fixa sobe o contador (nuconf 1053)`() {
        val linhas = listOf(
            pedido(1397, "KG", "10"), conferido(1397, "KG", "141", 9),
            pedido(3609, "CX", "1"), conferido(3609, "KG", "5.17", 5),
            pedido(3071, "CX", "1"), conferido(3071, "KG", "50.7", 2),
            pedido(1455, "CX", "1"), conferido(1455, "KG", "61.6", 1),
        ) + naNota(1397, 3609, 3071, 1455)
        assertEquals(mapOf(9 to 1397, 10 to 3609, 11 to 3071, 12 to 1455), VinculoCorte.preverSequencias(linhas).simples())
    }

    @Test
    fun `corte total (conferido 0) so tem a linha do pedido - contador depois dos conferidos`() {
        val linhas = listOf(
            pedido(1479, "PC", "4"), conferido(1479, "KG", "10", 2),
            pedido(2701, "FD", "1"), // nada conferido
            pedido(1518, "BI", "2"), conferido(1518, "BI", "2", 1), // ok, mesma unidade, sem divergência
        ) + naNota(1479, 2701, 1518)
        // ordem MINSEQ DESC, CODPROD: 1479(2), 1518(1), 2701(0) -> 1479 pega 1 e 2701 pega 2;
        // 1518 não diverge, então o "1" do contador não colide com a SEQCONF dele.
        assertEquals(mapOf(1 to 1479, 2 to 2701), VinculoCorte.preverSequencias(linhas).simples())
    }

    @Test
    fun `produto fora do pedido nao gera liberacao nem consome contador`() {
        val linhas = listOf(
            conferido(500, "UN", "3", 2), // sem item na nota
            pedido(214, "PC", "1"), conferido(214, "KG", "4.26", 1),
        ) + naNota(214)
        assertEquals(mapOf(1 to 214), VinculoCorte.preverSequencias(linhas).simples())
    }

    @Test
    fun `liberacao herdada ja cortada entra forcada e mantem a numeracao`() {
        // 1461 foi herdada e cortada na mesma rodada (pedido virou 4.14 = conferido); sem forçar,
        // o 1460 viraria seq 2 em vez de 3.
        val linhas = listOf(
            pedido(1460, "PC", "1"), conferido(1460, "KG", "5.78", 1),
            pedido(1461, "PC", "4.14"), conferido(1461, "KG", "4.14", 2),
            pedido(214, "PC", "1"), conferido(214, "KG", "4.26", 3),
        ) + naNota(1460, 1461, 214)
        val semForcar = VinculoCorte.preverSequencias(linhas).simples()
        assertEquals(1460, semForcar[2])
        val forcado = VinculoCorte.preverSequencias(linhas, forcarDivergentes = setOf(k(1461))).simples()
        assertEquals(214, forcado[1])
        assertEquals(1461, forcado[2])
        assertEquals(1460, forcado[3])
    }

    @Test
    fun `herdada removida da nota volta com linha de pedido MINSEQ 0`() {
        val linhas = listOf(pedido(214, "PC", "1"), conferido(214, "KG", "4.26", 1)) + naNota(214)
        val r = VinculoCorte.preverSequencias(linhas, forcarDivergentes = setOf(k(999))).simples()
        assertEquals(mapOf(1 to 214, 2 to 999), r)
    }

    @Test
    fun `CCO nao ajustar a maior pula o item sem consumir contador`() {
        val linhas = listOf(
            pedido(1, "CX", "1"), conferido(1, "KG", "9", 2), // a maior
            pedido(2, "CX", "5"), conferido(2, "KG", "1", 1), // a menor
        ) + naNota(1, 2)
        assertEquals(mapOf(1 to 2), VinculoCorte.preverSequencias(linhas, ignorarAMaior = true).simples())
    }

    @Test
    fun `texto do produto igual ao da OBSERVACAO`() {
        assertEquals("QUEIJO PARMESAO SCALA, COMPLEM.: 6 MESES", VinculoCorte.textoProduto("Queijo Parmesao Scala", "6 MESES", " "))
        assertEquals("X, CONTROLE: L1", VinculoCorte.textoProduto("x", null, "L1"))
        assertNull(VinculoCorte.textoProduto("  ", null, null))
        assertEquals(
            "LOMBO CANADENSE GOURMET DELI",
            VinculoCorte.produtoDaObservacao("Prod.: LOMBO CANADENSE GOURMET DELI, Qtd. total conf.: 0E+2 PC, Qtd. total pedido/nota: 1 PC"),
        )
        assertTrue(VinculoCorte.produtoDaObservacao("texto qualquer") == null)
    }
}
