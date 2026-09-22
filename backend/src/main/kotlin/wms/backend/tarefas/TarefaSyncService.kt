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
        // TGFCAB.ORDEMCARGA — número da ordem/onda de carga; base do filtro
        // "Ordem de Carga" da fila (igual ao fila-de-conferencia legado).
        "ORDEMCARGA",
        // TGFCAB.AD_TURNOENTREGA — período pro card da fila: 1 Diurno | 2 Noturno | 9 Qualquer.
        "AD_TURNOENTREGA",
        // Ponteiro pra conferência atual (NULL = nunca teve ou foi excluída —
        // fica preenchido permanentemente uma vez setado, mesmo após
        // finalização) + liberação do vendedor. Já eram usados dentro do
        // CRITERIO_BASE abaixo, mas nunca eram lidos pro resultado — agora
        // ancoram a resolução de status em StatusOperacional/reconciliarLoteTx
        // em vez de inferir "a conferência mais recente da nota".
        "NUCONFATUAL", "LIBCONF",
    )

    // Status por NUCONF exato (ver StatusOperacional/reconciliarLoteTx) — só
    // consultado pras notas com NUCONFATUAL preenchido; TGFCON2.STATUS é a
    // fonte de verdade real confirmada com o usuário (não o campo calculado
    // STATUSCONFERENCIA de CabecalhoNota).
    private val FIELDS_CONF = listOf("NUCONF", "STATUS")

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

        val nuconfsAtuais = rows.mapNotNull { it["NUCONFATUAL"]?.toIntOrNull() }.distinct()
        val statusPorNuconf = buscarStatusPorNuconf(tenantSlug, nuconfsAtuais)

        val linhas = rows.mapNotNull { r ->
            val nunota = r["NUNOTA"]?.toLongOrNull() ?: return@mapNotNull null
            val nuconfAtual = r["NUCONFATUAL"]?.toIntOrNull()
            val libconf = r["LIBCONF"]
            val statusTgfcon2Raw = nuconfAtual?.let { statusPorNuconf[it] }
            val dadosJson = buildJsonObject {
                FIELDS.forEach { campo -> put(campo, r[campo]) }
            }.toString()
            LinhaSankhya(nunota, nuconfAtual, libconf, statusTgfcon2Raw, dadosJson)
        }

        val notasResetadas = withContext(Dispatchers.IO) {
            // 1 transação por tenant por ciclo, reconciliação em LOTE (1
            // SELECT + no máximo 2 lotes de escrita, não ~2 idas ao banco
            // por nota) — statement_timeout mais folgado que o da API.
            TenantTx.run(tenantId, statementTimeoutMs = 30_000) {
                TarefasRepository.reconciliarLoteTx(tenantId, linhas)
            }
        }

        // Nota que voltou pra aguardando (conferência excluída ou reaberta pra
        // recontagem no Sankhya): a sessão de separação local ficou obsoleta
        // (etapas concluídas, itens conferidos) — cancela pra o próximo
        // `iniciar` criar uma limpa.
        if (notasResetadas.isNotEmpty()) {
            withContext(Dispatchers.IO) {
                wms.backend.separacao.SeparacaoRepository.cancelarSessoesAtivasPorNotas(tenantId, notasResetadas)
            }
        }

        withContext(Dispatchers.IO) {
            revalidarNaoVistasNoCiclo(tenantSlug, tenantId, vistasNesteCiclo = linhas.map { it.nunota }.toSet())
        }
    }

    /**
     * Preventivo pra "nota saiu do CRITERIO_BASE e a tarefa local fica presa pra
     * sempre no último status visto" — o CRITERIO_BASE só devolve o que "precisa
     * conferência"; uma nota some da resposta por DOIS motivos bem diferentes, e
     * sem isto os dois travavam a tarefa local igual:
     *
     * 1. Excluída FISICAMENTE no Sankhya (causa raiz confirmada de uma limpeza em
     *    massa de pedidos) — não existe mais em CabecalhoNota de jeito nenhum.
     * 2. Conferência FINALIZADA/liberada direto no Sankhya (corte, liberação de
     *    corte, etc.) ENTRE dois ciclos de sync — a nota ainda existe, só que
     *    TGFCON2.STATUS virou F/D antes do sync ver esse estado, então some da
     *    fila sem o reconciliarLoteTx normal nunca ter processado essa transição
     *    (caso real confirmado: nunota 57718, ficou preso em 'aguardando_corte'
     *    mesmo já liberado/finalizado no Sankhya).
     *
     * Só investiga tarefas locais ATIVAS (não-final, ver
     * TarefasRepository.listarNunotasAtivasTx) que não vieram neste ciclo. Pra
     * cada uma, busca o estado real em CabecalhoNota SEM CRITERIO_BASE (com os
     * MESMOS FIELDS do ciclo normal, pra não gravar um `dados` incompleto) — as
     * que ainda existem são REVALIDADAS pela mesma reconciliarLoteTx do ciclo
     * principal (idêntica regra de transição/auditoria, sem duplicar lógica); as
     * que não existem mais são removidas.
     */
    private suspend fun revalidarNaoVistasNoCiclo(tenantSlug: String, tenantId: UUID, vistasNesteCiclo: Set<Long>) {
        val ativasLocais = TenantTx.run(tenantId) { TarefasRepository.listarNunotasAtivasTx(tenantId) }
        val candidatas = ativasLocais.filterNot { it in vistasNesteCiclo }
        if (candidatas.isEmpty()) return

        val existentes = mutableSetOf<Long>()
        val linhasRevalidadas = mutableListOf<LinhaSankhya>()

        candidatas.chunked(200).forEach { lote ->
            val raw = SankhyaLoadRecordsClient.loadRecords(
                tenantSlug,
                LoadRecordsRequest(
                    entityName = "CabecalhoNota",
                    fields = FIELDS,
                    criteriaExpression = "NUNOTA IN (${lote.joinToString(",")})",
                ),
            )
            val rows = SankhyaLoadRecordsClient.parseRows(raw, FIELDS)
            val nuconfsLote = rows.mapNotNull { it["NUCONFATUAL"]?.toIntOrNull() }.distinct()
            val statusPorNuconfLote = buscarStatusPorNuconf(tenantSlug, nuconfsLote)

            rows.forEach { r ->
                val nunota = r["NUNOTA"]?.toLongOrNull() ?: return@forEach
                existentes += nunota
                val nuconfAtual = r["NUCONFATUAL"]?.toIntOrNull()
                val libconf = r["LIBCONF"]
                val statusTgfcon2Raw = nuconfAtual?.let { statusPorNuconfLote[it] }
                val dadosJson = buildJsonObject { FIELDS.forEach { campo -> put(campo, r[campo]) } }.toString()
                linhasRevalidadas += LinhaSankhya(nunota, nuconfAtual, libconf, statusTgfcon2Raw, dadosJson)
            }
        }

        val inexistentes = candidatas.filterNot { it in existentes }
        if (inexistentes.isNotEmpty()) {
            TenantTx.run(tenantId) { TarefasRepository.removerInexistentesNoSankhyaTx(tenantId, inexistentes) }
        }

        if (linhasRevalidadas.isNotEmpty()) {
            val notasResetadas = TenantTx.run(tenantId, statementTimeoutMs = 30_000) {
                TarefasRepository.reconciliarLoteTx(tenantId, linhasRevalidadas)
            }
            if (notasResetadas.isNotEmpty()) {
                wms.backend.separacao.SeparacaoRepository.cancelarSessoesAtivasPorNotas(tenantId, notasResetadas)
            }
        }
    }

    /**
     * TGFCON2.STATUS por NUCONF exato — não mais "a mais recente por
     * NUNOTAORIG": cada nota já sabe seu NUCONF ativo via TGFCAB.NUCONFATUAL
     * (lido em FIELDS), então a busca é uma correspondência direta, sem
     * ambiguidade entre a conferência atual e uma anterior da mesma nota.
     * NUCONF sem linha correspondente fica de fora do mapa (não deveria
     * acontecer com NUCONFATUAL preenchido — anomalia defensiva, tratada
     * como "" por quem chama).
     */
    private suspend fun buscarStatusPorNuconf(tenantSlug: String, nuconfs: List<Int>): Map<Int, String> {
        if (nuconfs.isEmpty()) return emptyMap()

        val raw = SankhyaLoadRecordsClient.loadRecords(
            tenantSlug,
            LoadRecordsRequest(
                entityName = "CabecalhoConferencia",
                fields = FIELDS_CONF,
                criteriaExpression = "NUCONF IN (${nuconfs.joinToString(",")})",
            ),
        )
        val rows = SankhyaLoadRecordsClient.parseRows(raw, FIELDS_CONF)

        return rows
            .mapNotNull { r ->
                val nuconf = r["NUCONF"]?.toIntOrNull() ?: return@mapNotNull null
                nuconf to (r["STATUS"] ?: "")
            }
            .toMap()
    }
}
