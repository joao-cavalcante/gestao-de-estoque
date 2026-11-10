package wms.backend.separacao

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DocumentosDoCortarTest {
    private fun r(s: String) = Json.parseToJsonElement(s).jsonObject

    @Test
    fun `sem notas nao gerou documento`() {
        assertFalse(SeparacaoService.documentosDoCortar(r("""{"status":"1","responseBody":{}}""")))
        assertFalse(SeparacaoService.documentosDoCortar(r("""{"status":"1","responseBody":{"notas":null}}""")))
        assertFalse(SeparacaoService.documentosDoCortar(r("""{"status":"1","responseBody":{"notas":{"nota":[]}}}""")))
        assertFalse(SeparacaoService.documentosDoCortar(null))
    }

    @Test
    fun `com nota de devolucao gerou documento`() {
        assertTrue(SeparacaoService.documentosDoCortar(r("""{"status":"1","responseBody":{"notas":{"nota":{"$":"83745"}}}}""")))
        assertTrue(SeparacaoService.documentosDoCortar(r("""{"status":"1","responseBody":{"notas":{"nota":[{"$":"1"},{"$":"2"}]}}}""")))
    }
}
