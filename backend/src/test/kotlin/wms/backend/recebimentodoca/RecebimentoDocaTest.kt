package wms.backend.recebimentodoca

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Leitura do XML da NF-e do fornecedor (trechos reais da BRF e da Frisacre, 27/11/2026). */
class RecebimentoDocaTest {
    @Test
    fun `le os lotes do grupo rastro do item`() {
        val det = """<det nItem="3"><prod><cProd>000123</cProd><xProd>LINGUICA TOSCANA</xProd>
            <rastro><nLote>3316211068</nLote><qLote>30.000</qLote><dFab>2026-07-30</dFab><dVal>2026-10-13</dVal></rastro>
            <rastro><nLote>3316211070</nLote><qLote>12.000</qLote><dFab>2026-07-31</dFab><dVal>2026-10-14</dVal></rastro>
            </prod><imposto><ICMS></ICMS></imposto></det>"""
        val i = RecebimentoDoca.lerDet(3, det)
        assertEquals("000123", i.cProd)
        assertEquals(2, i.lotes.size)
        assertEquals("3316211068", i.lotes[0].lote)
        assertEquals(30.0, i.lotes[0].qtd)
        assertEquals("2026-10-13", i.lotes[0].validade)
    }

    @Test
    fun `item sem rastro nao tem lote`() {
        assertEquals(0, RecebimentoDoca.lerDet(1, "<det nItem=\"1\"><prod><cProd>9</cProd><xProd>X</xProd></prod></det>").lotes.size)
    }

    @Test
    fun `soma volumes e pesos do transporte`() {
        val t = """<transp><modFrete>0</modFrete><veicTransp><placa>ABC1D23</placa></veicTransp>
            <vol><qVol>40</qVol><esp>CAIXA</esp><pesoL>800.000</pesoL><pesoB>830.500</pesoB></vol>
            <vol><qVol>8</qVol><esp>CAIXA</esp><pesoL>100.000</pesoL><pesoB>104.000</pesoB></vol></transp>"""
        val n = RecebimentoDoca.lerTransp(t)
        assertEquals(48.0, n.volumes)
        assertEquals(934.5, n.pesoBruto)
        assertEquals("CAIXA", n.especie)
        assertEquals("ABC1D23", n.placa)
        assertNull(RecebimentoDoca.lerTransp("<transp></transp>").volumes)
    }
}
