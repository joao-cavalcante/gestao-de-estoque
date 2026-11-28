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

    @Test
    fun `le a resposta do addon RecebimentoSP doca`() {
        val json = kotlinx.serialization.json.Json.parseToJsonElement(
            """{"body":{"CONTRATO":"4","XML":{"ENCONTRADO":"S","VOLUMES":48,"ESPECIE":"CAIXA","PESOB":612.4,"PESOL":580,"PLACA":"ABC1D23",
            "ITENS":[{"NITEM":1,"CODPROD":512,"CPROD":"X1","XPROD":"BACON","LOTES":[{"LOTE":"L1","QTD":580,"DFAB":"2026-11-01","DVAL":"2027-01-01"}]}]},
            "ITENS":[{"SEQUENCIA":1,"CODPROD":512,"DESCRPROD":"BACON MANTA","CODVOL":"KG","QTDNEG":580,"VLRUNIT":20.5,"UNCAIXA":"CX","FATORCAIXA":12.1}]}}"""
        ) as kotlinx.serialization.json.JsonObject
        val (nota, itens) = RecebimentoDoca.lerAddon(json)
        assertEquals(true, nota.encontrado)
        assertEquals(48.0, nota.volumes)
        assertEquals("ABC1D23", nota.placa)
        assertEquals("L1", nota.itens.single().lotes.single().lote)
        assertEquals(512, nota.itens.single().codprod)
        assertEquals(12.1, itens.single().fatorCaixa)
        assertEquals(false, itens.single().temPedido)
    }

    @Test
    fun `estima caixas da nota em KG so depois de contar`() {
        val item = RecebimentoDoca.ItemNotaPublico(512, "BACON", 580.0, 20.0, false, null, null, null, null, "KG", "CX", 12.1)
        val emCaixa = item.copy(codprod = 600, codvol = "CX")
        assertEquals(emptyList(), RecebimentoDoca.estimarCaixas(listOf(item), emptyMap()))
        val ok = RecebimentoDoca.estimarCaixas(listOf(item, emCaixa), mapOf(512 to (47.0 to 575.0), 600 to (10.0 to 10.0))).single()
        assertEquals(47.9, ok.caixasEstimadas)
        assertEquals(false, ok.alerta)
        val falta = RecebimentoDoca.estimarCaixas(listOf(item), mapOf(512 to (40.0 to 480.0))).single()
        assertEquals(true, falta.alerta)
    }
}
