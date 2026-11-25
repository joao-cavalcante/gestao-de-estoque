package wms.backend.entrada

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Contrato com o addon TOR (RecebimentoSP.aprovar) — etapa 2, 23/11/2026. */
class EntradaAprovacaoTest {

    @Test
    fun `corpo leva compra, conferencia e o aprovador como texto em request`() {
        val r = EntradaAprovacao.corpo(88093, 5501, "215.0")["request"]!!.jsonObject
        assertEquals("88093", r["NUNOTA"]!!.jsonPrimitive.content)
        assertEquals("5501", r["NUCONF"]!!.jsonPrimitive.content)
        assertEquals("215", r["CODUSU"]!!.jsonPrimitive.content)
    }

    @Test
    fun `resposta aprovada dentro de body`() {
        val json = Json.parseToJsonElement("""{"body":{"CONTRATO":"1","SITUACAO":"APROVADO","JAEXISTIA":"S","NUREC":7,"LIBERACOES":[]}}""").jsonObject
        val r = EntradaAprovacao.lerResposta(json)
        assertEquals("APROVADO", r.situacao)
        assertTrue(r.jaExistia)
        assertTrue(r.liberacoes.isEmpty())
    }

    @Test
    fun `liberacao pendente traz a lista`() {
        val json = Json.parseToJsonElement("""{"body":{"SITUACAO":"LIBERACAO_PENDENTE","JAEXISTIA":"N","LIBERACOES":["Evento 25: limite"]}}""").jsonObject
        val r = EntradaAprovacao.lerResposta(json)
        assertEquals("LIBERACAO_PENDENTE", r.situacao)
        assertFalse(r.jaExistia)
        assertEquals(listOf("Evento 25: limite"), r.liberacoes)
    }

    @Test
    fun `resposta sem situacao e erro`() {
        assertFailsWith<EntradaAprovacao.AprovacaoException> {
            EntradaAprovacao.lerResposta(Json.parseToJsonElement("""{"body":{}}""").jsonObject)
        }
    }
}
