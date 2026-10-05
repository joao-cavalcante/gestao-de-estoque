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
    /** Filtro aplicado: "saida" (vendas V/P) | "entrada" (compras C/O) | "todos". */
    val movimento: String,
    /** Só no modo "todos": os contadores divididos entre saída e entrada. */
    val porMovimento: TvPorMovimentoDto? = null,
    val resumo: TvContadoresDto,
    /** Turno atual (Manhã 08–18, Noite 22–07); fora de turno = contagem desde 00:00. */
    val turno: TvTurnoDto,
    /** Pedidos pendentes (disponível + em conferência + aguardando liberação) por modalidade. */
    val modalidades: TvModalidadesDto,
    val emConferencia: List<TvConferenciaDto>,
    val recemFinalizados: List<TvFinalizadoDto>,
    val porEtapa: List<TvEtapaResumoDto>,
    /** Ordens de carga com pedido não finalizado (só saídas), OC crescente; "sem OC" por último. */
    val ordensCarga: List<TvOrdemCargaDto> = emptyList(),
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
    /** Ordens de carga distintas com pedido ainda não finalizado (disponível, em conferência ou aguardando liberação). */
    val ordensCargaPendentes: Int = 0,
    /** Soma do TGFCAB.PESOBRUTO (KG) desses mesmos pedidos não finalizados. */
    val pesoPendenteKg: Double = 0.0,
    /** Parte do pesoPendenteKg aguardando liberação ou corte. */
    val pesoAguardandoLiberacaoKg: Double = 0.0,
)

/**
 * Uma ordem de carga com pedido ainda não finalizado (ou o grupo "sem OC", ordemCarga = null).
 * Pesos em KG, do TGFCAB.PESOBRUTO de cada pedido.
 */
@Serializable
data class TvOrdemCargaDto(
    val ordemCarga: Long?,
    /** Pedidos da OC no espelho (fila), finalizados ou não. */
    val pedidos: Int,
    val pedidosProntos: Int,
    /** Pedidos da OC em conferência agora. */
    val emConferencia: Int,
    val pesoTotalKg: Double,
    /** Peso dos pedidos não finalizados (inclui os aguardando liberação/corte). */
    val pesoPendenteKg: Double,
    /** Parte do pesoPendenteKg que está aguardando liberação ou corte — mostrada em destaque. */
    val pesoAguardandoLiberacaoKg: Double,
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
    /** "SAIDA" (TIPMOV V/P) | "ENTRADA" (C/O) | null (outro TIPMOV). */
    val movimento: String? = null,
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
    val movimento: String? = null,
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

@Serializable
data class TvPorMovimentoDto(
    val saida: TvMovimentoContadoresDto,
    val entrada: TvMovimentoContadoresDto,
)

@Serializable
data class TvMovimentoContadoresDto(
    val disponivel: Int,
    val emConferencia: Int,
    val aguardandoLiberacao: Int,
    val prontoTurno: Int,
)

/** TV exclusiva de Ordens de Carga (GET /api/tv/carga) — só saídas com OC, banco local + cache de transporte. */
@Serializable
data class TvCargaDto(
    val atualizadoEm: String,
    /** OCs com pedido a conferir ou a carregar. */
    val ocsAbertas: Int,
    /** Peso (KG) dos pedidos ainda não conferidos. */
    val pesoASepararKg: Double,
    /** Parte do peso a separar aguardando liberação/corte. */
    val pesoAguardandoLiberacaoKg: Double,
    /** Pedidos conferidos que ainda têm item a carregar. */
    val pedidosACarregar: Int,
    /** OCs que terminaram conferência e carregamento hoje. */
    val ocsCarregadasHoje: Int,
    val ocs: List<TvOcDto>,
)

@Serializable
data class TvOcDto(
    val ordemCarga: Long,
    val motorista: String? = null,
    val placa: String? = null,
    /** "CONFERINDO" (tem pedido a conferir) | "A_CARREGAR" (tudo conferido, falta carregar). */
    val fase: String,
    val pedidos: Int,
    val pedidosConferidos: Int,
    val pedidosEmConferencia: Int,
    val itensTotal: Int,
    val itensCarregados: Int,
    val pesoTotalKg: Double,
    val pesoASepararKg: Double,
    val pesoAguardandoLiberacaoKg: Double,
)
