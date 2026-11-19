package wms.backend.entrada

import kotlinx.serialization.Serializable
import wms.backend.tarefas.AjustePesoDto
import wms.backend.tarefas.AndamentoDto

/**
 * Situação do recebimento na tela Entrada (19/11/2026 — proposta "Entrada com endereçamento", etapa 2, primeira
 * entrega só de visibilidade). As três primeiras vêm da conferência do Sankhya (TGFCON2); as duas últimas dizem se a
 * compra conferida já foi confirmada (TGFCAB.STATUSNOTA = 'L') — hoje a confirmação é manual, pela responsável do
 * recebimento.
 */
object SituacaoRecebimento {
    const val AGUARDANDO_CONFERENCIA = "aguardando_conferencia"
    const val EM_CONFERENCIA = "em_conferencia"
    const val DIVERGENCIA = "divergencia"
    const val AGUARDANDO_LIBERACAO = "aguardando_liberacao"
    const val LIBERADA = "liberada"
}

/** Nota que o Sankhya gerou na conferência: devolução (TGFCON2.NUNOTADEV) ou complemento (TGFCON2.NUPEDCOMP). */
@Serializable
data class NotaDerivadaDto(val nunota: Long, val numero: Long? = null, val confirmada: Boolean = false)

/** Card da tela Entrada — GET /api/entrada. */
@Serializable
data class RecebimentoDto(
    val nunota: Long,
    val numeroNota: Long? = null,
    val codigoParceiro: String? = null,
    val fornecedor: String? = null,
    val codigoEmpresa: String? = null,
    val nomeEmpresa: String? = null,
    val dataMovimento: String? = null,
    val codigoTipoOperacao: String? = null,
    val tipoOperacao: String? = null,
    val tipMov: String? = null,
    /** StatusOperacional da conferência (mesmo código da Fila). */
    val statusConferencia: String,
    /** [SituacaoRecebimento]. */
    val situacao: String,
    val pesoBruto: Double? = null,
    val andamento: AndamentoDto? = null,
    val ajustesPeso: List<AjustePesoDto>? = null,
    /** TGFCAB.STATUSNOTA = 'L' (null = ainda não lido do Sankhya neste processo). */
    val notaConfirmada: Boolean? = null,
    val devolucao: NotaDerivadaDto? = null,
    val complemento: NotaDerivadaDto? = null,
    val segundosDesdeSync: Long,
)

/** Linha da mesa de liberação: item da nota × conferido na Torre. */
@Serializable
data class ItemMesaDto(
    val sequencia: Int,
    val codprod: Int,
    val produto: String,
    val controle: String? = null,
    val unidade: String? = null,
    val qtdNota: Double,
    val qtdConferida: Double,
    val diferenca: Double,
    val pesavel: Boolean,
    /** Diferença que foi para a nota de ajuste de peso (dentro da tolerância) — null quando não houve. */
    val ajustePeso: Double? = null,
    /** [TipoItemMesa]. */
    val tipo: String,
)

object TipoItemMesa {
    const val OK = "ok"
    const val FALTA = "falta"
    const val SOBRA = "sobra"
    const val AJUSTE = "ajuste"
    const val FORA_PEDIDO = "fora_pedido"
}

/** GET /api/entrada/{nunota}/mesa. */
@Serializable
data class MesaLiberacaoDto(
    val recebimento: RecebimentoDto,
    val itens: List<ItemMesaDto>,
    /** false = a conferência não foi feita pela Torre (ou a sessão já foi limpa): não há itens para comparar. */
    val conferenciaNaTorre: Boolean,
    val conferente: String? = null,
    val conferenciaInicio: String? = null,
    val conferenciaFim: String? = null,
    /** Houve recontagem: a quantidade de cada item é a da contagem mais recente. */
    val recontagem: Boolean = false,
    /** Quando a situação da nota foi lida do Sankhya (ISO-8601) — null = ainda não lida. */
    val situacaoLidaEm: String? = null,
    /** Aprovar pela Torre só existe com o addon (etapa 1); hoje a confirmação é no Sankhya. */
    val aprovacaoDisponivel: Boolean = false,
)
