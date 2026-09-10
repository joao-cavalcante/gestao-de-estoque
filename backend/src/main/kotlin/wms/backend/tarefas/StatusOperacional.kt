package wms.backend.tarefas

enum class StatusOperacional(val codigo: String) {
    AGUARDANDO("aguardando"),
    ANDAMENTO("andamento"),
    // TGFCON2.STATUS = 'C': o ConferenciaSP.cortar deixou a conferência aguardando
    // liberação de corte (CCO com LIBCORTE='S'). Um liberador precisa aprovar/negar
    // os itens cortados (ver wms.backend.liberacaocorte) — a nota NÃO voltou pra fila.
    AGUARDANDO_CORTE("aguardando_corte"),
    CONCLUIDO("concluido"),
    CANCELADO("cancelado");

    companion object {
        fun porCodigo(codigo: String): StatusOperacional =
            entries.firstOrNull { it.codigo == codigo } ?: AGUARDANDO
    }
}

/**
 * Mapeamento do STATUSCONFERENCIA cru do Sankhya (campo calculado do
 * CabecalhoNota, mesmos códigos usados historicamente em TGFCON2.STATUS)
 * pro nosso enum semântico. Vazio/nulo = ainda não tem conferência aberta.
 */
private val MAPA_STATUS_SANKHYA: Map<String, StatusOperacional> = mapOf(
    "" to StatusOperacional.AGUARDANDO,
    "AC" to StatusOperacional.AGUARDANDO,
    "A" to StatusOperacional.ANDAMENTO,
    "C" to StatusOperacional.AGUARDANDO_CORTE,
    "F" to StatusOperacional.CONCLUIDO,
    "D" to StatusOperacional.CANCELADO,
)

fun mapearStatusSankhya(raw: String?): StatusOperacional =
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
    "Reaberta no Sankhya (recontagem) — estava '${de.codigo}' localmente; execução (operador/horários) " +
        "foi limpa para o novo ciclo, histórico preservado nesta auditoria"

private fun motivoCancelamento(de: StatusOperacional, para: StatusOperacional) =
    "Conferência excluída/cancelada no Sankhya — estava '${de.codigo}' localmente"

/**
 * Regra crítica do produto: quando NÃO há write-back local pendente, o
 * status vindo do Sankhya sempre prevalece — mesmo que isso signifique
 * regredir uma tarefa "concluída" pra "aguardando" (recontagem) ou pra
 * "cancelado" (exclusão). Tabela explícita de estados, não if/else solto —
 * cobre as 16 combinações possíveis de (status local atual × novo status
 * Sankhya) com uma regra clara para cada uma.
 */
val TABELA_TRANSICAO: Map<Pair<StatusOperacional, StatusOperacional>, Transicao> = buildMap {
    for (de in StatusOperacional.entries) {
        for (para in StatusOperacional.entries) {
            val transicao = when {
                de == para ->
                    Transicao(para, limparExecucao = false, motivo = ::motivoSemMudanca)

                para == StatusOperacional.CANCELADO ->
                    Transicao(para, limparExecucao = false, motivo = ::motivoCancelamento)

                para == StatusOperacional.AGUARDANDO &&
                    de in setOf(StatusOperacional.ANDAMENTO, StatusOperacional.AGUARDANDO_CORTE, StatusOperacional.CONCLUIDO) ->
                    Transicao(para, limparExecucao = true, motivo = ::motivoReabertura)

                else ->
                    Transicao(para, limparExecucao = false, motivo = ::motivoPadrao)
            }
            put(de to para, transicao)
        }
    }
}
