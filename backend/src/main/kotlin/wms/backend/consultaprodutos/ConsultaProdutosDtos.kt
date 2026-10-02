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
    /** Unidade padrão (TGFPRO.CODVOL) — o Estoque guarda o saldo sempre nela. */
    val unidade: String? = null,
    /** Regra central (RegraPesavel). null = não deu pra decidir (falha ao ler do Sankhya). */
    val pesavel: Boolean? = null,
    /** Só pro filtro de texto da tela achar o produto pelo código de barras. */
    val codigosBarra: List<String> = emptyList(),
    val estoque: Double = 0.0,
    val reservado: Double = 0.0,
    val disponivel: Double = 0.0,
    val locais: List<EstoqueLocalDto> = emptyList(),
)

@Serializable
data class ConsultaProdutosRespostaDto(
    val produtos: List<ProdutoEstoqueDto>,
    /** Quando o saldo foi lido do Sankhya (ISO-8601) — a leitura é reaproveitada por alguns minutos. */
    val estoqueLidoEm: String? = null,
    /** Preenchido quando o catálogo veio mas o estoque não veio do Sankhya. */
    val erroEstoque: String? = null,
)
