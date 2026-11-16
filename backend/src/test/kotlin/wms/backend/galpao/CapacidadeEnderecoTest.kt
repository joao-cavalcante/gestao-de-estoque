package wms.backend.galpao

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Regra do Negri Planta Log 3D (Analytics.occupancy / StoragePolicy.validate). */
class CapacidadeEnderecoTest {

    private val fisicos = mapOf(
        1 to FisicoProduto(pesoBruto = 5.0, m3 = 0.01),
        2 to FisicoProduto(pesoBruto = 25.0, m3 = 0.05),
        3 to FisicoProduto(pesoBruto = null, m3 = null),
    )

    @Test
    fun `ocupacao e a maior razao entre quantidade, peso e volume`() {
        // 40 un de 25 kg = 1000 kg; cap 100 un / 2000 kg / 10 m³ → 40%, 50%, 20% → 50% pelo peso.
        val r = CapacidadeEndereco.ocupacao(Capacidade(100.0, 2000.0, 10.0), listOf(ItemNoEndereco(2, 40.0)), fisicos)
        assertEquals(0.5, r.ocupacao!!, 1e-9)
        assertEquals("PESO", r.base)
    }

    @Test
    fun `produto sem peso deixa o peso do endereco desconhecido, nao zero`() {
        val r = CapacidadeEndereco.ocupacao(Capacidade(100.0, 2000.0, null), listOf(ItemNoEndereco(1, 10.0), ItemNoEndereco(3, 10.0)), fisicos)
        assertNull(r.peso)
        assertEquals("QUANTIDADE", r.base)
        assertEquals(0.2, r.ocupacao!!, 1e-9)
    }

    @Test
    fun `sem nenhuma capacidade cadastrada e N-D e bloqueia`() {
        assertNull(CapacidadeEndereco.ocupacao(Capacidade(null, null, null), listOf(ItemNoEndereco(1, 1.0)), fisicos).ocupacao)
        assertEquals("Capacidade do endereço não parametrizada", CapacidadeEndereco.validarEntrada(Capacidade(null, null, null), emptyList(), 1, 1.0, fisicos))
    }

    @Test
    fun `cabe ate 100 por cento e bloqueia acima`() {
        val cap = Capacidade(100.0, null, null)
        assertNull(CapacidadeEndereco.validarEntrada(cap, listOf(ItemNoEndereco(1, 60.0)), 1, 40.0, fisicos))
        assertTrue(CapacidadeEndereco.validarEntrada(cap, listOf(ItemNoEndereco(1, 60.0)), 1, 41.0, fisicos)!!.startsWith("Sobrecapacidade"))
    }

    @Test
    fun `peso estoura antes da quantidade`() {
        // 30 un × 25 kg = 750 kg numa cap de 500 kg (quantidade 30/100 = 30%).
        val erro = CapacidadeEndereco.validarEntrada(Capacidade(100.0, 500.0, null), emptyList(), 2, 30.0, fisicos)
        assertTrue(erro!!.contains("peso"), erro)
    }

    @Test
    fun `endereco com capacidade de peso exige peso cadastrado no produto`() {
        val erro = CapacidadeEndereco.validarEntrada(Capacidade(100.0, 500.0, null), emptyList(), 3, 1.0, fisicos)
        assertTrue(erro!!.startsWith("Peso ou volume do produto"), erro)
    }
}
