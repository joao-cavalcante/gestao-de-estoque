package wms.backend.inventario

import kotlinx.serialization.Serializable

@Serializable
data class AbrirInventarioRequest(
    val descricao: String,
    val escopoTipo: String,
    val escopoValores: List<String>,
)

@Serializable
data class InventarioCriadoDto(val id: String, val status: String)

@Serializable
data class RegistrarContagemRequest(
    val local: String,
    val codigoLido: String,
    val quantidade: String? = null,
)

@Serializable
data class ItemInventarioDto(
    val id: String,
    val produtoCodigo: String,
    val nomeProduto: String,
    val controle: String,
    val local: String,
    val quantidadeSistema: String,
    val quantidadeContada: String,
    val divergencia: String,
    val statusItem: String,
    val canalOrigem: String? = null,
)

@Serializable
data class InventarioListDto(
    val id: String,
    val descricao: String,
    val status: String,
    val abertoEm: String,
    val abertoPorNome: String,
    val totalItens: Int,
    val itensContados: Int,
)

@Serializable
data class InventariosPaginadosDto(
    val itens: List<InventarioListDto>,
    val paginaAtual: Int,
    val totalPaginas: Int,
    val totalRegistros: Int,
)

@Serializable
data class InventarioDetalheDto(
    val id: String,
    val descricao: String,
    val status: String,
    val escopoTipo: String,
    val escopoValores: List<String>,
    val abertoPorNome: String,
    val abertoEm: String,
    val totalItens: Int,
    val itensContados: Int,
    val itens: List<ItemInventarioDto>,
)

@Serializable
data class MudarStatusRequest(val status: String)

@Serializable
data class AprovarResponse(val ok: Boolean, val avisoRecontagem: String? = null)
