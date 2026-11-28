package wms.backend.recebimentodoca

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RecebimentoFiscalTest {
    @Test
    fun `corpo da manifestacao`() {
        val c = RecebimentoFiscal.corpoManifestacao("3".repeat(44), "210240", "1", 73713, "Carga com temperatura fora do limite").toString()
        assertTrue(c.startsWith("{\"params\":{"))
        assertTrue(c.contains("\"acao\":\"210240\""))
        assertTrue(c.contains("\"nuNota\":\"73713\""))
        assertTrue(c.contains("\"justificativa\":"))
    }

    @Test
    fun `retorno com mensagem e sem mensagem`() {
        val aviso = Json.parseToJsonElement("""{"mensagem":{"$":"Registro só pode ser manifestado como 'Operação confirmada' se a nota estiver confirmada"}}""") as JsonObject
        assertTrue(RecebimentoFiscal.mensagemRetorno(aviso)!!.startsWith("Registro só pode"))
        assertNull(RecebimentoFiscal.mensagemRetorno(Json.parseToJsonElement("{}") as JsonObject))
        assertEquals("x", RecebimentoFiscal.mensagemRetorno(Json.parseToJsonElement("""{"body":{"mensagem":"x"}}""") as JsonObject))
    }
}
