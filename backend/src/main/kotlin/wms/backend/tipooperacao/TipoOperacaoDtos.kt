package wms.backend.tipooperacao

import kotlinx.serialization.Serializable

@Serializable
data class TipoOperacaoDto(
    val id: String,
    val codtop: Int,
    val descricao: String,
    val nucco: Int?,
    val localAtualizadoEm: String,
)

@Serializable
data class SincronizarTipoOperacaoResponse(val ok: Boolean, val totalAtualizado: Int)
