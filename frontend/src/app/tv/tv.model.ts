/** Contrato de GET /api/tv/resumo (backend wms.backend.tv.TvDtos) — só dados locais do WMS. */
export interface TvResumo {
  atualizadoEm: string;
  limiteParadoMin: number;
  segmentado: boolean;
  /** "saida" (vendas) | "entrada" (compras) | "todos". */
  movimento: string;
  /** Só no modo "todos": contadores divididos entre saída e entrada. */
  porMovimento: { saida: TvMovimentoContadores; entrada: TvMovimentoContadores } | null;
  resumo: {
    disponivel: number;
    emConferencia: number;
    aguardandoLiberacao: number;
    prontoHoje: number;
    tempoMedioHojeMin: number | null;
    prontoTurno: number;
    tempoMedioTurnoMin: number | null;
    /** Ordens de carga distintas com pedido ainda não finalizado. */
    ordensCargaPendentes: number;
    /** Peso bruto (KG) dos pedidos ainda não finalizados. */
    pesoPendenteKg: number;
    /** Parte do pesoPendenteKg aguardando liberação ou corte. */
    pesoAguardandoLiberacaoKg: number;
  };
  /** Turno atual (Manhã 08–18, Noite 22–07); fora de turno = desde 00:00. */
  turno: { codigo: string | null; rotulo: string; inicioEm: string };
  /** Pedidos pendentes por modalidade (Express / Cliente retira / Entrega). */
  modalidades: { express: number; retira: number; entrega: number };
  emConferencia: TvConferencia[];
  recemFinalizados: TvFinalizado[];
  porEtapa: TvEtapaResumo[];
  /** Ordens de carga com pedido não finalizado (só saídas); "sem OC" (ordemCarga null) por último. */
  ordensCarga: TvOrdemCarga[];
}

export interface TvOrdemCarga {
  ordemCarga: number | null;
  pedidos: number;
  pedidosProntos: number;
  emConferencia: number;
  pesoTotalKg: number;
  pesoPendenteKg: number;
  pesoAguardandoLiberacaoKg: number;
}

export interface TvConferencia {
  nunota: number;
  numNota: number | null;
  cliente: string | null;
  ordemCarga: number | null;
  express: boolean;
  retira: boolean;
  entrega: boolean;
  /** "SAIDA" | "ENTRADA" | null. */
  movimento: string | null;
  status: string;
  recontagem: boolean;
  etapaAtual: number | null;
  etapasPendentes: number[];
  conferente: string | null;
  inicioEm: string | null;
  ultimaAtividadeEm: string | null;
  itensConferidos: number | null;
  itensTotal: number | null;
  volumes: number | null;
}

export interface TvFinalizado {
  nunota: number;
  numNota: number | null;
  cliente: string | null;
  concluidoEm: string;
  divergente: boolean;
  recontagem: boolean;
  movimento: string | null;
}

export interface TvMovimentoContadores {
  disponivel: number;
  emConferencia: number;
  aguardandoLiberacao: number;
  prontoTurno: number;
}

export interface TvEtapaResumo {
  tipo: number;
  disponivel: number;
  emConferencia: number;
  prontoHoje: number;
}

/** GET /api/tv/carga — TV exclusiva de Ordens de Carga (backend TvCargaDto). */
export interface TvCarga {
  atualizadoEm: string;
  ocsAbertas: number;
  pesoASepararKg: number;
  pesoAguardandoLiberacaoKg: number;
  pedidosACarregar: number;
  ocsCarregadasHoje: number;
  ocs: TvOc[];
}

export interface TvOc {
  ordemCarga: number;
  motorista: string | null;
  placa: string | null;
  /** "CONFERINDO" | "A_CARREGAR". */
  fase: string;
  pedidos: number;
  pedidosConferidos: number;
  pedidosEmConferencia: number;
  itensTotal: number;
  itensCarregados: number;
  pesoTotalKg: number;
  pesoASepararKg: number;
  pesoAguardandoLiberacaoKg: number;
}
