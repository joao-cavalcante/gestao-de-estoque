package wms.backend.tarefas

import kotlinx.serialization.Serializable

/** Resposta de GET /api/tarefas — lida EXCLUSIVAMENTE da base local, nunca do Sankhya. */
@Serializable
data class TarefaApiDto(
    val nunota: Long,
    val tipo: String,
    val statusOperacional: String,
    val statusSankhya: String,
    val numeroNota: Long?,
    val codigoParceiro: String?,
    val nomeParceiro: String?,
    val codigoVendedor: String?,
    val apelidoVendedor: String?,
    val dataMovimento: String?,
    val codigoTipoOperacao: String?,
    val descricaoTipoOperacao: String?,
    /** TGFCAB.ORDEMCARGA — número da ordem/onda de carga (null quando a nota não está numa carga). */
    val ordemCarga: Long? = null,
    /** Motorista e veículo da Ordem de Carga (ver TransporteOrdemCarga) — null sem OC ou ainda não carregado. */
    val motorista: String? = null,
    val placa: String? = null,
    val veiculo: String? = null,
    /** TGFCAB.AD_TURNOENTREGA — período pra entrega: "1" Diurno | "2" Noturno | "9" Qualquer. */
    val turnoEntrega: String? = null,
    /** TGFCAB.AD_EXPRESS = 'S'. */
    val express: Boolean = false,
    /** TGFCAB.AD_RETIRA = 'S' (cliente retira). */
    val retira: Boolean = false,
    /** TGFCAB.AD_ENTREGA = 'S'. */
    val entrega: Boolean = false,
    /** Base pro indicador de sincronização já existente na UI ("dados de Xs atrás"). */
    val segundosDesdeSync: Long,
    val pendenteWriteBack: Boolean,
    /** Carregamento (checklist do "Ver conferidos") — só pra nota conferida com OC; null = não se aplica. */
    val carregamento: CarregamentoResumoDto? = null,
    /** V58 — conferida, CCO pede faturamento e a nota ainda não saiu confirmada: card AGUARDANDO NOTA + "Faturar". */
    val notaPendente: wms.backend.aguardandonota.NotaPendenteDto? = null,
    /** TGFORD.SITUACAO = 'F' — OC fechada: os pedidos dela saem da fila. */
    val ordemCargaFechada: Boolean = false,
)

/**
 * Itens conferidos x carregados (checados) de uma nota, somando as sessões concluídas (recontagem
 * é outra sessão). `sessaoId` = sessão a abrir no checklist (a que ainda tem item a carregar).
 */
@Serializable
data class CarregamentoResumoDto(
    val total: Int,
    val carregados: Int,
    val sessaoId: String?,
)

@Serializable
data class ConcluirTarefaRequest(
    val operador: String,
)

/** Modalidade do pedido (TGFCAB.AD_EXPRESS / AD_RETIRA / AD_ENTREGA = 'S'), lida do mirror local. */
@Serializable
data class ModalidadePedido(
    val express: Boolean = false,
    val retira: Boolean = false,
    val entrega: Boolean = false,
)
