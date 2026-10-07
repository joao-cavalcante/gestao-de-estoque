package wms.backend.tipooperacao

import kotlinx.serialization.Serializable

@Serializable
data class TipoOperacaoDto(
    val id: String,
    val codtop: Int,
    val descricao: String,
    val nucco: Int?,
    /** TGFCAB.TIPMOV — 'C'/'O' compra, 'V'/'P' venda (null = ainda não sincronizado). */
    val tipmov: String? = null,
    /** Conferência por etapa (Secos/Refrigerado/Congelado) pra notas deste TOP — V49, padrão true. */
    val conferenciaPorEtapa: Boolean = true,
    val localAtualizadoEm: String,
    /** Qtd. de usuários autorizados (V51) — 0 = sem restrição, todos usam. */
    val usuariosAutorizados: Int = 0,
    /** TOPs de destino do faturamento (restrição D no Sankhya — V57). */
    val destinos: List<TopDestinoDto> = emptyList(),
)

@Serializable
data class TopDestinoDto(val codtop: Int, val descricao: String, val serie: String? = null)

@Serializable
data class SincronizarTipoOperacaoResponse(val ok: Boolean, val totalAtualizado: Int)

@Serializable
data class TipoOperacaoConfigRequest(val conferenciaPorEtapa: Boolean)
