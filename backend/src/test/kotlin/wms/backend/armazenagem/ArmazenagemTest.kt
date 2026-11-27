package wms.backend.armazenagem

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Contrato com o addon TOR (EnderecamentoSP.tarefas/iniciar/armazenar) — etapa 3b, 27/11/2026. */
class ArmazenagemTest {

    @Test
    fun `le a tarefa do addon com numeros em texto e barras separadas por virgula`() {
        val o = Json.parseToJsonElement(
            """{"NUTAR":"12","SITUACAO":"EX","NUNOTA":73686,"NUMNOTA":"5501","NOMEPARC":"FRIGO X","CODEMP":"1","CODLOCAL":"101",
               "CODPROD":"10","DESCRPROD":"LINGUICA","CONTROLE":" ","CODVOL":"KG","QTD":"1100.5","QTDFEITA":"100",
               "ORIGEM":"DOCA-1-101","SUGERIDO":"A-01-1","TIPOSUGERIDO":"PULMAO","BARRAS":"789, 2000010"}""",
        ).jsonObject
        val t = Armazenagem.tarefa(o)
        assertEquals(12, t.nutar)
        assertEquals(73686, t.nunota)
        assertEquals(1, t.codemp)
        assertEquals(1100.5, t.qtd)
        assertEquals(100.0, t.qtdFeita)
        assertNull(t.controle)
        assertEquals("A-01-1", t.sugerido)
        assertEquals(listOf("789", "2000010"), t.barras)
    }

    @Test
    fun `lista de um item pode vir como objeto`() {
        val um = Json.parseToJsonElement("""{"NUTAR":"1","CODPROD":"10"}""")
        assertEquals(1, Armazenagem.objetos(um).size)
        assertEquals(2, Armazenagem.objetos(Json.parseToJsonElement("""[{"NUTAR":"1"},{"NUTAR":"2"}]""")).size)
        assertEquals(0, Armazenagem.objetos(null).size)
    }
}

class ArmazenagemComprasTest {
    private fun t(nutar: String, nunota: String, sit: String, feita: String = "0") =
        mapOf("NUTAR" to nutar, "NUNOTA" to nunota, "NUMNOTA" to "999990", "CODPROD" to "10", "DESCRPROD" to "X",
            "QTD" to "100", "QTDFEITA" to feita, "SITUACAO" to sit)

    @Test
    fun `agrupa por compra e calcula a situacao`() {
        val r = ArmazenagemCompras.montar(
            listOf(t("1", "100", "CO", "100"), t("2", "100", "AB"), t("3", "200", "AB"), t("4", "300", "CO", "100")),
            listOf(mapOf("NUNOTA" to "400", "ENDSIT" to "ER", "ENDERRO" to "falhou"), mapOf("NUNOTA" to "500"), mapOf("NUNOTA" to "100")),
        )
        assertEquals(listOf(400L, 100L, 200L, 500L, 300L), r.map { it.nunota })
        assertEquals(listOf("erro", "guardando", "na_doca", "aguardando", "guardado"), r.map { it.situacao })
        assertEquals("falhou", r[0].erro)
        assertEquals(2, r[1].itens.size)
    }
}
