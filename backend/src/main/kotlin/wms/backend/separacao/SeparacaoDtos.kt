package wms.backend.separacao

import kotlinx.serialization.Serializable

@Serializable
data class IniciarSeparacaoRequest(val nunota: Long)

@Serializable
data class SessaoSeparacaoDto(
    val id: String,
    val nunota: Long,
    val status: String,
    val erro: String? = null,
)

@Serializable
data class CodigoBarraDto(
    val codigoBarra: String,
    val codprod: Int,
    val codvol: String?,
    val controle: String,
    val origem: String,
)

@Serializable
data class ResolverCodigoBarraRequest(val codigoBarra: String)

@Serializable
data class ItemResolvido(
    val codprod: Int,
    val descricaoProduto: String?,
    val referencia: String?,
    val codvol: String?,
    val controle: String,
    val fatorConversao: String?,
    val divideMultiplica: String?,
)

@Serializable
data class IdentificarProdutoRequest(val codigoBarra: String)

@Serializable
data class IdentificarProdutoResultado(
    val codprod: Int,
    val descricaoProduto: String?,
    /** true = campo "Nº do Lote" com digitação livre; false = <select> com [controlesDisponiveis]. */
    val controleModoLote: Boolean,
    /** "SEM_CONTROLE" é sentinel (mesmo contrato do projeto base) — o front mostra "Sem controle" e desabilita a opção. */
    val controlesDisponiveis: List<String>,
    /** Só preenchido quando o produto não tem controle, ou o código veio de ESTOQUE (EST) já com controle — nunca uma "adivinhação". */
    val controleAutoSelecionado: String?,
    /** true = campo desabilitado (mesmos 2 casos acima); operador não pode alterar. */
    val controleTravado: Boolean,
    /** Preenchido na camada da rota (busca é suspend/assíncrona — ver ProdutoImagemService), não no repositório. */
    val imagemBase64: String? = null,
)

@Serializable
data class ConferirItemRequest(val codprod: Int, val controle: String, val qtd: String)

@Serializable
data class DevolverItemRequest(val codprod: Int, val controle: String)

@Serializable
data class ItemConferidoResultado(
    val sequencia: Int,
    val codprod: Int,
    val controle: String,
    val descricaoProduto: String?,
    val qtdConferidaLocal: String,
    val qtdTotalLida: String,
)

@Serializable
data class ItemSeparacaoDto(
    val sequencia: Int,
    val codprod: Int,
    val controle: String,
    val codvol: String?,
    val qtdNeg: String,
    val qtdEntregue: String,
    val qtdConferidaLocal: String,
    val descricaoProduto: String? = null,
    val complementoDescricao: String? = null,
    val marca: String? = null,
    val referencia: String? = null,
)
