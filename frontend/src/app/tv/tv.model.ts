/** Contrato de GET /api/tv/resumo (backend wms.backend.tv.TvDtos) — só dados locais do WMS. */
export interface TvResumo {
  atualizadoEm: string;
  limiteParadoMin: number;
  segmentado: boolean;
  resumo: {
    disponivel: number;
    emConferencia: number;
    aguardandoLiberacao: number;
    prontoHoje: number;
    tempoMedioHojeMin: number | null;
  };
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
}

export interface TvEtapaResumo {
  tipo: number;
  disponivel: number;
  emConferencia: number;
  prontoHoje: number;
}
