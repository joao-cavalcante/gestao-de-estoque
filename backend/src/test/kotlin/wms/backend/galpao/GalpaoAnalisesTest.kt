package wms.backend.galpao

import wms.backend.galpao.GalpaoAnalises.LinhaVenda
import wms.backend.galpao.GalpaoAnalises.MetricaAbc
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Mesmos casos do AnalyticsTest do Negri Planta Log 3D + montagem da fotografia. */
class GalpaoAnalisesTest {
    private val hoje = LocalDate.of(2026, 10, 30)
    private fun venda(cod: Int, valor: Double, qtd: Double = 0.0, freq: Int = 0) = LinhaVenda(cod, valor, qtd, freq, hoje)

    @Test
    fun `ABC usa o universo inteiro e as participacoes fecham 100`() {
        val r = GalpaoAnalises.abc(listOf(venda(1, 80.0), venda(2, 15.0), venda(3, 5.0)), MetricaAbc.VALOR, 80.0, 95.0)
        assertEquals(listOf("A", "B", "C"), listOf(1, 2, 3).map { r.getValue(it).classe })
        assertEquals(100.0, r.values.sumOf { it.participacao!! }, 1e-9)
    }

    @Test
    fun `ABC sem demanda nao tem classe`() {
        assertEquals("N/D", GalpaoAnalises.abc(listOf(venda(1, 0.0)), MetricaAbc.VALOR, 80.0, 95.0).getValue(1).classe)
    }

    @Test
    fun `ABC desempata pelo codigo e recusa limites invertidos`() {
        val r = GalpaoAnalises.abc(listOf(venda(2, 10.0), venda(1, 10.0)), MetricaAbc.VALOR, 80.0, 95.0)
        assertEquals(50.0, r.getValue(1).acumulado!!, 1e-9)
        assertEquals(100.0, r.getValue(2).acumulado!!, 1e-9)
        assertFailsWith<IllegalArgumentException> { GalpaoAnalises.abc(emptyList(), MetricaAbc.VALOR, 95.0, 80.0) }
    }

    @Test
    fun `ABC por quantidade e por frequencia usam a propria metrica`() {
        // Valor: 1 tem 80%; quantidade e frequência: 2 tem 80%.
        val vendas = listOf(LinhaVenda(1, 80.0, 20.0, 20, null), LinhaVenda(2, 20.0, 80.0, 80, null))
        assertEquals("A", GalpaoAnalises.abc(vendas, MetricaAbc.VALOR, 80.0, 95.0).getValue(1).classe)
        assertEquals("A", GalpaoAnalises.abc(vendas, MetricaAbc.QUANTIDADE, 80.0, 95.0).getValue(2).classe)
        assertEquals("C", GalpaoAnalises.abc(vendas, MetricaAbc.FREQUENCIA, 80.0, 95.0).getValue(1).classe)
    }

    @Test
    fun `ABC como no Planta Log - classe pelo acumulado incluindo o proprio produto`() {
        // Um produto com 99% do valor passa do limite A (80%) já sozinho: C, igual ao modelo.
        val r = GalpaoAnalises.abc(listOf(venda(1, 99.0), venda(2, 1.0)), MetricaAbc.VALOR, 80.0, 95.0)
        assertEquals("C", r.getValue(1).classe)
    }

    @Test
    fun `cobertura sem demanda e nula e negativa se mantem`() {
        assertNull(GalpaoAnalises.cobertura(10.0, 2.0, 0.0, 90).dias)
        assertEquals(-3.0, GalpaoAnalises.cobertura(-2.0, 1.0, 90.0, 90).dias!!, 1e-9)
    }

    @Test
    fun `faixas de validade`() {
        assertEquals("N/D", GalpaoAnalises.validade(null, hoje))
        assertEquals("VENCIDO", GalpaoAnalises.validade(hoje.minusDays(1), hoje))
        assertEquals("ATE_30", GalpaoAnalises.validade(hoje.plusDays(30), hoje))
        assertEquals("31_90", GalpaoAnalises.validade(hoje.plusDays(31), hoje))
        assertEquals("31_90", GalpaoAnalises.validade(hoje.plusDays(90), hoje))
        assertEquals("ACIMA_90", GalpaoAnalises.validade(hoje.plusDays(91), hoje))
    }

    @Test
    fun `idade nao inventa FIFO`() {
        assertEquals("APROXIMADO — última compra", GalpaoAnalises.idade(null, hoje.minusDays(3), hoje).origem)
        assertNull(GalpaoAnalises.idade(hoje.plusDays(1), null, hoje).dias)
        assertEquals("EXATO", GalpaoAnalises.idade(hoje.minusDays(5), hoje.minusDays(1), hoje).origem)
    }

    @Test
    fun `saude ignora pesos sem dado`() {
        val r = GalpaoAnalises.saude(mapOf("CONSISTENCIA" to 1.0, "COBERTURA" to 0.0))
        assertEquals(50, r.score)
        assertEquals(0.5, r.confianca, 1e-9)
        assertEquals("ATENÇÃO", r.faixa)
        assertNull(GalpaoAnalises.saude(emptyMap()).score)
    }

    private val p = ParametrosAnaliseDto()
    private fun produto(cod: Int, setor: Int? = 1, setorGrupo: Int? = null) =
        GalpaoAnalisesService.LinhaProduto(cod, "P$cod", true, "UN", 10, "G", setor, setorGrupo, null, null)

    @Test
    fun `montagem - consistente quando endereçado igual ao contabil e parado sem venda`() {
        val r = GalpaoAnalisesService.montar(
            produtos = listOf(produto(1), produto(2)),
            estoque = listOf(
                GalpaoAnalisesService.LinhaEstoque(1, "", 10.0, 0.0, null, null),
                GalpaoAnalisesService.LinhaEstoque(2, "", 5.0, 0.0, hoje.plusDays(10), null),
            ),
            vendas = listOf(LinhaVenda(1, 80.0, 90.0, 3, hoje), LinhaVenda(9, 20.0, 1.0, 1, hoje)),
            compras = emptyList(),
            enderecado = mapOf(1 to 10.0),
            p = p, hoje = hoje,
        ).produtos.associateBy { it.codprod }
        val um = r.getValue(1)
        val dois = r.getValue(2)
        assertEquals(1.0, um.saude.componentes.first { it.componente == "CONSISTENCIA" }.qualidade)
        assertEquals(0.0, dois.saude.componentes.first { it.componente == "CONSISTENCIA" }.qualidade)
        assertFalse(um.parado)
        assertTrue(dois.parado)
        assertEquals("A", um.abc)
        assertEquals("N/D", dois.abc)
        assertEquals("ATE_30", dois.validade)
        assertEquals(10.0, um.cobertura!!, 1e-9) // 10 / (90/90)
    }

    @Test
    fun `montagem - setor do grupo so com o parametro ligado e idade pela ultima compra do lote`() {
        val estoque = listOf(GalpaoAnalisesService.LinhaEstoque(1, "L1", 1.0, 0.0, null, null))
        val compras = listOf(GalpaoAnalisesService.LinhaCompra(1, "L1", hoje.minusDays(4)), GalpaoAnalisesService.LinhaCompra(1, "OUTRO", hoje.minusDays(40)))
        val desligado = GalpaoAnalisesService.montar(listOf(produto(1, null, 2)), estoque, emptyList(), compras, emptyMap(), p, hoje).produtos[0]
        val ligado = GalpaoAnalisesService.montar(listOf(produto(1, null, 2)), estoque, emptyList(), compras, emptyMap(), p.copy(setorPeloGrupo = true), hoje).produtos[0]
        assertNull(desligado.setor)
        assertNull(desligado.saude.componentes.first { it.componente == "ENDERECO" }.qualidade)
        assertEquals(2, ligado.setor)
        assertEquals("GRUPO", ligado.setorOrigem)
        assertEquals(4L, desligado.idadeDias)
        assertEquals("APROXIMADO — última compra", desligado.idadeOrigem)
    }
}
