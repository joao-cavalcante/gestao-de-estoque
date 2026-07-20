package wms.backend.configconferencia

import kotlinx.serialization.Serializable

@Serializable
data class ConfigConferenciaListDto(
    val id: String,
    val nucco: Int,
    val descricao: String,
    val sankhyaAtualizadoEm: String?,
)

@Serializable
data class ConfigConferenciaDetalheDto(
    val id: String,
    val nucco: Int,
    val descricao: String,
    val campos: Map<String, String?>,
    val sankhyaAtualizadoEm: String?,
    val localAtualizadoEm: String,
)

@Serializable
data class SincronizarResponse(val ok: Boolean, val totalSincronizado: Int)

/** Edição local — ver ConfigConferenciaRepository.atualizarLocal. */
@Serializable
data class AtualizarConfigConferenciaRequest(val campos: Map<String, String?>)
