package wms.backend.tarefas

/**
 * Espelha os 11 códigos reais de status de conferência do Sankhya (campo
 * calculado STATUSCONFERENCIA / TGFCON2.STATUS, confirmado com o usuário
 * contra o app nativo) + AGUARDANDO/AGUARDANDO_LIBERACAO, que não vêm de
 * TGFCON2 (a nota nem tem NUCONFATUAL setado nesses dois) — são resolvidos
 * localmente por TarefaSyncService a partir de TGFCAB.LIBCONF/NUCONFATUAL.
 *
 * NÃO existe um valor "cancelado": o Sankhya não tem esse conceito — quando
 * uma conferência é excluída, a nota simplesmente volta a AGUARDANDO (igual
 * a uma nota nunca conferida). A limpeza de decisões de liberação de corte
 * obsoletas é um efeito colateral disparado na hora que o sync detecta a
 * exclusão (NUCONFATUAL preenchido -> null), não um estado próprio (ver
 * TarefaSyncService/TarefasRepository.reconciliarLoteTx).
 */
enum class StatusOperacional(val codigo: String) {
    AGUARDANDO_LIBERACAO("aguardando_liberacao"), // AL
    AGUARDANDO("aguardando"), // AC — sem NUCONFATUAL (nunca conferida ou excluída)
    ANDAMENTO("andamento"), // A
    AGUARDANDO_CORTE("aguardando_corte"), // C
    AGUARDANDO_FINALIZACAO("aguardando_finalizacao"), // Z
    CONCLUIDO("concluido"), // F
    CONCLUIDO_DIVERGENTE("concluido_divergente"), // D
    AGUARDANDO_RECONTAGEM("aguardando_recontagem"), // R
    RECONTAGEM_ANDAMENTO("recontagem_andamento"), // RA
    RECONTAGEM_CONCLUIDA("recontagem_concluida"), // RF
    RECONTAGEM_CONCLUIDA_DIVERGENTE("recontagem_concluida_divergente"); // RD

    companion object {
        fun porCodigo(codigo: String): StatusOperacional =
            entries.firstOrNull { it.codigo == codigo } ?: AGUARDANDO
    }
}

/**
 * Mapeamento do STATUS cru de TGFCON2 (só usado quando NUCONFATUAL está
 * preenchido — ver TarefaSyncService) pro nosso enum. AC/AL não entram aqui
 * porque nunca vêm de TGFCON2 (resolvidos localmente por LIBCONF/histórico).
 */
private val MAPA_STATUS_SANKHYA: Map<String, StatusOperacional> = mapOf(
    "A" to StatusOperacional.ANDAMENTO,
    "C" to StatusOperacional.AGUARDANDO_CORTE,
    "Z" to StatusOperacional.AGUARDANDO_FINALIZACAO,
    "F" to StatusOperacional.CONCLUIDO,
    "D" to StatusOperacional.CONCLUIDO_DIVERGENTE, // "Finalizada divergente" — uma conclusão, NÃO cancelamento
    "R" to StatusOperacional.AGUARDANDO_RECONTAGEM,
    "RA" to StatusOperacional.RECONTAGEM_ANDAMENTO,
    "RF" to StatusOperacional.RECONTAGEM_CONCLUIDA,
    "RD" to StatusOperacional.RECONTAGEM_CONCLUIDA_DIVERGENTE,
)

fun mapearStatusTgfcon2(raw: String?): StatusOperacional =
    MAPA_STATUS_SANKHYA[raw?.trim()?.uppercase() ?: ""] ?: StatusOperacional.AGUARDANDO

/**
 * Resultado de uma transição de reconciliação: pra qual status_operacional
 * ir, se os campos de execução (operador/timestamps) devem ser limpos, e
 * como descrever isso na auditoria.
 */
data class Transicao(
    val resultado: StatusOperacional,
    val limparExecucao: Boolean,
    val motivo: (StatusOperacional, StatusOperacional) -> String,
)

private fun motivoSemMudanca(de: StatusOperacional, para: StatusOperacional) = ""

private fun motivoPadrao(de: StatusOperacional, para: StatusOperacional) =
    "Sincronização Sankhya: status operacional avançou de '${de.codigo}' para '${para.codigo}'"

private fun motivoReabertura(de: StatusOperacional, para: StatusOperacional) =
    "Reaberta no Sankhya (recontagem ou conferência excluída) — estava '${de.codigo}' localmente; execução " +
        "(operador/horários) foi limpa para o novo ciclo, histórico preservado nesta auditoria"

/** Famílias usadas pra decidir a transição — ver comentário de TABELA_TRANSICAO. */
private val FAMILIA_TRABALHANDO = setOf(
    StatusOperacional.ANDAMENTO,
    StatusOperacional.AGUARDANDO_CORTE,
    StatusOperacional.RECONTAGEM_ANDAMENTO,
    StatusOperacional.AGUARDANDO_FINALIZACAO,
)

private val FAMILIA_CONCLUSAO = setOf(
    StatusOperacional.CONCLUIDO,
    StatusOperacional.CONCLUIDO_DIVERGENTE,
    StatusOperacional.RECONTAGEM_CONCLUIDA,
    StatusOperacional.RECONTAGEM_CONCLUIDA_DIVERGENTE,
)

private val FAMILIA_AGUARDANDO = setOf(
    StatusOperacional.AGUARDANDO_LIBERACAO,
    StatusOperacional.AGUARDANDO,
    StatusOperacional.AGUARDANDO_RECONTAGEM,
)

/**
 * Regra crítica do produto: quando NÃO há write-back local pendente, o
 * status vindo do Sankhya sempre prevalece — mesmo que isso signifique
 * regredir uma tarefa "concluída" pra "aguardando" (recontagem OU exclusão
 * — os dois casos resolvem pro mesmo AGUARDANDO/AC, ver StatusOperacional).
 * Tabela gerada por regras sobre o produto cartesiano (não célula por
 * célula) — crescer o enum não exige autorar N² casos à mão, só classificar
 * o estado novo numa das famílias acima.
 */
val TABELA_TRANSICAO: Map<Pair<StatusOperacional, StatusOperacional>, Transicao> = buildMap {
    for (de in StatusOperacional.entries) {
        for (para in StatusOperacional.entries) {
            val transicao = when {
                de == para ->
                    Transicao(para, limparExecucao = false, motivo = ::motivoSemMudanca)

                para in FAMILIA_AGUARDANDO && (de in FAMILIA_TRABALHANDO || de in FAMILIA_CONCLUSAO) ->
                    Transicao(para, limparExecucao = true, motivo = ::motivoReabertura)

                else ->
                    Transicao(para, limparExecucao = false, motivo = ::motivoPadrao)
            }
            put(de to para, transicao)
        }
    }
}
