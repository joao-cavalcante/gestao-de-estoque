package wms.backend.liberacaocorte

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.selectAll
import wms.backend.separacao.SeparacaoRepository
import wms.backend.tarefas.StatusOperacional
import wms.backend.tarefas.TarefasTable
import wms.backend.tenancy.TenantTx
import java.util.UUID

object LiberacaoCorteRepository {

    /**
     * Conferências travadas aguardando liberação de corte — leitura LOCAL de
     * app.tarefas (status_operacional = 'aguardando_corte', escrito pelo sync
     * quando TGFCON2.STATUS='C', ver StatusOperacional.MAPA_STATUS_SANKHYA).
     * O NUCONF vem da sessão local mais recente da nota.
     */
    fun listarAguardandoCorte(tenantId: UUID): List<ConferenciaAguardandoCorteDto> = TenantTx.run(tenantId) {
        TarefasTable.selectAll()
            .where {
                (TarefasTable.tenantId eq tenantId) and
                    (TarefasTable.statusOperacional eq StatusOperacional.AGUARDANDO_CORTE.codigo)
            }
            .orderBy(TarefasTable.sankhyaAtualizadoEm to SortOrder.DESC)
            .map { row ->
                val nunota = row[TarefasTable.nunota].toLong()
                val dados = runCatching { Json.parseToJsonElement(row[TarefasTable.dados]) as JsonObject }.getOrNull()
                ConferenciaAguardandoCorteDto(
                    nunota = nunota,
                    numeroNota = dados?.get("NUMNOTA")?.jsonPrimitive?.contentOrNull?.toLongOrNull(),
                    nomeParceiro = dados?.get("Parceiro.NOMEPARC")?.jsonPrimitive?.contentOrNull,
                    descricaoTipoOperacao = dados?.get("TipoOperacao.DESCROPER")?.jsonPrimitive?.contentOrNull,
                    dataMovimento = dados?.get("DTNEG")?.jsonPrimitive?.contentOrNull,
                    nuconf = SeparacaoRepository.buscarNuconfPorNota(tenantId, nunota),
                )
            }
    }
}
