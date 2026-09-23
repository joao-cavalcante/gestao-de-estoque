package wms.backend.mapaseparacao

import kotlinx.serialization.Serializable

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
 * - [consolidado]: seco/refrigerado/sem classificação NÃO pesáveis somados
 *   sobre todos os pedidos da OC, 1 bloco por categoria — não precisam de
 *   segregação por cliente.
 * - [porParceiro]: pesáveis (TGFVOL.UTILICONFPESO, mesma regra da
 *   conferência) e todo CONGELADO continuam separados por parceiro.
 */
@Serializable
data class MapaSeparacaoDto(
    val ordemCarga: Long,
    val codVeiculo: Int?,
    val placa: String?,
    val modeloVeiculo: String?,
    /** TGFORD.CODPARCMOTORISTA — motorista é um Parceiro (TGFPAR), não TGFFUN. */
    val codParcMotorista: Int?,
    val nomeMotorista: String?,
    /** TGFORD.PESOMAX — peso máximo da Ordem de Carga. */
    val pesoMaxOc: String?,
    val totalPedidos: Int,
    val quantidadeTotal: String,
    val pesoTotal: String,
    val consolidado: List<CategoriaSeparacaoDto>,
    val porParceiro: List<ParceiroSeparacaoDto>,
)

/** Pesáveis + congelados de UM parceiro na OC (somados entre os pedidos dele, nunca entre parceiros). */
@Serializable
data class ParceiroSeparacaoDto(
    val codParc: Int,
    val nomeParceiro: String,
    val nunotas: List<Long>,
    val quantidadeTotal: String,
    val pesoTotal: String,
    val categorias: List<CategoriaSeparacaoDto>,
)

/** Item da lista de Ordens de Carga ABERTAS (TGFORD.SITUACAO='A') pra seleção na tela — ainda precisam ser separadas. */
@Serializable
data class OrdemCargaResumoDto(
    val ordemCarga: Long,
    /** TGFORD.DTPREVSAIDA (dd/MM/yyyy), já formatada — ou "—" se ausente. */
    val dataPrevSaida: String,
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
    /** Exige pesagem (TGFVOL.UTILICONFPESO) — o front mostra o ícone de balança. */
    val pesavel: Boolean,
)
