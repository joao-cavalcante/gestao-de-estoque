package wms.backend.auditoria

import kotlinx.serialization.Serializable

@Serializable
data class AuditoriaCabecalhoDto(
    val nunota: Long,
    val numnota: Long? = null,
    val codtipoper: Int? = null,
    val top: String? = null,
    val tipmov: String? = null,
    val statusNota: String? = null,
    val ordemCarga: Int? = null,
    val codparc: Int? = null,
    val parceiro: String? = null,
    val codvend: Int? = null,
    val vendedor: String? = null,
    val valor: Double? = null,
    /** DTMOV + HRMOV (hora em que o pedido entrou), ISO local de Brasília. */
    val incluidoEm: String? = null,
    val alteradoEm: String? = null,
    /** Quem fez a última alteração no Sankhya (TGFCAB.CODUSU → TSIUSU). */
    val alteradoPor: String? = null,
    val nuconfAtual: Int? = null,
)

/**
 * Quantidades em duas unidades: a de EXIBIÇÃO (comercial do pedido — FD, CX…; em KG quando a unidade padrão
 * é KG) e a padrão do produto (o Sankhya guarda QTDNEG/QTDCONF nela). Ex.: farinha vendida em FD (FD = 5 PT):
 * 15 FD / 75 PT.
 */
@Serializable
data class AuditoriaItemDto(
    val sequencia: Int,
    val codprod: Int,
    val produto: String,
    val unidade: String? = null,
    val qtdNegociada: Double? = null,
    val qtdConferida: Double? = null,
    /** Unidade padrão (TGFPRO.CODVOL) e as quantidades nela — só preenchidas quando difere da exibida. */
    val unidadePadrao: String? = null,
    val qtdNegociadaPadrao: Double? = null,
    val qtdConferidaPadrao: Double? = null,
    val valorUnitario: Double? = null,
    val valorTotal: Double? = null,
)

@Serializable
data class AuditoriaNotaGeradaDto(
    val nunota: Long,
    val numnota: Long? = null,
    val tipmov: String? = null,
    val top: String? = null,
    val valor: Double? = null,
    val geradaEm: String? = null,
)

/**
 * Um acontecimento da linha do tempo. [quando] = ISO local de Brasília ("2026-10-07T04:42:28") — o
 * Sankhya já grava em Brasília e o banco do WMS (UTC) é convertido na consulta.
 */
@Serializable
data class AuditoriaEventoDto(
    val quando: String,
    /** "WMS" ou "SANKHYA". */
    val origem: String,
    /** pedido | status | impressao | abertura | operador | leitura | etiqueta | etapa | conferencia | liberacao | carregamento | nota | diagnostico */
    val tipo: String,
    val titulo: String,
    val detalhe: String? = null,
    val usuario: String? = null,
    /** info | sucesso | alerta | erro */
    val nivel: String = "info",
)

@Serializable
data class AuditoriaPedidoDto(
    val cabecalho: AuditoriaCabecalhoDto,
    val itens: List<AuditoriaItemDto>,
    val notasGeradas: List<AuditoriaNotaGeradaDto>,
    val eventos: List<AuditoriaEventoDto>,
    /** Partes que falharam ao carregar (o resto da tela aparece mesmo assim). */
    val avisos: List<String> = emptyList(),
)
