/** Espelha wms.backend.liberacaocorte.*Dtos. */

export interface ConferenciaAguardandoCorte {
  nunota: number;
  numeroNota: number | null;
  nomeParceiro: string | null;
  descricaoTipoOperacao: string | null;
  dataMovimento: string | null;
  nuconf: number | null;
}

export interface LiberacaoPendente {
  sequencia: number;
  produto: string | null;
  qtdPedido: number | null;
  unidadePedido: string | null;
  qtdConferida: number | null;
  unidadeConferida: string | null;
  diferenca: number | null;
}

export interface ValidarLiberadorParams {
  usuario: string;
  senha: string;
}

export interface LiberarCorteParams {
  nuconf: number;
  usuario: string;
  senha: string;
  liberar: 'S' | 'N';
  sequencias: number[];
  obs?: string;
}

export interface LiberarCorteResposta {
  ok: boolean;
  itensProcessados: number;
}
