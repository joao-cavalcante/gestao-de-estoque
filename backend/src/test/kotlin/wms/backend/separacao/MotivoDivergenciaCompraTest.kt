package wms.backend.separacao

import java.math.BigDecimal
import kotlin.test.Test
import kotlin.test.assertTrue

class MotivoDivergenciaCompraTest {
    private val item = MotivoDivergenciaCompra.LinhaItem(337, "KG", BigDecimal("10"), BigDecimal("8.5"))

    @Test
    fun `linha mostra nota, conferido e percentual`() {
        val l = MotivoDivergenciaCompra.linha(item, "PEITO PERU DEFUMADO SEARA")
        assertTrue("337 PEITO PERU DEFUMADO SEARA: nota 10,000 KG, conferido 8,500 KG (-15,0%)" == l, l)
    }

    @Test
    fun `texto do ajuste traz tolerancia, sentido e marcador`() {
        val t = MotivoDivergenciaCompra.textoAjuste(
            83548, 9080, entrada = false,
            itens = listOf(MotivoDivergenciaCompra.LinhaItem(3243, "KG", BigDecimal("7422"), BigDecimal("7392.64")) to null),
            tolAcima = BigDecimal("15"), tolAbaixo = BigDecimal("10"), marcador = "[TO-AJPESO x S]",
        )
        assertTrue("conferência 9080" in t, t)
        assertTrue("nota 7422,000 KG, conferido 7392,640 KG (-0,4%)" in t, t)
        assertTrue("acima 15,0%, abaixo 10,0%" in t, t)
        assertTrue("a menor SAI" in t, t)
        assertTrue(t.endsWith("[TO-AJPESO x S]"), t)
    }
}
