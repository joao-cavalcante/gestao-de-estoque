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
  };
  /** Turno atual (Manhã 08–18, Noite 22–07); fora de turno = desde 00:00. */
  turno: { codigo: string | null; rotulo: string; inicioEm: string };
  /** Pedidos pendentes por modalidade (Express / Cliente retira / Entrega). */
  modalidades: { express: number; retira: number; entrega: number };
  emConferencia: TvConferencia[];
  recemFinalizados: TvFinalizado[];
  porEtapa: TvEtapaResumo[];
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
