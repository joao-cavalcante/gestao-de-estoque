package wms.backend.consultaprodutos

import kotlinx.serialization.Serializable

/** Saldo de um produto numa empresa + local + controle (uma linha da instância Estoque). */
@Serializable
data class EstoqueLocalDto(
    val codemp: Int? = null,
    val empresa: String? = null,
    val codlocal: Int? = null,
    val local: String? = null,
    /** Lote/controle — " " (vazio) quando o produto não controla. */
    val controle: String? = null,
    val estoque: Double = 0.0,
    val reservado: Double = 0.0,
    val disponivel: Double = 0.0,
)

@Serializable
data class ProdutoEstoqueDto(
    val codprod: Int,
    val descricao: String,
    val complemento: String? = null,
    val marca: String? = null,
    val referencia: String? = null,
    /** Unidade padrão (Produto.CODVOL) — o Estoque guarda o saldo sempre nela. */
    val unidade: String? = null,
    val estoque: Double = 0.0,
    val reservado: Double = 0.0,
    val disponivel: Double = 0.0,
    val locais: List<EstoqueLocalDto> = emptyList(),
)

@Serializable
data class ConsultaProdutosRespostaDto(
    val produtos: List<ProdutoEstoqueDto>,
    /** true = a busca achou mais que o limite; só os primeiros vieram. */
    val limitado: Boolean = false,
    /** Preenchido quando a busca local deu certo mas o estoque não veio do Sankhya. */
    val erroEstoque: String? = null,
)
