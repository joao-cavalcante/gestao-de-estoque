package wms.backend.transferencia

import kotlinx.serialization.Serializable

@Serializable
data class ModeloNotaDto(
    val codtop: Int,
    val codemp: Int,
    val codnat: Int,
    val ativo: Boolean,
)

@Serializable
data class LocalEstoqueDto(
    val codigo: String,
    val ativo: Boolean,
)

@Serializable
data class ProdutoEstoqueDto(
    val codigo: String,
    val nome: String,
    val ctrl: String,
    val modo: String,
    val qtdEtiqueta: String?,
    val unidade: String,
)

@Serializable
data class CriarTransferenciaRequest(
    val origem: String,
    val destino: String,
    val canalOrigem: String,
)

@Serializable
data class TransferenciaCriadaDto(
    val id: String,
    val origem: String,
    val destino: String,
    val status: String,
)

/** `quantidade` só é obrigatória quando o produto é granel (peso/medida variável) — nos demais modos o backend resolve sozinho (etiqueta soma automático, peça avulsa soma +1). */
@Serializable
data class AdicionarItemRequest(
    val codigoLido: String,
    val quantidade: String? = null,
)

@Serializable
data class ItemTransferenciaDto(
    val id: String,
    val codigoProduto: String,
    val nomeProduto: String,
    val controle: String,
    val quantidade: String,
    val unidade: String,
)

@Serializable
data class TransferenciasPaginadasDto(
    val itens: List<TransferenciaListDto>,
    val paginaAtual: Int,
    val totalPaginas: Int,
    val totalRegistros: Int,
)

@Serializable
data class TransferenciaListDto(
    val id: String,
    val criadoEm: String,
    val origem: String,
    val destino: String,
    val totalItens: Int,
    val totalUnidades: String,
    val canalOrigem: String,
    val status: String,
    val operadorNome: String,
)
