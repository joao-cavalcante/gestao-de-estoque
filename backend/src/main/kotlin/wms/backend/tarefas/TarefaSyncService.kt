package wms.backend.tarefas

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import wms.backend.erp.LoadRecordsRequest
import wms.backend.erp.SankhyaLoadRecordsClient
import wms.backend.tenancy.TenantTx
import java.time.Instant
import java.util.UUID

/**
 * Job de sincronização Sankhya -> base local. Roda a query real da tela
 * nativa "Fila de Conferência" (DatasetSP.loadRecords, mesmo contrato
 * confirmado ao vivo contra o Modial) e reconcilia cada linha via
 * TarefasRepository.reconciliarLinhaTx.
 *
 * Sem campo de auditoria de alteração (DHALTER não existe nesta entidade
 * do Sankhya — confirmado) para filtro incremental: cada ciclo reconsulta
 * o conjunto FILTRADO inteiro (o próprio CRITERIO_BASE já restringe a
 * "precisa conferência", um recorte pequeno — não é "a base inteira" da
 * tabela de notas). Idempotência vem do upsert por (tenant_id, nunota) no
 * repositório, não de um corte incremental por data.
 *
 * Reconciliação inteira roda numa ÚNICA transação por tenant por ciclo
 * (não uma por linha) — é o "bulkhead correto": no máximo 1 conexão do
 * pool compartilhada por tenant por ciclo, não 1 por nota.
 */
object TarefaSyncService {

    // TipoOperacao.NUCCO entra aqui (não só pra exibir a nota) porque é a ÚNICA
    // fonte confiável do vínculo TOP->NUCCO: a entidade "TipoOperacao" isolada via
    // CRUDServiceProvider (usada em wms.backend.tipooperacao) não lista todo TOP
    // realmente em uso — confirmado ao vivo que CODTIPOPER 1011 (CUBAGEM DE
    // PEDIDO), que aparece normalmente aqui, nunca aparece numa varredura
    // completa daquela entidade. O espelho de Tipos de Operação é derivado
    // DESTES dados (ver TipoOperacaoSyncService), não de uma consulta própria.
    private val FIELDS = listOf(
        "NUNOTA", "NUMNOTA", "DTNEG", "CODEMP", "Empresa.NOMEFANTASIA",
        "CODTIPOPER", "TipoOperacao.DESCROPER", "TipoOperacao.NUCCO", "CODVEND", "Vendedor.APELIDO",
        "STATUSNOTA", "TIPMOV", "CODPARC", "Parceiro.NOMEPARC",
    )

    // Status real da conferência: NÃO vem de um campo calculado em
    // CabecalhoNota (STATUSCONFERENCIA, que depende de NUCONFATUAL estar
    // vinculado — coisa que a SP de abertura não faz sozinha e nada mais no
    // fluxo garante) — vem direto de TGFCON2.STATUS, a fonte de verdade real
    // confirmada com o usuário. Mesma entidade/campo que buscarNumeroConferenciaAtiva
    // usa no projeto base.
    private val FIELDS_CONF = listOf("NUNOTAORIG", "NUCONF", "STATUS")

    // Critério real da tela nativa "Fila de Conferência" (via
    // FilaConferenciaCrudListener), capturado do app oficial — verbatim,
    // sem nenhum campo AD_.
    private const val CRITERIO_BASE = """( (   (TipoOperacao->ConfiguracaoConferencia->APRESFILASEMPRE = 'S'      OR EXISTS(           SELECT 1                   FROM TGFITE ITE           WHERE ITE.NUNOTA = TGFCAB.NUNOTA          AND (ITE.PENDENTE = 'S' OR TipoOperacao->ConfiguracaoConferencia->MOMENTOCONFERENCIA = 'C')           AND (EXISTS(                      SELECT 1 FROM TGFPRO PROD                                 WHERE (PROD.EXCLUIRCONF IS NULL OR PROD.EXCLUIRCONF = 'N')                                  AND PROD.CODPROD = ITE.CODPROD))                          )    ) AND (      (TipoOperacao->ConfiguracaoConferencia->MOMENTOCONFERENCIA = 'F' AND this.STATUSNOTA = 'L')       OR       (TipoOperacao->ConfiguracaoConferencia->MOMENTOCONFERENCIA = 'C' AND this.LIBCONF = 'S')      )  AND (      this.NUCONFATUAL IS NULL        OR (            TipoOperacao->ConfiguracaoConferencia->MOMENTOCONFERENCIA = 'F'            AND TipoOperacao->ConfiguracaoConferencia->MULTENTREGAS = 'S' AND this.LIBCONF = 'S'            )        OR NOT EXISTS(                    SELECT 1                    FROM TGFCON2 CON2                    WHERE CON2.NUCONF = this.NUCONFATUAL                      AND CON2.STATUS IN('F','D')                   )        OR 'S' = 'N'         ) ))"""

    /** Lança exceção em caso de falha — quem chama (SyncWorkerPool) decide o backoff. */
    suspend fun sincronizarTenant(tenantSlug: String, tenantId: UUID) {
        val raw = SankhyaLoadRecordsClient.loadRecords(
            tenantSlug,
            LoadRecordsRequest(
                entityName = "CabecalhoNota",
                fields = FIELDS,
                criteriaExpression = CRITERIO_BASE,
                orderByExpression = "NUNOTA DESC",
            ),
        )
        val rows = SankhyaLoadRecordsClient.parseRows(raw, FIELDS)
        val agora = Instant.now()

        val nunotas = rows.mapNotNull { it["NUNOTA"]?.toLongOrNull() }
        val statusPorNunota = buscarStatusConferenciaAtiva(tenantSlug, nunotas)

        val linhas = rows.mapNotNull { r ->
            val nunota = r["NUNOTA"]?.toLongOrNull() ?: return@mapNotNull null
            val statusRaw = statusPorNunota[nunota] ?: ""
            val dadosJson = buildJsonObject {
                FIELDS.forEach { campo -> put(campo, r[campo]) }
            }.toString()
            LinhaSankhya(nunota, statusRaw, dadosJson)
        }

        withContext(Dispatchers.IO) {
            // 1 transação por tenant por ciclo, reconciliação em LOTE (1
            // SELECT + no máximo 2 lotes de escrita, não ~2 idas ao banco
            // por nota) — statement_timeout mais folgado que o da API.
            TenantTx.run(tenantId, statementTimeoutMs = 30_000) {
                TarefasRepository.reconciliarLoteTx(tenantId, linhas)
            }
        }
    }

    /**
     * TGFCON2.STATUS por NUNOTAORIG — quando existem várias conferências pra
     * mesma nota ao longo do tempo (recontagem), pega a de maior NUCONF (a
     * mais recente) como a que manda. Nota sem nenhuma linha em TGFCON2 fica
     * de fora do mapa — quem chama trata isso como "" (aguardando).
     */
    private suspend fun buscarStatusConferenciaAtiva(tenantSlug: String, nunotas: List<Long>): Map<Long, String> {
        if (nunotas.isEmpty()) return emptyMap()

        val raw = SankhyaLoadRecordsClient.loadRecords(
            tenantSlug,
            LoadRecordsRequest(
                entityName = "CabecalhoConferencia",
                fields = FIELDS_CONF,
                criteriaExpression = "NUNOTAORIG IN (${nunotas.joinToString(",")})",
            ),
        )
        val rows = SankhyaLoadRecordsClient.parseRows(raw, FIELDS_CONF)

        return rows
            .mapNotNull { r ->
                val nunotaOrig = r["NUNOTAORIG"]?.toLongOrNull() ?: return@mapNotNull null
                val nuconf = r["NUCONF"]?.toIntOrNull() ?: return@mapNotNull null
                Triple(nunotaOrig, nuconf, r["STATUS"] ?: "")
            }
            .groupBy { it.first }
            .mapValues { (_, linhas) -> linhas.maxBy { it.second }.third }
    }
}
