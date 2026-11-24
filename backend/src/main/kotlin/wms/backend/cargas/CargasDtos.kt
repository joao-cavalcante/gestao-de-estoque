package wms.backend.cargas

import kotlinx.serialization.Serializable

/** GET /api/cargas — situação das Ordens de Carga (tela Cargas, 24/11/2026). */
@Serializable
data class CargasDto(
    val atualizadoEm: String,
    /** Dia das OCs que saíram (yyyy-MM-dd). */
    val diaSaida: String,
    val cargas: List<CargaDto>,
)

@Serializable
data class CargaDto(
    val ordemCarga: Long,
    /** pendente | pronta | saiu */
    val situacao: String,
    val motorista: String? = null,
    val placa: String? = null,
    val veiculo: String? = null,
    /** TGFORD.DTPREVSAIDA (yyyy-MM-dd). */
    val saidaPrevista: String? = null,
    /** TGFORD.PESOMAX (kg). */
    val pesoMaxKg: Double? = null,
    val pesoKg: Double,
    /** Soma de TGFCAB.QTDVOL (volumes previstos); null quando o espelho não traz o campo. */
    val volumes: Int? = null,
    val clientes: Int,
    /** Pedidos conferidos e carregados. */
    val prontos: Int,
    val express: Boolean,
    val retira: Boolean,
    val faltas: FaltasDto,
    /** Só "saiu": quando o Fechar OC foi feito (ISO) e por quem. */
    val saiuEm: String? = null,
    val fechadaPor: String? = null,
    val impressao: ImpressaoResumoDto? = null,
    val notas: NotasResumoDto,
    val pedidos: List<PedidoCargaDto>,
)

@Serializable
data class FaltasDto(val corte: Int, val aConferir: Int, val emConferencia: Int, val aCarregar: Int)

@Serializable
data class ImpressaoResumoDto(val status: String, val erro: String? = null, val impressoEm: String? = null)

@Serializable
data class NotasResumoDto(val total: Int, val aprovadas: Int, val aguardando: Int, val problema: Int)

@Serializable
data class PedidoCargaDto(
    val nunota: Long,
    val cliente: String? = null,
    val pesoKg: Double,
    val volumes: Int? = null,
    /** corte | a_conferir | em_conferencia | a_carregar | carregado | com_nota */
    val situacao: String,
    val express: Boolean,
    val retira: Boolean,
    val notaNumero: Long? = null,
    val notaSituacao: String? = null,
    val notaStatusNfe: String? = null,
)
