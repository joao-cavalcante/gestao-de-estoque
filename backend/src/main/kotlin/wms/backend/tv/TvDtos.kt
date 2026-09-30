package wms.backend.tv

import kotlinx.serialization.Serializable

/**
 * GET /api/tv/resumo — TV de acompanhamento da conferência. Só dados LOCAIS do WMS (nunca Sankhya).
 * Agrupamentos são só visuais sobre StatusOperacional (ver TvService) — nenhum estado novo.
 * Horários em ISO-8601 (UTC); o front calcula "há X min" e o PARADO a cada segundo.
 */
@Serializable
data class TvResumoDto(
    val atualizadoEm: String,
    /** Minutos sem bipe/atividade pra conferência aparecer como PARADO (indicador só visual). */
    val limiteParadoMin: Int,
    /** Tenant com conferência por etapa (faixa Secos/Refrigerado/Congelado). */
    val segmentado: Boolean,
    val resumo: TvContadoresDto,
    /** Turno atual (Manhã 08–18, Noite 22–07); fora de turno = contagem desde 00:00. */
    val turno: TvTurnoDto,
    /** Pedidos pendentes (disponível + em conferência + aguardando liberação) por modalidade. */
    val modalidades: TvModalidadesDto,
    val emConferencia: List<TvConferenciaDto>,
    val recemFinalizados: List<TvFinalizadoDto>,
    val porEtapa: List<TvEtapaResumoDto>,
)

@Serializable
data class TvContadoresDto(
    /** aguardando + aguardando_recontagem. */
    val disponivel: Int,
    /** andamento + recontagem_andamento + aguardando_finalizacao. */
    val emConferencia: Int,
    /** aguardando_corte + aguardando_liberacao. */
    val aguardandoLiberacao: Int,
    /** concluido / concluido_divergente / recontagem_concluida(_divergente) com conclusão desde 00:00. */
    val prontoHoje: Int,
    /** Média (min) conclusão − início, só conferências feitas pelo WMS e concluídas hoje. null = sem dados. */
    val tempoMedioHojeMin: Int?,
    /** Mesmo critério do prontoHoje, contado desde o início do turno atual. */
    val prontoTurno: Int = 0,
    /** Mesmo critério do tempoMedioHojeMin, só conclusões do turno atual. */
    val tempoMedioTurnoMin: Int? = null,
)

@Serializable
data class TvTurnoDto(
    /** MANHA | NOITE | null (fora de turno). */
    val codigo: String?,
    /** "Manhã · 08:00–18:00" / "Noite · 22:00–07:00" / "Fora de turno". */
    val rotulo: String,
    /** Início da janela de contagem (turno, ou 00:00 fora de turno) — ISO. */
    val inicioEm: String,
)

@Serializable
data class TvModalidadesDto(
    val express: Int,
    val retira: Int,
    val entrega: Int,
)

@Serializable
data class TvConferenciaDto(
    val nunota: Long,
    val numNota: Long?,
    val cliente: String?,
    val ordemCarga: Long?,
    val express: Boolean,
    val retira: Boolean,
    val entrega: Boolean,
    /** Status real da nota (StatusOperacional.codigo). */
    val status: String,
    val recontagem: Boolean,
    /** Etapa com operador trabalhando agora (1 Secos | 2 Refrigerado | 3 Congelado); null = sem etapa/sem lock. */
    val etapaAtual: Int?,
    /** Etapas ainda pendentes na sessão (conferência por etapa). */
    val etapasPendentes: List<Int>,
    val conferente: String?,
    /** separacao_sessoes.criado_em — null = aberta direto no Sankhya (sem início confiável). */
    val inicioEm: String?,
    /** Mais recente entre o último bipe e a atividade do lock — base do PARADO. */
    val ultimaAtividadeEm: String?,
    /** Linhas de item conferidas / total (da etapa atual, ou da nota inteira). null = sem sessão local. */
    val itensConferidos: Int?,
    val itensTotal: Int?,
    val volumes: Int?,
)

@Serializable
data class TvFinalizadoDto(
    val nunota: Long,
    val numNota: Long?,
    val cliente: String?,
    val concluidoEm: String,
    val divergente: Boolean,
    val recontagem: Boolean,
)

@Serializable
data class TvEtapaResumoDto(
    /** 1 Secos | 2 Refrigerado | 3 Congelado. */
    val tipo: Int,
    /** Etapas pendentes em conferências abertas, sem ninguém trabalhando nelas agora. */
    val disponivel: Int,
    /** Etapas pendentes com operador trabalhando (lock ativo). */
    val emConferencia: Int,
    /** Etapas concluídas desde 00:00. */
    val prontoHoje: Int,
)
