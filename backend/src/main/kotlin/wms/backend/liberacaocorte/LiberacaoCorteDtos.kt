package wms.backend.liberacaocorte

import kotlinx.serialization.Serializable

/** Uma conferência travada em TGFCON2.STATUS='C' (aguardando liberação de corte). */
@Serializable
data class ConferenciaAguardandoCorteDto(
    val nunota: Long,
    val numeroNota: Long? = null,
    val nomeParceiro: String? = null,
    val descricaoTipoOperacao: String? = null,
    val dataMovimento: String? = null,
    /** NUCONF resolvido da sessão local mais recente dessa nota — null se a conferência não passou pelo WMS. */
    val nuconf: Int? = null,
)

/** Item pendente de liberação (linha da ViewLiberacaoLimite já parseada). */
@Serializable
data class LiberacaoPendenteDto(
    val sequencia: Int,
    val produto: String? = null,
    val qtdPedido: Double? = null,
    val unidadePedido: String? = null,
    val qtdConferida: Double? = null,
    val unidadeConferida: String? = null,
    val diferenca: Double? = null,
)

@Serializable
data class ValidarLiberadorRequest(val usuario: String, val senha: String)

@Serializable
data class LiberarCorteRequest(
    val nuconf: Int,
    val usuario: String,
    val senha: String,
    /** 'S' = liberar, 'N' = negar. */
    val liberar: String,
    val sequencias: List<Int>,
    val obs: String? = null,
)

@Serializable
data class LiberarCorteResponse(val ok: Boolean = true, val itensProcessados: Int)
