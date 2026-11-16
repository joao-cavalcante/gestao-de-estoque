package wms.backend.galpao

import kotlinx.serialization.Serializable

@Serializable
data class PlantaDto(
    val id: String,
    val nome: String,
    val codemp: Int?,
    val codlocal: Int?,
    val largura: Double,
    val profundidade: Double,
    val versaoPublicada: Int?,
    val totalEnderecos: Int,
    val temRascunho: Boolean,
)

@Serializable
data class SalvarPlantaRequest(
    val nome: String,
    val codemp: Int? = null,
    val codlocal: Int? = null,
    val largura: Double,
    val profundidade: Double,
)

@Serializable
data class VersaoResumoDto(
    val id: String,
    val numero: Int,
    val status: String,
    val observacao: String?,
    val criadoPorNome: String,
    val criadoEm: String,
    val publicadoPorNome: String?,
    val publicadoEm: String?,
    val totalObjetos: Int,
)

@Serializable
data class VersaoDto(val versao: VersaoResumoDto, val geometria: GeometriaPlanta)

@Serializable
data class SalvarRascunhoRequest(val geometria: GeometriaPlanta, val observacao: String? = null)

@Serializable
data class ValidacaoDto(val valido: Boolean, val erros: List<String>, val totalEnderecos: Int)

/** Publicar mexe no cadastro de endereços: a tela pede que o usuário digite o nome da planta. */
@Serializable
data class PublicarRequest(val confirmacao: String)

@Serializable
data class SaldoEnderecoDto(
    val codprod: Int,
    val produto: String,
    val controle: String,
    val quantidade: Double,
    /** Versão do saldo — a movimentação devolve 409 se mudou desde que a tela leu. */
    val versao: Int,
)

@Serializable
data class EnderecoDto(
    val id: String,
    val codigo: String,
    val setor: String,
    val estrutura: String,
    val modulo: Int,
    val nivel: Int,
    val tipo: String,
    val x: Double,
    val y: Double,
    val z: Double,
    val rotacao: Double,
    val largura: Double,
    val comprimento: Double,
    val altura: Double,
    val capQtd: Double?,
    val capPeso: Double?,
    val capVolume: Double?,
    val bloqueado: Boolean,
    val quantidade: Double,
    /** Maior razão entre quantidade, peso e volume (CapacidadeEndereco); null = N/D, não 0%. */
    val ocupacao: Double?,
    /** QUANTIDADE | PESO | VOLUME — qual medida definiu a ocupação. */
    val baseOcupacao: String?,
    val saldos: List<SaldoEnderecoDto>,
    val contagensPendentes: Int,
    /** Cada razão em separado (null = sem capacidade ou medida desconhecida) — modos de cor do 3D. */
    val ocupacaoQtd: Double? = null,
    val ocupacaoPeso: Double? = null,
    val ocupacaoVolume: Double? = null,
    /** Peso (kg) e volume (m³) no endereço; null = algum produto sem cadastro no Sankhya. */
    val pesoKg: Double? = null,
    val volumeM3: Double? = null,
    /** Último movimento físico que tocou o endereço (origem ou destino), ISO-8601. */
    val ultimoMovimentoEm: String? = null,
    /** Algum produto do endereço está numa conferência em andamento na Torre. */
    val emSeparacao: Boolean = false,
)

/** Tudo que a visualização 3D precisa numa ida: o desenho publicado e os endereços com ocupação. */
@Serializable
data class VisaoPlantaDto(
    val planta: PlantaDto,
    val geometria: GeometriaPlanta?,
    val enderecos: List<EnderecoDto>,
    /** Pedidos com conferência em andamento na Torre (indicador "Em separação"). */
    val documentosEmSeparacao: Int = 0,
    val geradoEm: String = "",
)

/** Saldo contábil do empresa/local da planta (TGFEST próprio) — indicador da visão; lido à parte. */
@Serializable
data class IndicadoresPlantaDto(
    val saldoContabil: Double?,
    val produtosComSaldo: Int?,
    val lidoEm: String,
    val aviso: String?,
)

/** Documento do Sankhya (NUNOTA, ou nº do pedido/nota no empresa da planta) e os produtos dele — busca da visão. */
@Serializable
data class DocumentoBuscaDto(
    val nunota: Int,
    val numnota: Int?,
    val tipmov: String?,
    val codprods: List<Int>,
)

// ─── Movimentação (fase 4) ──────────────────────────────────────────────────

@Serializable
data class MovimentoRequest(
    /** UUID gerado pela tela — reenviar o mesmo não repete o movimento. */
    val comandoId: String,
    /** ENDERECAR | TRANSFERIR | RETIRAR | CONTAR | BLOQUEAR | DESBLOQUEAR */
    val tipo: String,
    val origemId: String? = null,
    val destinoId: String? = null,
    val codprod: Int? = null,
    val controle: String = "",
    /** Na CONTAR é a quantidade contada (pode ser zero). */
    val quantidade: Double = 0.0,
    val versaoOrigem: Int? = null,
    val versaoDestino: Int? = null,
    val motivo: String? = null,
)

@Serializable
data class ResultadoMovimentoDto(
    val repetido: Boolean,
    val mensagem: String,
    val movimentoId: String? = null,
    val contagemId: String? = null,
)

@Serializable
data class DecisaoContagemRequest(val observacao: String? = null)

@Serializable
data class MovimentoDto(
    val id: String,
    val tipo: String,
    val origem: String?,
    val destino: String?,
    val codprod: Int?,
    val produto: String?,
    val controle: String,
    val quantidade: Double,
    val motivo: String?,
    val usuarioNome: String,
    val criadoEm: String,
)

@Serializable
data class MovimentosPaginadosDto(val itens: List<MovimentoDto>, val paginaAtual: Int, val totalPaginas: Int, val totalRegistros: Int)

@Serializable
data class ContagemDto(
    val id: String,
    val endereco: String,
    val enderecoId: String,
    val codprod: Int,
    val produto: String,
    val controle: String,
    val quantidadeSistema: Double,
    val quantidadeContada: Double,
    val diferenca: Double,
    val motivo: String?,
    val status: String,
    val contadoPorNome: String,
    val contadoEm: String,
    val decididoPorNome: String?,
    val decididoEm: String?,
    val observacaoDecisao: String?,
)

@Serializable
data class ProdutoBuscaDto(val codprod: Int, val produto: String, val unidade: String?)

@Serializable
data class ControleEstoqueDto(
    val controle: String,
    val contabil: Double,
    val reservado: Double,
    val enderecado: Double,
    val semEndereco: Double,
)

@Serializable
data class ProdutoNoEnderecoDto(val endereco: String, val enderecoId: String, val controle: String, val quantidade: Double)

@Serializable
data class EstoqueProdutoDto(
    val codprod: Int,
    val produto: String,
    val controles: List<ControleEstoqueDto>,
    val enderecos: List<ProdutoNoEnderecoDto>,
    val aviso: String?,
)

@Serializable
data class ConciliacaoLinhaDto(
    val codprod: Int,
    val produto: String,
    val controle: String,
    val contabil: Double,
    val reservado: Double,
    val enderecado: Double,
    val semEndereco: Double,
    val excedente: Double,
    /** OK | PARCIAL | SEM_ENDERECO | EXCEDENTE | NEGATIVO (saldo negativo no Sankhya) */
    val situacao: String,
)

@Serializable
data class ConciliacaoDto(val codemp: Int, val codlocal: Int, val linhas: List<ConciliacaoLinhaDto>, val lidoEm: String)
