package wms.backend.separacao

import kotlinx.serialization.Serializable

@Serializable
data class IniciarSeparacaoRequest(val nunota: Long)

@Serializable
data class VolumeDto(val quantidade: Int)

@Serializable
data class UmaDto(
    val codprod: Int,
    val coduma: Int,
    val descricao: String?,
    val peso: String?,
    val codvol: String?,
    val codbarra: String?,
    val padrao: Boolean,
)

@Serializable
data class DefinirVolumeRequest(val quantidade: Int)

@Serializable
data class SessaoSeparacaoDto(
    val id: String,
    val nunota: Long,
    val status: String,
    val erro: String? = null,
    /** CCO.OBTERQTDBALANCA cru — 'N' (ou ausente) = fluxo de peso desativado pra esta sessão. */
    val obterQtdBalanca: String? = null,
    /** Módulo por-tenant "conferência segmentada" (ver Modulos.kt) — snapshot da sessão; false = conferência normal. */
    val conferenciaSegmentada: Boolean = false,
    /** CCO.FATAOCONCLUIR cru — 'S' = oferecer faturamento (escolha de TOP) após finalizar. */
    val fatAoConcluir: String? = null,
    // CCO "Comportamento da interface" cru (V28) — o front gateia painéis com isso.
    // Só 'N' explícito esconde; null/'S'/outro = mostra.
    /** CCO.EXIBIRPROD — painel de pendentes. */
    val exibirProd: String? = null,
    /** CCO.EXIBIRQTD — qtd negociada na lista de pendentes. */
    val exibirQtd: String? = null,
    /** CCO.EXIBIRPRODCONF — painel de conferidos. */
    val exibirProdConf: String? = null,
    /** CCO.EXIBIRQTDCONF — qtd conferida (lista de conferidos + última leitura). */
    val exibirQtdConf: String? = null,
    /** CCO.EXIBIRIMGPROD — painel de imagem / última leitura. */
    val exibirImgProd: String? = null,
)

@Serializable
data class FinalizarResultadoDto(
    val ok: Boolean = true,
    /** true = ConferenciaSP.cortar deixou a conferência em TGFCON2.STATUS='C' (aguardando liberação de corte). */
    val aguardandoCorte: Boolean = false,
    val nuconf: Int? = null,
)

// ─── Conferência por etapa (V29) ────────────────────────────────────────────

@Serializable
data class EtapaSeparacaoDto(
    /** 1 Secos | 2 Resfriados | 3 Congelados. */
    val tipoSeparacao: Int,
    /** 'P' pendente | 'C' concluída. */
    val status: String,
    val concluidaPor: String? = null,
    val concluidaEm: String? = null,
)

@Serializable
data class ConcluirEtapaRequest(
    val tipoSeparacao: Int,
    /** true = concluir a etapa mesmo com item pendente nela (operador confirmou no modal). */
    val manterPendente: Boolean = false,
    val operador: String,
)

@Serializable
data class ConcluirEtapaResultadoDto(
    val etapaConcluida: Boolean = true,
    /** true = era a última etapa pendente; a conferência foi finalizada no Sankhya agora. */
    val conferenciaFinalizada: Boolean = false,
    /** Preenchidos só quando conferenciaFinalizada = true (ver FinalizarResultadoDto). */
    val aguardandoCorte: Boolean = false,
    val nuconf: Int? = null,
)

@Serializable
data class EtapaComPendentesDto(val erro: String = "etapa com pendentes", val pendentes: Int)

@Serializable
data class FilaEtapasRequest(val nunotas: List<Long>)

@Serializable
data class FilaEtapasDto(
    /** Tipos de separação PRESENTES na nota (com item). Vazio = nota não segmentada / sem itens. */
    val tipos: List<Int>,
    /** Tipos já concluídos localmente. */
    val concluidos: List<Int>,
    /** Progresso por tipo (só tipos que têm sessão local com itens) — pra "Continuar 3/8" no card. */
    val progresso: Map<Int, EtapaProgressoDto> = emptyMap(),
)

@Serializable
data class EtapaProgressoDto(val total: Int, val conferidos: Int)

@Serializable
data class TopFaturamentoDto(val codTipOper: Int, val descricao: String)

@Serializable
data class FaturarRequest(val codTipOper: Int, val serie: String? = null)

@Serializable
data class ConferenciaFinalizadaDto(
    val sessaoId: String,
    val nunota: Long,
    val numeroNota: Long? = null,
    val nomeParceiro: String? = null,
    val descricaoTipoOperacao: String? = null,
    val dataMovimento: String? = null,
    val apelidoVendedor: String? = null,
    val nuconf: Int? = null,
)

@Serializable
data class ConferenciasFinalizadasResponse(
    val itens: List<ConferenciaFinalizadaDto>,
    val total: Int,
    val page: Int,
    val perPage: Int,
)

@Serializable
data class EtiquetaDadosDto(
    val cliente: String,
    val uf: String,
    /** Número da nota com 5 dígitos (zero à esquerda) — mesmo formato do template do projeto base. */
    val numeroNota: String,
    val numeroConferencia: Int?,
    val totalVolumes: Int,
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
data class IdentificarProdutoRequest(
    val codigoBarra: String,
    /**
     * Preenchido quando o operador CLICA num item da lista de pendentes em vez
     * de bipar — resolve o produto direto por CODPROD (sem passar pelas regras
     * de código de barras), na unidade padrão. Elimina a digitação do código
     * no mobile. `codigoBarra` vira só rótulo do campo nesse caso.
     */
    val codprod: Int? = null,
    /** Etapa ativa na tela (conferência segmentada) — item fora do pedido nasce nesta etapa (1-3). */
    val etapa: Int? = null,
)

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
    /** TGFVOL.UTILICONFPESO do codvol — rotina de peso portada do projeto base (combinar com SessaoSeparacaoDto.obterQtdBalanca no front). */
    val usaConfPeso: Boolean = false,
    /** Unidade escanada (VOA) — reenviada no /conferir p/ virar CODVOL no Sankhya. */
    val codvol: String? = null,
    /** Preenchido na camada da rota (busca é suspend/assíncrona — ver ProdutoImagemService), não no repositório. */
    val imagemBase64: String? = null,
)

@Serializable
data class ConferirItemRequest(
    val codprod: Int,
    val controle: String,
    val qtd: String,
    val peso: String? = null,
    /** Unidade escanada (VOA) — grava em separacao_leituras.codvol e vai pro CODVOL do Sankhya. */
    val codvol: String? = null,
    /** Código de barras escanado de fato — grava em separacao_leituras.codigo_barra e vai pro CODBARRA do Sankhya. */
    val codigoBarra: String? = null,
)

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
    val usaConfPeso: Boolean = false,
    /** Produto fora do pedido (qtd_neg=0, só existe porque foi bipado). */
    val foraPedido: Boolean = false,
    /** TGFPRO.AD_TIPOSEPARACAO — 1 Secos | 2 Resfriados | 3 Congelados. Conferência por etapa (V29). */
    val tipoSeparacao: Int = 1,
    /** Unidades alternativas (TGFVOA) — só p/ display "Pedido: X CX". Magnitude conferida é sempre a padrão. */
    val unidadeComercial: String? = null,
    val unidadePadrao: String? = null,
    val quantidadeComercial: String? = null,
    val quantidadePadrao: String? = null,
    val quantidadeComercialConferida: String? = null,
    val quantidadePadraoConferida: String? = null,
)
