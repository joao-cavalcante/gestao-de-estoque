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
    /** Base pro indicador de sincronização já existente na UI ("dados de Xs atrás"). */
    val segundosDesdeSync: Long,
    val pendenteWriteBack: Boolean,
)

@Serializable
data class ConcluirTarefaRequest(
    val operador: String,
)
