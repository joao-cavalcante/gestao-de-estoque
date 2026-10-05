package wms.backend.produtos

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class NomeProdutoTest {

    @Test
    fun `descricao - complemento`() {
        assertEquals("QUEIJO PARMESAO SCALA - 6 MESES", NomeProduto.formatar(" QUEIJO PARMESAO SCALA ", "6 MESES "))
        assertEquals("QUEIJO PARMESAO SCALA", NomeProduto.formatar("QUEIJO PARMESAO SCALA", "  "))
        assertEquals("QUEIJO PARMESAO SCALA", NomeProduto.formatar("QUEIJO PARMESAO SCALA", null))
        assertNull(NomeProduto.formatar(null, null))
    }

    @Test
    fun `observacao da liberacao no mesmo formato`() {
        assertEquals("ALGA NORI 140GR - 50FLS", NomeProduto.daObservacaoLiberacao("ALGA NORI 140GR, Complem.: 50FLS"))
        assertEquals("X - Y · L1", NomeProduto.daObservacaoLiberacao("X, Complem.: Y, Controle: L1"))
    }

    @Test
    fun `ordem alfabetica ignora acento e maiuscula, nulo no fim`() {
        val nomes = listOf("queijo", null, "Açúcar", "ACUCAR CRISTAL", "Bacon", "abacaxi")
        assertEquals(listOf("abacaxi", "Açúcar", "ACUCAR CRISTAL", "Bacon", "queijo", null), nomes.sortedWith(NomeProduto.ORDEM))
    }
}
