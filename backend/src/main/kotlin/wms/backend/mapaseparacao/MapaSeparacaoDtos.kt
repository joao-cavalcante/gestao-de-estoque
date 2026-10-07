package wms.backend.mapaseparacao

import kotlinx.serialization.Serializable
import wms.backend.tarefas.ModalidadePedido

/**
 * Mapa de Separação por Ordem de Carga — porte do componente HTML5/JSP que
 * substituiu o iReport 513 no Sankhya (ver 189_html5Component.zip). Mesma
 * regra de negócio, mesmas tabelas (TGFCAB/TGFORD/TGFVEI/TGFITE/TGFPRO),
 * mesma classificação por TGFPRO.AD_TIPOSEPARACAO — só a apresentação muda
 * (identidade visual do WMS em vez do CSS solto do JSP).
 *
 * Consulta 100% AO VIVO no Sankhya (sem mirror local) — igual a
 * LiberacaoCorteService: relatório de impressão sob demanda, não precisa de
 * sync em background.
 */
/**
 * Quebra do mapa (redução de papel): antes era 1 folha por pedido × categoria.
 * Agora a OC inteira vira:
 * - [consolidado]: seco, congelado (e sem classificação) somados sobre
 *   todos os pedidos da OC — uma folha por categoria, todos os clientes.
 * - [porParceiro]: REFRIGERADO, uma folha por cliente (peso e unidade
 *   juntos). Pesável (RegraPesavel) só marca o item com a balança.
 */
@Serializable
data class MapaSeparacaoDto(
    /** null = mapa S/ ORDEM DE CARGA (um pedido só — ver [nunota]). */
    val ordemCarga: Long?,
    val codVeiculo: Int?,
    val placa: String?,
    val modeloVeiculo: String?,
    /** TGFORD.CODPARCMOTORISTA — motorista é um Parceiro (TGFPAR), não TGFFUN. */
    val codParcMotorista: Int?,
    val nomeMotorista: String?,
    /** TGFORD.PESOMAX — peso máximo da Ordem de Carga. */
    val pesoMaxOc: String?,
    /** TGFORD.DTALTER — data/hora da última alteração da OC ("dd/MM/yyyy HH:mm:ss"); null no mapa S/ OC. */
    val ultimaAlteracaoOc: String? = null,
    /** Nº Único de cada pedido do mapa — o Pedido de Venda de cada um é impresso junto. */
    val nunotasPedidos: List<Long> = emptyList(),
    val totalPedidos: Int,
    val quantidadeTotal: String,
    val pesoTotal: String,
    val consolidado: List<CategoriaSeparacaoDto>,
    val porParceiro: List<ParceiroSeparacaoDto>,
    /** Produtos DISTINTOS do mapa inteiro (OC ou pedido S/ OC) — não é soma de quantidades. */
    val totalItens: Int = 0,
    /** Peso total quebrado por etapa (AD_TIPOSEPARACAO) — mesmo peso que soma o pesoTotal. */
    val pesoPorEtapa: List<PesoEtapaDto> = emptyList(),
    /**
     * Mapa S/ ORDEM DE CARGA: um mapa por Número Único, nunca consolidado com outro pedido.
     * Os campos abaixo só vêm preenchidos nesse modo (identificação do cabeçalho impresso).
     */
    val semOrdemCarga: Boolean = false,
    val nunota: Long? = null,
    val numNota: Long? = null,
    val codParc: Int? = null,
    val nomeParceiro: String? = null,
    /** Mapa S/ OC: modalidade do pedido (AD_EXPRESS / AD_RETIRA / AD_ENTREGA). */
    val modalidade: ModalidadePedido = ModalidadePedido(),
    /** Mapa da OC só com parte dos pedidos ("Imprimir só os pedidos novos"). */
    val somenteAlgunsPedidos: Boolean = false,
)

/** Pedido do painel "S/ Ordem de Carga" (mirror local, mesmo universo da Fila de Tarefas). */
@Serializable
data class PedidoSemOrdemCargaDto(
    val nunota: Long,
    /** Sempre null nesta lista (é o critério dela) — vai no DTO pro badge do card ser o mesmo [OC n] / [S/ OC]. */
    val ordemCarga: Long? = null,
    val numNota: Long?,
    val codParc: Int?,
    val nomeParceiro: String?,
    val dataMovimento: String?,
    /** Conferência já concluída (mesma regra da barra de progresso das OCs). */
    val conferido: Boolean,
    val modalidade: ModalidadePedido = ModalidadePedido(),
    /** Última impressão do mapa deste Nº Único (ISO) e por quem — null = nunca impresso. */
    val impressoEm: String? = null,
    val impressoPor: String? = null,
    /** TGFCAB.PESOBRUTO (KG) do pedido. */
    val pesoKg: Double = 0.0,
)

/** Refrigerados de UM pedido (NUNOTA) da OC — bloco próprio por pedido, com o cliente dele; `nunotas` tem só esse NUNOTA. */
@Serializable
data class ParceiroSeparacaoDto(
    val codParc: Int,
    val nomeParceiro: String,
    val nunotas: List<Long>,
    val quantidadeTotal: String,
    val pesoTotal: String,
    val categorias: List<CategoriaSeparacaoDto>,
    /** Produtos DISTINTOS do pedido inteiro (todas as etapas, não só deste bloco) — rodapé do pedido. */
    val totalItensPedido: Int = 0,
    /** Modalidade do pedido deste bloco (AD_EXPRESS / AD_RETIRA / AD_ENTREGA). */
    val modalidade: ModalidadePedido = ModalidadePedido(),
)

/** Item do painel: OC (aberta ou fechada) com pedido de conferência ainda não concluída (ver MapaSeparacaoService.listarAbertas). */
@Serializable
data class OrdemCargaResumoDto(
    val ordemCarga: Long,
    /** TGFORD.DTPREVSAIDA (dd/MM/yyyy), já formatada — ou "—" se ausente. */
    val dataPrevSaida: String,
    /** TGFORD.DTALTER — última alteração da OC ("dd/MM/yyyy HH:mm:ss"), ou "—". */
    val ultimaAlteracao: String = "—",
    val placa: String?,
    val nomeMotorista: String?,
    /** Total de notas da OC (TGFCAB.ORDEMCARGA) — pra barra de progresso de conferência. */
    val totalNotas: Int,
    /**
     * Quantas dessas notas já estão com status_operacional concluído no mirror LOCAL
     * (app.tarefas, ver TarefaSyncService) — não é uma consulta ao vivo no Sankhya, é o
     * mesmo espelho que já alimenta a Fila de Tarefas. Nota que nunca passou pelo critério
     * de conferência (ver CRITERIO_BASE) não entra no mirror — conta como não conferida.
     */
    val notasConferidas: Int,
    /** TGFORD.SITUACAO: 'A' aberta | 'F' fechada — fechada ainda aparece enquanto tiver nota na fila. */
    val situacao: String? = null,
    /** Quantas notas da OC são Express / Retira / Entrega (mirror local) — pins do card da OC. */
    val qtdExpress: Int = 0,
    val qtdRetira: Int = 0,
    val qtdEntrega: Int = 0,
    /** Última impressão do mapa desta OC (ISO) e por quem — null = nunca impresso. */
    val impressoEm: String? = null,
    val impressoPor: String? = null,
    /** Soma do TGFCAB.PESOBRUTO (KG) de todos os pedidos de saída da OC / só dos ainda não conferidos. */
    val pesoTotalKg: Double = 0.0,
    val pesoPendenteKg: Double = 0.0,
    /** OC já impressa que ganhou pedido DEPOIS da impressão — caso de extrema atenção no painel. */
    val pedidosNovos: List<PedidoNovoOcDto> = emptyList(),
)

/** Pedido que entrou na OC depois do último mapa impresso dela (não saiu em nenhum mapa). */
@Serializable
data class PedidoNovoOcDto(
    val nunota: Long,
    val numNota: Long? = null,
    val nomeParceiro: String? = null,
    val pesoKg: Double = 0.0,
)

@Serializable
data class CategoriaSeparacaoDto(
    /** "1" SECO | "2" REFRIGERADO | "3" CONGELADO | "0" SEM CLASSIFICAÇÃO — ver TGFPRO.AD_TIPOSEPARACAO. */
    val codigo: String,
    val descricao: String,
    val quantidadeTotal: String,
    val pesoTotal: String,
    val itens: List<ItemSeparacaoDto>,
)

@Serializable
data class ItemSeparacaoDto(
    val codProd: Int,
    val descricao: String,
    val controle: String?,
    val unidade: String,
    val quantidade: String,
    val pesoUnitario: String,
    val pesoTotal: String,
    /** Exige pesagem (RegraPesavel: unidade, ou TGFPRO.AD_PESAVEL com o módulo) — o front mostra o ícone de balança. */
    val pesavel: Boolean,
    /** Só quando vendido em unidade alternativa com fator cadastrado (TGFVOA): a mesma qtd na unidade padrão — "5 CX = 60 UN". */
    val quantidadePadrao: String? = null,
    val unidadePadrao: String? = null,
)

@Serializable
data class RegistrarImpressaoRequest(
    val ordensCarga: List<Long> = emptyList(),
    val nunotas: List<Long> = emptyList(),
    /** OC → pedidos que saíram no mapa impresso (chave = nº da OC em texto, JSON). */
    val pedidosPorOc: Map<String, List<Long>> = emptyMap(),
)

@Serializable
data class PesoEtapaDto(
    /** "1" SECO | "2" REFRIGERADO | "3" CONGELADO | "0" SEM CLASSIFICAÇÃO. */
    val codigo: String,
    val descricao: String,
    val pesoTotal: String,
)
