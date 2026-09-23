package wms.backend.tarefas

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.contentOrNull
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.inList
import org.jetbrains.exposed.sql.SqlExpressionBuilder.notInList
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.batchInsert
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.update
import wms.backend.separacao.SeparacaoRepository
import wms.backend.tenancy.TenantTx
import java.time.Instant
import java.util.UUID

/**
 * Uma linha crua vinda do loadRecords, pronta pra reconciliar.
 *
 * `nuconfAtual`/`libconf` vêm de TGFCAB (NUCONFATUAL/LIBCONF); `statusTgfcon2Raw`
 * é o STATUS de TGFCON2 pro NUCONF acima (só buscado quando `nuconfAtual` não é
 * nulo — null aqui não significa "vazio", significa "não se aplica"). A
 * resolução do status_operacional final (e a detecção de exclusão) acontece
 * dentro de `reconciliarLoteTx`, que é quem tem o valor anterior de `nuconfAtual`
 * pra comparar (ver StatusOperacional.kt).
 */
data class LinhaSankhya(
    val nunota: Long,
    val nuconfAtual: Int?,
    val libconf: String?,
    val statusTgfcon2Raw: String?,
    val dadosJson: String,
)

private data class InsercaoPendente(
    val nunotaInt: Int,
    val nuconfAtual: Int?,
    val statusSankhyaRaw: String,
    val statusOperacionalNovo: String,
    val dadosJson: String,
)

private data class AtualizacaoPendente(
    val nunotaInt: Int,
    val nuconfAtual: Int?,
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
     * Resolve o status_operacional (e o raw pra guardar em status_sankhya) de
     * uma linha, dado o `nuconfAtual` que a tarefa tinha ANTES deste ciclo
     * (null se é uma tarefa nova ou nunca teve conferência). Ver
     * StatusOperacional.kt pro raciocínio completo.
     */
    private fun resolverStatus(linha: LinhaSankhya, nuconfAtualAnterior: Int?): Pair<StatusOperacional, String> {
        val nuconf = linha.nuconfAtual
        if (nuconf != null) {
            val raw = linha.statusTgfcon2Raw ?: ""
            return mapearStatusTgfcon2(raw) to raw
        }
        if (nuconfAtualAnterior != null) {
            // Já teve NUCONFATUAL preenchido antes, agora não tem mais — exclusão
            // física da conferência. Sempre AGUARDANDO/AC, independente do LIBCONF
            // atual (que pode estar 'N' por causa da própria finalização anterior,
            // não por falta de liberação).
            return StatusOperacional.AGUARDANDO to "AC"
        }
        // Nunca teve conferência — LIBCONF decide se falta liberação do vendedor.
        return if (linha.libconf?.trim()?.uppercase() == "S") {
            StatusOperacional.AGUARDANDO to "AC"
        } else {
            StatusOperacional.AGUARDANDO_LIBERACAO to "AL"
        }
    }

    /**
     * Retorna as NUNOTAs deste ciclo cujo status operacional FINAL pertence à
     * família "aguardando" — quem chama usa pra invalidar a sessão de
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
        val exclusoesDetectadas = mutableListOf<Long>()

        for (linha in linhas) {
            val nunotaInt = linha.nunota.toInt()
            val existente = existentesPorNunota[nunotaInt]

            if (existente == null) {
                val (novoStatus, statusRaw) = resolverStatus(linha, nuconfAtualAnterior = null)
                paraInserir += InsercaoPendente(nunotaInt, linha.nuconfAtual, statusRaw, novoStatus.codigo, linha.dadosJson)
                continue
            }

            val nuconfAnterior = existente[TarefasTable.nuconfAtual]
            // Sinal inequívoco de exclusão física da conferência: tinha NUCONF,
            // agora não tem — NUCONFATUAL nunca zera numa finalização normal
            // (F/D/RF/RD ficam com ele preenchido permanentemente), então essa
            // transição só acontece quando a conferência foi excluída no
            // Sankhya. Dispara a limpeza de decisões de liberação de corte
            // obsoletas na hora — não é mais algo re-derivado depois via
            // histórico de auditoria quando uma sessão nova é criada.
            if (nuconfAnterior != null && linha.nuconfAtual == null) {
                exclusoesDetectadas += linha.nunota
            }

            val (novoStatus, statusSankhyaEfetivo) = resolverStatus(linha, nuconfAnterior)

            val statusSankhyaAnterior = existente[TarefasTable.statusSankhya]
            val dadosAnteriores = existente[TarefasTable.dados]

            // Comparação nos JSON já PARSEADOS (JsonObject == compara como
            // Map, ignora ordem de chave) — jsonb do Postgres não garante
            // preservar ordem de chave ao reler.
            val dadosSankhyaMudaram = statusSankhyaAnterior != statusSankhyaEfetivo ||
                nuconfAnterior != linha.nuconfAtual ||
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
                        nuconfAtual = linha.nuconfAtual,
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
                nuconfAtual = linha.nuconfAtual,
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
                this[TarefasTable.nuconfAtual] = item.nuconfAtual
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
                it[nuconfAtual] = item.nuconfAtual
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

        // Efeito colateral da exclusão detectada no loop acima — limpa
        // decisões de liberação de corte obsoletas na hora, em vez de
        // re-derivar depois via histórico de auditoria quando uma sessão de
        // separação nova é criada (ver SeparacaoRepository.criarSessao).
        exclusoesDetectadas.forEach { nunota ->
            SeparacaoRepository.limparDecisoesLiberacao(tenantId, nunota)
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
        val familiaAguardando = listOf(
            StatusOperacional.AGUARDANDO.codigo,
            StatusOperacional.AGUARDANDO_LIBERACAO.codigo,
            StatusOperacional.AGUARDANDO_RECONTAGEM.codigo,
        )
        return TarefasTable
            .selectAll()
            .where {
                (TarefasTable.tenantId eq tenantId) and
                    (TarefasTable.nunota inList nunotasInt) and
                    (TarefasTable.statusOperacional inList familiaAguardando)
            }
            .map { it[TarefasTable.nunota].toLong() }
    }

    /**
     * NUNOTAs locais em status NÃO-final (fora de CONCLUIDO/CONCLUIDO_DIVERGENTE/
     * RECONTAGEM_CONCLUIDA*) — candidatas a "sumiu do Sankhya" quando não aparecem
     * num ciclo de sync (ver TarefaSyncService.detectarExclusõesFisicas). Notas
     * concluídas saem do CRITERIO_BASE por razão normal (conferência finalizada não
     * aparece mais na fila nativa), então não entram aqui.
     */
    fun listarNunotasAtivasTx(tenantId: UUID): List<Long> {
        val statusFinais = listOf(
            StatusOperacional.CONCLUIDO.codigo,
            StatusOperacional.CONCLUIDO_DIVERGENTE.codigo,
            StatusOperacional.RECONTAGEM_CONCLUIDA.codigo,
            StatusOperacional.RECONTAGEM_CONCLUIDA_DIVERGENTE.codigo,
        )
        return TarefasTable.selectAll()
            .where { (TarefasTable.tenantId eq tenantId) and (TarefasTable.statusOperacional notInList statusFinais) }
            .map { it[TarefasTable.nunota].toLong() }
    }

    /**
     * Remove tarefas cujo NUNOTA foi confirmado como fisicamente inexistente no
     * Sankhya (não apenas fora do CRITERIO_BASE da fila — verificado à parte por
     * quem chama, ver TarefaSyncService). Sem isto, uma nota excluída direto no
     * Sankhya (fora do fluxo normal de conferência) nunca mais reaparece na
     * consulta de sync e fica travada pra sempre no último status local — foi
     * exatamente o que aconteceu numa exclusão em massa de pedidos no Sankhya.
     *
     * Grava auditoria ANTES de apagar (histórico não depende da linha continuar
     * existindo) e limpa o que dependia da tarefa (sessão de separação viva,
     * decisões de liberação de corte) — mesmo efeito colateral já disparado pra
     * exclusão de CONFERÊNCIA em reconciliarLoteTx, agora também pra exclusão da
     * NOTA inteira.
     */
    fun removerInexistentesNoSankhyaTx(tenantId: UUID, nunotas: List<Long>) {
        if (nunotas.isEmpty()) return
        val nunotasInt = nunotas.map { it.toInt() }
        val agora = Instant.now()

        val existentes = TarefasTable.selectAll()
            .where { (TarefasTable.tenantId eq tenantId) and (TarefasTable.nunota inList nunotasInt) }
            .associateBy { it[TarefasTable.nunota] }

        val paraAuditar = existentes.values.map { row ->
            AuditoriaPendente(
                nunotaInt = row[TarefasTable.nunota],
                statusAnterior = row[TarefasTable.statusOperacional],
                statusNovo = "excluido_sankhya",
                motivo = "Sincronização Sankhya: NUNOTA não existe mais no Sankhya (nota excluída) — removida da base local",
            )
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

        SeparacaoRepository.cancelarSessoesAtivasPorNotas(tenantId, nunotas, minIdadeSegundos = 0)
        nunotas.forEach { nunota -> SeparacaoRepository.limparDecisoesLiberacao(tenantId, nunota) }

        TarefasTable.deleteWhere { (TarefasTable.tenantId eq tenantId) and (TarefasTable.nunota inList nunotasInt) }
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
                    turnoEntrega = dados?.get("AD_TURNOENTREGA")?.jsonPrimitive?.contentOrNull
                        ?.trim()?.takeIf { it.isNotEmpty() }
                        // normaliza "1"/"1.0" -> "1" (mesma razão do ordemCarga acima)
                        ?.let { (it.toLongOrNull() ?: it.toDoubleOrNull()?.toLong())?.toString() },
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

    /** Sankhya já está em recontagem (R) mas o mirror local ficou em aguardando_corte — alinha sem esperar o sync. */
    fun marcarAguardandoRecontagemLocal(tenantId: UUID, nunota: Long): Boolean = TenantTx.run(tenantId) {
        TarefasTable.update({
            (TarefasTable.tenantId eq tenantId) and (TarefasTable.nunota eq nunota.toInt()) and
                (TarefasTable.statusOperacional eq StatusOperacional.AGUARDANDO_CORTE.codigo)
        }) {
            it[statusOperacional] = StatusOperacional.AGUARDANDO_RECONTAGEM.codigo
            it[statusSankhya] = "R"
            it[operadorExecucao] = null
            it[iniciadoEm] = null
            it[concluidoEm] = null
            it[localAtualizadoEm] = Instant.now()
        } > 0
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

    /**
     * (ORDEMCARGA, status_operacional) de toda tarefa local vinculada às Ordens de Carga
     * pedidas — usado pra montar o progresso de conferência por OC (Mapa de Separação).
     *
     * Fonte é o MIRROR LOCAL (app.tarefas), não uma consulta crua ao Sankhya por NUNOTA da
     * OC: nem toda nota vinculada a uma OC precisa de conferência (depende da config do
     * TipoOperacao — CRITERIO_BASE em TarefaSyncService) — contar essas como "pendente" pra
     * sempre inflava o total e a barra nunca fechava 100% mesmo com tudo que realmente
     * precisava conferência já concluído (bug real confirmado: OC 42 e 46). O mirror local só
     * tem nota que JÁ passou pelo critério — é o denominador certo.
     */
    /**
     * Quais dessas NUNOTAs passaram pelo critério de conferência, ou seja, estão no
     * mirror local (app.tarefas). Usado pelo Mapa de Separação pra ignorar nota da OC
     * que nunca vai ser conferida — mesmo universo de [statusPorOrdemCarga].
     */
    fun nunotasComConferencia(tenantId: UUID, nunotas: Collection<Long>): Set<Long> = TenantTx.run(tenantId) {
        val ints = nunotas.mapNotNull { n -> n.takeIf { it in 0..Int.MAX_VALUE }?.toInt() }
        if (ints.isEmpty()) return@run emptySet()
        TarefasTable.selectAll()
            .where { (TarefasTable.tenantId eq tenantId) and (TarefasTable.nunota inList ints) }
            .map { it[TarefasTable.nunota].toLong() }
            .toSet()
    }

    fun statusPorOrdemCarga(tenantId: UUID, ordensCarga: Set<Long>): List<Pair<Long, String>> = TenantTx.run(tenantId) {
        if (ordensCarga.isEmpty()) return@run emptyList()
        TarefasTable.selectAll()
            .where { TarefasTable.tenantId eq tenantId }
            .mapNotNull { row ->
                val dados = runCatching { Json.parseToJsonElement(row[TarefasTable.dados]) as JsonObject }.getOrNull()
                // vem como "46" ou "46.0" do loadRecords — mesma normalização de TarefasRepository.listar (ordemCarga).
                val ordemCarga = dados?.get("ORDEMCARGA")?.jsonPrimitive?.contentOrNull
                    ?.trim()?.takeIf { it.isNotEmpty() }
                    ?.let { it.toLongOrNull() ?: it.toDoubleOrNull()?.toLong() }
                    ?: return@mapNotNull null
                if (ordemCarga !in ordensCarga) return@mapNotNull null
                ordemCarga to row[TarefasTable.statusOperacional]
            }
    }
}
