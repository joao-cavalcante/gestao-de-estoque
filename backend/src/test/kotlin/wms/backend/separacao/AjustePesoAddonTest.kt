package wms.backend.separacao

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.math.BigDecimal
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Contrato com o addon TOR (AjustePesoSP.gerar) — 19/11/2026. */
class AjustePesoAddonTest {

    @Test
    fun `corpo leva compra, conferencia e as diferencas dos dois sentidos`() {
        val corpo = AjustePesoCompra.payloadAddon(
            88093, 5501,
            listOf(
                AjustePesoCompra.ItemAddon(3243, " ", BigDecimal("7463.14"), BigDecimal("7437.84"), BigDecimal("-25.30")),
                AjustePesoCompra.ItemAddon(100, "L01 ", BigDecimal("10"), BigDecimal("10.25"), BigDecimal("0.25")),
            ),
        )["request"]!!.jsonObject // o bean do Addon Studio lê requestBody.request
        assertEquals("88093", corpo["NUNOTA"]!!.jsonPrimitive.content)
        assertEquals("5501", corpo["NUCONF"]!!.jsonPrimitive.content)
        assertTrue(corpo["NUNOTA"]!!.jsonPrimitive.isString, "número como texto (o Gson do addon vira Double)")
        val itens = corpo["ITENS"]!!.jsonArray
        assertEquals(2, itens.size)
        assertEquals("", itens[0].jsonObject["CONTROLE"]!!.jsonPrimitive.content)
        assertEquals("-25.300", itens[0].jsonObject["QTDDIF"]!!.jsonPrimitive.content)
        assertEquals("L01", itens[1].jsonObject["CONTROLE"]!!.jsonPrimitive.content)
    }

    @Test
    fun `resposta com lista de notas`() {
        val r = Json.parseToJsonElement(
            """{"CONTRATO":"1","NOTAS":[{"SENTIDO":"S","NUNOTAAJ":88686,"JAEXISTIA":"N"},{"SENTIDO":"E","NUNOTAAJ":"88687"}]}""",
        ).jsonObject
        assertEquals(mapOf("S" to 88686, "E" to 88687), AjustePesoCompra.notasDoAddon(r))
    }

    @Test
    fun `resposta embrulhada em body pelo bean do Addon Studio`() {
        val r = Json.parseToJsonElement(
            """{"body":{"CONTRATO":"1","NOTAS":[{"SENTIDO":"S","NUNOTAAJ":88686.0,"JAEXISTIA":"N"}]}}""",
        ).jsonObject
        assertEquals(mapOf("S" to 88686), AjustePesoCompra.notasDoAddon(r))
    }

    @Test
    fun `resposta com uma nota so no formato do gateway`() {
        val r = Json.parseToJsonElement("""{"NOTAS":{"SENTIDO":{"$":"S"},"NUNOTAAJ":{"$":"88686"}}}""").jsonObject
        assertEquals(mapOf("S" to 88686), AjustePesoCompra.notasDoAddon(r))
    }

    @Test
    fun `resposta sem notas`() {
        assertTrue(AjustePesoCompra.notasDoAddon(Json.parseToJsonElement("{}").jsonObject).isEmpty())
    }
}
