package wms.backend.separacao

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import wms.backend.tenancy.ParametrosAjustePesoCompra
import java.math.BigDecimal
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Ajuste de peso na compra — decisão do peso enviado, parâmetros e payloads (nota 79759 como referência). */
class AjustePesoCompraTest {

    private fun bd(s: String) = BigDecimal(s)

    private val desligado = ParametrosAjustePesoCompra()
    /** Faixas ligadas, ajuste nos dois sentidos. */
    private val ajuste = ParametrosAjustePesoCompra(habilitado = true, ajusteEntrada = true, ajusteSaida = true, topEntrada = 1495, topSaida = 1195)

    // ─── Desligado = regra de 19/10 ──────────────────────────────────────

    @Test
    fun `desligado - a maior dentro da tolerancia vai com o peso da nota sem ajuste`() {
        val d = AjustePesoCompra.decidir(bd("12.708"), bd("12.958"), 2.0, 5.0, desligado)
        assertEquals(0, d.enviar.compareTo(bd("12.708")))
        assertNull(d.diferenca)
    }

    @Test
    fun `desligado - a menor vai com o lido`() {
        val d = AjustePesoCompra.decidir(bd("12.708"), bd("12.500"), 2.0, 5.0, desligado)
        assertEquals(0, d.enviar.compareTo(bd("12.500")))
        assertNull(d.diferenca)
    }

    @Test
    fun `desligado - sem limite a maior continua indo com o peso da nota`() {
        val d = AjustePesoCompra.decidir(bd("10"), bd("15"), null, 2.0, desligado)
        assertEquals(0, d.enviar.compareTo(bd("10")))
    }

    // ─── Faixa 2: ajuste ─────────────────────────────────────────────────

    @Test
    fun `ajuste - Brie a maior 1,97 por cento gera ajuste de entrada de 0,250`() {
        val d = AjustePesoCompra.decidir(bd("12.708"), bd("12.958"), 2.0, 2.0, ajuste)
        assertEquals(0, d.enviar.compareTo(bd("12.708")))
        assertEquals(0, d.diferenca!!.compareTo(bd("0.250")))
        assertEquals(AjustePesoCompra.Faixa.AJUSTAR, d.faixa)
    }

    @Test
    fun `ajuste - a menor dentro da tolerancia vai com o peso da nota e gera ajuste de saida`() {
        val d = AjustePesoCompra.decidir(bd("12.708"), bd("12.500"), 2.0, 2.0, ajuste)
        assertEquals(0, d.enviar.compareTo(bd("12.708")))
        assertEquals(0, d.diferenca!!.compareTo(bd("-0.208")))
    }

    @Test
    fun `acima da tolerancia vai o lido e nao ajusta`() {
        val d = AjustePesoCompra.decidir(bd("10"), bd("10.5"), 2.0, 2.0, ajuste)
        assertEquals(0, d.enviar.compareTo(bd("10.5")))
        assertNull(d.diferenca)
        assertEquals(AjustePesoCompra.Faixa.DIVERGENCIA, d.faixa)
        val m = AjustePesoCompra.decidir(bd("10"), bd("9.5"), 2.0, 2.0, ajuste)
        assertEquals(0, m.enviar.compareTo(bd("9.5")))
        assertNull(m.diferenca)
    }

    @Test
    fun `ajuste - no limite exato ainda ajusta`() {
        val d = AjustePesoCompra.decidir(bd("10"), bd("10.2"), 2.0, 2.0, ajuste)
        assertEquals(0, d.diferenca!!.compareTo(bd("0.2")))
    }

    @Test
    fun `ajuste - sem limite naquele sentido vai pra divergencia`() {
        val maior = AjustePesoCompra.decidir(bd("10"), bd("15"), null, 2.0, ajuste)
        assertEquals(0, maior.enviar.compareTo(bd("15")))
        assertNull(maior.diferenca)
        val menor = AjustePesoCompra.decidir(bd("10"), bd("9.9"), 2.0, null, ajuste)
        assertEquals(0, menor.enviar.compareTo(bd("9.9")))
        assertNull(menor.diferenca)
    }

    @Test
    fun `ajuste desligado num sentido - dentro da tolerancia vai pra divergencia`() {
        val soSaida = ajuste.copy(ajusteEntrada = false)
        val maior = AjustePesoCompra.decidir(bd("10"), bd("10.15"), 2.0, 2.0, soSaida)
        assertEquals(0, maior.enviar.compareTo(bd("10.15")))
        assertEquals(AjustePesoCompra.Faixa.DIVERGENCIA, maior.faixa)
        val menor = AjustePesoCompra.decidir(bd("10"), bd("9.85"), 2.0, 2.0, soSaida)
        assertEquals(AjustePesoCompra.Faixa.AJUSTAR, menor.faixa)
    }

    @Test
    fun `peso igual, sem negociado ou zerado nao mexe`() {
        assertNull(AjustePesoCompra.decidir(bd("5"), bd("5.000"), 2.0, 2.0, ajuste).diferenca)
        assertEquals(0, AjustePesoCompra.decidir(null, bd("5"), 2.0, 2.0, ajuste).enviar.compareTo(bd("5")))
        assertEquals(0, AjustePesoCompra.decidir(bd("0"), bd("5"), 2.0, 2.0, ajuste).enviar.compareTo(bd("5")))
    }

    @Test
    fun `diferenca menor que a precisao da balanca nao gera ajuste`() {
        val d = AjustePesoCompra.decidir(bd("2.08333"), bd("2.0835"), 2.0, 2.0, ajuste)
        assertEquals(0, d.enviar.compareTo(bd("2.08333")))
        assertNull(d.diferenca)
    }

    // ─── Tolerância do produto x NUCCO ───────────────────────────────────

    @Test
    fun `tolerancia do produto tem prioridade, sentido a sentido`() {
        val prod = AjustePesoCompra.ToleranciaProduto(acimaPct = 1.0, abaixoPct = null)
        assertEquals(1.0 to 2.0, AjustePesoCompra.toleranciaEfetiva(2.0, 2.0, prod))
        assertEquals(2.0 to 3.0, AjustePesoCompra.toleranciaEfetiva(2.0, 3.0, null))
        assertEquals(2.0 to 0.5, AjustePesoCompra.toleranciaEfetiva(2.0, 3.0, AjustePesoCompra.ToleranciaProduto(0.0, 0.5)))
    }

    @Test
    fun `produto com 1 por cento manda divergencia o que o NUCCO de 2 aceitaria`() {
        val (acima, abaixo) = AjustePesoCompra.toleranciaEfetiva(2.0, 2.0, AjustePesoCompra.ToleranciaProduto(1.0, 1.0))
        val d = AjustePesoCompra.decidir(bd("10"), bd("10.15"), acima, abaixo, ajuste)
        assertEquals(0, d.enviar.compareTo(bd("10.15")))
        assertNull(d.diferenca)
    }

    // ─── Parâmetros ──────────────────────────────────────────────────────

    @Test
    fun `parametros - padrao desligado e valido`() {
        assertTrue(ParametrosAjustePesoCompra().validar().isEmpty())
    }

    @Test
    fun `parametros - ajuste ligado exige a TOP do sentido`() {
        assertTrue(ParametrosAjustePesoCompra(habilitado = true, ajusteEntrada = true).validar().isNotEmpty())
        assertTrue(ParametrosAjustePesoCompra(habilitado = true, ajusteSaida = true, topEntrada = 1495).validar().isNotEmpty())
        assertTrue(ajuste.validar().isEmpty())
        assertTrue(ajuste.copy(topSaida = 1495).validar().isNotEmpty())
        assertTrue(ajuste.copy(serieEntrada = "0", serieSaida = "0").validar().isEmpty())
        assertTrue(ajuste.copy(serieEntrada = "ABCD").validar().isNotEmpty())
    }

    // ─── Payloads ────────────────────────────────────────────────────────

    @Test
    fun `cabecalho com os dados da nota do fornecedor e a TOP de ajuste`() {
        val dados = AjustePesoCompra.DadosCab(
            codemp = 1, codParc = 3021, codTipVenda = 25, dhTipVenda = "03/10/2026 10:28:52", codNat = "20101",
            codCenCus = "40100", codTipOper = 1195, tipMov = "V", dhTipOper = "20/10/2026 17:08:46", dtNeg = "04/11/2026",
        )
        val p = AjustePesoCompra.payloadCabecalho(dados, "obs [X]")
        val cab = p["nota"]!!.jsonObject["cabecalho"]!!.jsonObject
        fun v(c: String) = cab[c]!!.jsonObject["$"]!!.jsonPrimitive.content
        assertEquals("1195", v("CODTIPOPER"))
        assertEquals("V", v("TIPMOV"))
        assertEquals("3021", v("CODPARC"))
        assertEquals("25", v("CODTIPVENDA"))
        assertEquals("20101", v("CODNAT"))
        assertEquals("40100", v("CODCENCUS"))
        assertEquals("04/11/2026 00:00:00", v("DTMOV"))
        assertEquals("04/11/2026 00:00:00", v("DTENTSAI"))
        assertNull(cab["SERIENOTA"])
        val comSerie = AjustePesoCompra.payloadCabecalho(dados.copy(serie = "0", hora = "14:35:10"), "obs")["nota"]!!.jsonObject["cabecalho"]!!.jsonObject
        assertEquals("0", comSerie["SERIENOTA"]!!.jsonObject["$"]!!.jsonPrimitive.content)
        assertEquals("04/11/2026 14:35:10", comSerie["DTENTSAI"]!!.jsonObject["$"]!!.jsonPrimitive.content)
        assertEquals("04/11/2026 00:00:00", v("DTNEG"))
        assertEquals("", v("NUNOTA"))
        assertTrue(v("OBSERVACAO").contains("[X]"))
        assertTrue((p["clientEventList"]!!.jsonObject["clientEvent"] as JsonArray).isNotEmpty())
    }

    @Test
    fun `itens com quantidade da balanca, valor da nota e lote`() {
        val item = AjustePesoCompra.ItemAjuste(2592, "L1 ", bd("0.250"), bd("12.708"))
        val preco = AjustePesoCompra.PrecoItem(bd("38.4500"), "KG", 101)
        val p = AjustePesoCompra.payloadItens(79759, 1, listOf(item to preco))
        val nota = p["nota"]!!.jsonObject
        assertEquals("79759", nota["NUNOTA"]!!.jsonPrimitive.content)
        val i = nota["itens"]!!.jsonObject["item"]!!.jsonArray.single().jsonObject
        fun v(c: String) = i[c]!!.jsonObject["$"]!!.jsonPrimitive.content
        assertEquals("2592", v("CODPROD"))
        assertEquals("0.250", v("QTDNEG"))
        assertEquals("38.4500", v("VLRUNIT"))
        assertEquals("9.61", v("VLRTOT"))
        assertEquals("KG", v("CODVOL"))
        assertEquals("101", v("CODLOCALORIG"))
        assertEquals("L1", v("CONTROLE"))
    }

    @Test
    fun `quantidade arredondada pelas casas decimais do produto`() {
        assertEquals("0.2500", AjustePesoCompra.quantidadeAjuste(bd("0.25"), 4).toPlainString())
        assertEquals("0.208", AjustePesoCompra.quantidadeAjuste(bd("-0.208"), null).toPlainString())
        assertEquals(0, AjustePesoCompra.quantidadeAjuste(bd("0.004"), 2).signum())
    }

    @Test
    fun `vinculo com a nota de origem na TGFVAR`() {
        val p = AjustePesoCompra.payloadVinculo(79760, 1, 71091, 3, "L")
        val ds = p["dataSet"]!!.jsonObject
        assertEquals("CompraVendavariosPedido", ds["rootEntity"]!!.jsonPrimitive.content)
        val f = ds["dataRow"]!!.jsonObject["localFields"]!!.jsonObject
        fun v(c: String) = f[c]!!.jsonObject["$"]!!.jsonPrimitive.content
        assertEquals("79760", v("NUNOTA"))
        assertEquals("71091", v("NUNOTAORIG"))
        assertEquals("3", v("SEQUENCIAORIG"))
    }

    @Test
    fun `numero da nota criada vem em pk NUNOTA`() {
        val r = Json.parseToJsonElement("""{"pk":{"NUNOTA":{"$":"79760"}}}""").jsonObject
        assertEquals(79760, AjustePesoCompra.nunotaDaResposta(r))
        assertNull(AjustePesoCompra.nunotaDaResposta(Json.parseToJsonElement("{}").jsonObject))
    }

    @Test
    fun `marcador identifica sessao e sentido`() {
        val id = UUID.fromString("00000000-0000-0000-0000-000000000001")
        assertEquals("[TO-AJPESO 00000000-0000-0000-0000-000000000001 S]", AjustePesoCompra.marcador(id, AjustePesoCompra.SAIDA))
    }
}
