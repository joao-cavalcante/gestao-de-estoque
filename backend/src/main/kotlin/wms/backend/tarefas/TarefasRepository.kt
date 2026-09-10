package wms.backend.tarefas

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.contentOrNull
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.inList
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.batchInsert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.update
import wms.backend.tenancy.TenantTx
import java.time.Instant
import java.util.UUID

/** Uma linha crua vinda do loadRecords, pronta pra reconciliar. */
data class LinhaSankhya(val nunota: Long, val statusSankhyaRaw: String, val dadosJson: String)

private data class InsercaoPendente(
    val nunotaInt: Int,
    val statusSankhyaRaw: String,
    val statusOperacionalNovo: String,
    val dadosJson: String,
)

private data class AtualizacaoPendente(
    val nunotaInt: Int,
    val statusSankhyaRaw: String,
    val statusOperacionalNovo: String,
    val dadosJson: String,
    val limparExecucao: Boolean,
)

private data class AuditoriaPendente(
    val nunotaInt: Int,
    val statusAnterior: String,
    val statusNovo: String,
    val motivo: String,
)

/**
 * Toda operação aqui passa por `TenantTx.run` — resolve a conexão certa
 * (compartilhada ou dedicada), seta `SET LOCAL app.tenant_id` (RLS) e
 * `SET LOCAL statement_timeout` (contenção de recurso). Funções `*Tx`
 * assumem que JÁ estão dentro de uma transação aberta por quem chama
 * (usadas pelo lote de sync — uma transação só por tenant por ciclo).
 */
object TarefasRepository {

    /**
     * Reconciliação em LOTE — 1 SELECT pra buscar todas as linhas
     * existentes do ciclo (não 1 por nota), decisão em memória (mesma
     * regra de sempre, ver StatusOperacional.kt), e gravação em lote
     * (`batchInsert` pras novas, updates individuais só pras que de fato
     * mudaram — não mais todas as N notas a cada ciclo).
     *
     * Isto é puramente uma otimização de I/O: pra 66 notas, o padrão
     * antigo (1 SELECT + 1 INSERT/UPDATE por nota) fazia ~130 idas ao
     * banco por tenant por ciclo; isto faz 1 SELECT + no máximo 2 lotes de
     * escrita — a REGRA DE NEGÓCIO (idempotência, pendente_write_back,
     * tabela de transição, auditoria condicional) é idêntica, só decidida
     * em memória em vez de round-trip por linha.
     */
    /**
     * Retorna as NUNOTAs deste ciclo cujo status operacional FINAL é
     * 'aguardando' ou 'cancelado' — quem chama usa pra invalidar a sessão de
     * separação local (uma nota nesses estados não deve ter conferência viva;
     * cobre conferência excluída/reaberta no Sankhya, com ou sem transição).
     */
    fun reconciliarLoteTx(tenantId: UUID, linhas: List<LinhaSankhya>): List<Long> {
        if (linhas.isEmpty()) return emptyList()
        val nunotasInt = linhas.map { it.nunota.toInt() }

        val existentesPorNunota = TarefasTable.selectAll()
            .where { (TarefasTable.tenantId eq tenantId) and (TarefasTable.nunota inList nunotasInt) }
            .associateBy { it[TarefasTable.nunota] }

        val agora = Instant.now()
        val paraInserir = mutableListOf<InsercaoPendente>()
        val paraAtualizar = mutableListOf<AtualizacaoPendente>()
        val paraAuditar = mutableListOf<AuditoriaPendente>()

        for (linha in linhas) {
            val nunotaInt = linha.nunota.toInt()
            val existente = existentesPorNunota[nunotaInt]

            if (existente == null) {
                val novoStatus = mapearStatusSankhya(linha.statusSankhyaRaw)
                paraInserir += InsercaoPendente(nunotaInt, linha.statusSankhyaRaw, novoStatus.codigo, linha.dadosJson)
                continue
            }

            // Nenhuma linha ativa encontrada em TGFCON2 (raw vazio) para uma
            // tarefa que JÁ estava com conferência em andamento/concluída
            // localmente NÃO significa "nunca teve conferência" — significa
            // que ela foi excluída FISICAMENTE do TGFCON2, sem deixar
            // STATUS='D' pra trás (diferente de um cancelamento suave, que
            // preserva a linha com STATUS='D'). Trata como o mesmo 'D' que o
            // Sankhya usa pro cancelamento suave, pra cair no mesmo caminho
            // semântico de CANCELADO — não regredir pra AGUARDANDO como se a
            // conferência nunca tivesse existido, e manter a auditoria
            // (motivoCancelamento) honesta sobre o que de fato aconteceu.
            val statusOperacionalAnterior = existente[TarefasTable.statusOperacional]
            val statusSankhyaEfetivo = if (
                linha.statusSankhyaRaw.isBlank() &&
                statusOperacionalAnterior in setOf(StatusOperacional.ANDAMENTO.codigo, StatusOperacional.CONCLUIDO.codigo)
            ) {
                "D"
            } else {
                linha.statusSankhyaRaw
            }
            val novoStatus = mapearStatusSankhya(statusSankhyaEfetivo)

            val statusSankhyaAnterior = existente[TarefasTable.statusSankhya]
            val dadosAnteriores = existente[TarefasTable.dados]

            // Comparação nos JSON já PARSEADOS (JsonObject == compara como
            // Map, ignora ordem de chave) — jsonb do Postgres não garante
            // preservar ordem de chave ao reler.
            val dadosSankhyaMudaram = statusSankhyaAnterior != statusSankhyaEfetivo ||
                runCatching {
                    Json.parseToJsonElement(dadosAnteriores) != Json.parseToJsonElement(linha.dadosJson)
                }.getOrDefault(true)

            if (existente[TarefasTable.pendenteWriteBack]) {
                // Write-back local em voo: NÃO mexe no status operacional
                // (mantém o valor atual) — só refresca dados/statusSankhya
                // se algo mudou de verdade do lado do Sankhya.
                if (dadosSankhyaMudaram) {
                    paraAtualizar += AtualizacaoPendente(
                        nunotaInt = nunotaInt,
                        statusSankhyaRaw = statusSankhyaEfetivo,
                        statusOperacionalNovo = existente[TarefasTable.statusOperacional],
                        dadosJson = linha.dadosJson,
                        limparExecucao = false,
                    )
                }
                continue
            }

            val statusAtualLocal = StatusOperacional.porCodigo(existente[TarefasTable.statusOperacional])
            val transicao = TABELA_TRANSICAO.getValue(statusAtualLocal to novoStatus)

            // Idempotência real: só escreve se o Sankhya trouxe algo novo OU
            // se o status local ainda não reflete o que a reconciliação
            // exige (cobre "concluí local, mas o Sankhya já dizia aguardando
            // desde antes").
            if (!dadosSankhyaMudaram && statusAtualLocal == transicao.resultado) {
                continue
            }

            paraAtualizar += AtualizacaoPendente(
                nunotaInt = nunotaInt,
                statusSankhyaRaw = statusSankhyaEfetivo,
                statusOperacionalNovo = transicao.resultado.codigo,
                dadosJson = linha.dadosJson,
                limparExecucao = transicao.limparExecucao,
            )

            if (statusAtualLocal != transicao.resultado) {
                paraAuditar += AuditoriaPendente(
                    nunotaInt = nunotaInt,
                    statusAnterior = statusAtualLocal.codigo,
                    statusNovo = transicao.resultado.codigo,
                    motivo = transicao.motivo(statusAtualLocal, novoStatus),
                )
            }
        }

        if (paraInserir.isNotEmpty()) {
            TarefasTable.batchInsert(paraInserir) { item ->
                this[TarefasTable.id] = UUID.randomUUID()
                this[TarefasTable.tenantId] = tenantId
                this[TarefasTable.nunota] = item.nunotaInt
                this[TarefasTable.tipo] = "conferencia"
                this[TarefasTable.statusSankhya] = item.statusSankhyaRaw
                this[TarefasTable.statusOperacional] = item.statusOperacionalNovo
                this[TarefasTable.dados] = item.dadosJson
                this[TarefasTable.sankhyaAtualizadoEm] = agora
                this[TarefasTable.localAtualizadoEm] = agora
                this[TarefasTable.pendenteWriteBack] = false
            }
        }

        // Updates continuam parametrizados e individuais (Exposed não tem
        // um batchUpdate heterogêneo seguro sem SQL cru) — mas só pras
        // linhas que REALMENTE mudaram, não mais as N inteiras por ciclo.
        paraAtualizar.forEach { item ->
            TarefasTable.update({ (TarefasTable.tenantId eq tenantId) and (TarefasTable.nunota eq item.nunotaInt) }) {
                it[statusSankhya] = item.statusSankhyaRaw
                it[statusOperacional] = item.statusOperacionalNovo
                it[dados] = item.dadosJson
                it[sankhyaAtualizadoEm] = agora
                if (item.limparExecucao) {
                    it[operadorExecucao] = null
                    it[iniciadoEm] = null
                    it[concluidoEm] = null
                }
            }
        }

        if (paraAuditar.isNotEmpty()) {
            TarefasAuditoriaTable.batchInsert(paraAuditar) { item ->
                this[TarefasAuditoriaTable.id] = UUID.randomUUID()
                this[TarefasAuditoriaTable.tenantId] = tenantId
                this[TarefasAuditoriaTable.nunota] = item.nunotaInt
                this[TarefasAuditoriaTable.statusAnterior] = item.statusAnterior
                this[TarefasAuditoriaTable.statusNovo] = item.statusNovo
                this[TarefasAuditoriaTable.origem] = "sync_sankhya"
                this[TarefasAuditoriaTable.motivo] = item.motivo
                this[TarefasAuditoriaTable.criadoEm] = agora
            }
        }

        // Estado FINAL desta transação (inserts + updates + as que não mudaram) —
        // re-lê pra pegar também as notas que já estavam 'aguardando' de ciclos
        // anteriores (sessão obsoleta que nunca foi limpa).
        return TarefasTable
            .selectAll()
            .where {
                (TarefasTable.tenantId eq tenantId) and
                    (TarefasTable.nunota inList nunotasInt) and
                    (TarefasTable.statusOperacional inList listOf("aguardando", "cancelado"))
            }
            .map { it[TarefasTable.nunota].toLong() }
    }

    fun listar(tenantId: UUID): List<TarefaApiDto> = TenantTx.run(tenantId) {
        TarefasTable.selectAll()
            .where { TarefasTable.tenantId eq tenantId }
            .orderBy(TarefasTable.sankhyaAtualizadoEm to SortOrder.DESC)
            .map { row ->
                val dados = runCatching { Json.parseToJsonElement(row[TarefasTable.dados]) as JsonObject }
                    .getOrNull()
                val sankhyaAtualizadoEm = row[TarefasTable.sankhyaAtualizadoEm]
                TarefaApiDto(
                    nunota = row[TarefasTable.nunota].toLong(),
                    tipo = row[TarefasTable.tipo],
                    statusOperacional = row[TarefasTable.statusOperacional],
                    statusSankhya = row[TarefasTable.statusSankhya],
                    numeroNota = dados?.get("NUMNOTA")?.jsonPrimitive?.contentOrNull?.toLongOrNull(),
                    codigoParceiro = dados?.get("CODPARC")?.jsonPrimitive?.contentOrNull,
                    nomeParceiro = dados?.get("Parceiro.NOMEPARC")?.jsonPrimitive?.contentOrNull,
                    codigoVendedor = dados?.get("CODVEND")?.jsonPrimitive?.contentOrNull,
                    apelidoVendedor = dados?.get("Vendedor.APELIDO")?.jsonPrimitive?.contentOrNull,
                    dataMovimento = dados?.get("DTNEG")?.jsonPrimitive?.contentOrNull,
                    codigoTipoOperacao = dados?.get("CODTIPOPER")?.jsonPrimitive?.contentOrNull,
                    descricaoTipoOperacao = dados?.get("TipoOperacao.DESCROPER")?.jsonPrimitive?.contentOrNull,
                    ordemCarga = dados?.get("ORDEMCARGA")?.jsonPrimitive?.contentOrNull
                        ?.trim()?.takeIf { it.isNotEmpty() }
                        // vem como "1234" ou "1234.0" do loadRecords — normaliza pra Long
                        ?.let { it.toLongOrNull() ?: it.toDoubleOrNull()?.toLong() },
                    segundosDesdeSync = Instant.now().epochSecond - sankhyaAtualizadoEm.epochSecond,
                    pendenteWriteBack = row[TarefasTable.pendenteWriteBack],
                )
            }
    }

    /** Conclusão local imediata — a escrita real no Sankhya é assíncrona (ver WriteBackQueue). */
    fun concluirLocal(tenantId: UUID, nunota: Long, operador: String): Boolean = TenantTx.run(tenantId) {
        val agora = Instant.now()
        val linhas = TarefasTable.update({
            (TarefasTable.tenantId eq tenantId) and (TarefasTable.nunota eq nunota.toInt())
        }) {
            it[statusOperacional] = StatusOperacional.CONCLUIDO.codigo
            it[operadorExecucao] = operador
            it[concluidoEm] = agora
            it[localAtualizadoEm] = agora
            it[pendenteWriteBack] = true
        }
        linhas > 0
    }

    /**
     * Conclusão local depois de um write-back SÍNCRONO já confirmado (ex.:
     * SeparacaoService.finalizar, que já chamou ConferenciaSP.cortar de
     * verdade) — diferente de [concluirLocal], não marca
     * `pendente_write_back` (não há nada pendente, já aconteceu).
     *
     * Necessário porque uma nota com TGFCON2.STATUS='F' sai do critério de
     * busca do TarefaSyncService (mesmo critério da fila nativa do Sankhya:
     * conferência finalizada não aparece mais) — sem isto, este mirror local
     * ficaria travado em 'andamento' pra sempre, já que o próximo ciclo de
     * sync nunca mais devolve essa nota pra reconciliar.
     */
    fun concluirLocalSemWriteBack(tenantId: UUID, nunota: Long): Boolean = TenantTx.run(tenantId) {
        val agora = Instant.now()
        val linhas = TarefasTable.update({
            (TarefasTable.tenantId eq tenantId) and (TarefasTable.nunota eq nunota.toInt())
        }) {
            it[statusOperacional] = StatusOperacional.CONCLUIDO.codigo
            it[concluidoEm] = agora
            it[localAtualizadoEm] = agora
        }
        linhas > 0
    }

    fun marcarWriteBackConfirmado(tenantId: UUID, nunota: Long) = TenantTx.run(tenantId) {
        TarefasTable.update({ (TarefasTable.tenantId eq tenantId) and (TarefasTable.nunota eq nunota.toInt()) }) {
            it[pendenteWriteBack] = false
            it[localAtualizadoEm] = Instant.now()
        }
    }

    /** Pra retomar write-backs que ficaram pendentes de um restart do processo. */
    fun listarPendentesWriteBack(tenantId: UUID): List<Long> = TenantTx.run(tenantId) {
        TarefasTable.selectAll()
            .where { (TarefasTable.tenantId eq tenantId) and (TarefasTable.pendenteWriteBack eq true) }
            .map { it[TarefasTable.nunota].toLong() }
    }

    /**
     * NUCCO já sincronizado (ver TarefaSyncService.FIELDS) — evita a chamada
     * própria ao Sankhya que SeparacaoService.iniciar fazia só pra descobrir
     * isto (CabecalhoNota, ao vivo, toda vez). Uma tarefa só existe aqui
     * depois de já ter passado pelo CRITERIO_BASE da Fila de Tarefas, então
     * o NUCCO sempre deveria estar presente — null só em caso de corrida rara
     * (nota concluída/removida entre o clique e a leitura).
     */
    fun buscarNuccoLocal(tenantId: UUID, nunota: Long): Int? = TenantTx.run(tenantId) {
        TarefasTable.selectAll()
            .where { (TarefasTable.tenantId eq tenantId) and (TarefasTable.nunota eq nunota.toInt()) }
            .singleOrNull()
            ?.let { row ->
                val dados = runCatching { Json.parseToJsonElement(row[TarefasTable.dados]) as JsonObject }.getOrNull()
                dados?.get("TipoOperacao.NUCCO")?.jsonPrimitive?.contentOrNull?.toIntOrNull()
            }
    }

    private fun campoDosDados(tenantId: UUID, nunota: Long, campo: String): String? = TenantTx.run(tenantId) {
        TarefasTable.selectAll()
            .where { (TarefasTable.tenantId eq tenantId) and (TarefasTable.nunota eq nunota.toInt()) }
            .singleOrNull()
            ?.let { row ->
                val dados = runCatching { Json.parseToJsonElement(row[TarefasTable.dados]) as JsonObject }.getOrNull()
                dados?.get(campo)?.jsonPrimitive?.contentOrNull
            }
    }

    /** TIPMOV da nota (já sincronizado, ver TarefaSyncService.FIELDS) — 'V' venda, 'C' compra, etc. Usado pra listar TOPs de faturamento. */
    fun buscarTipMovLocal(tenantId: UUID, nunota: Long): String? = campoDosDados(tenantId, nunota, "TIPMOV")

    /** CODPARC da nota (já sincronizado) — usado pra montar os dados da etiqueta de volume. */
    fun buscarCodParcLocal(tenantId: UUID, nunota: Long): Int? =
        campoDosDados(tenantId, nunota, "CODPARC")?.toIntOrNull()
}
