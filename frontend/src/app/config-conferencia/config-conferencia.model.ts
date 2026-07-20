export type StatusCampo = 'nao_aplicavel' | 'nao_implementado' | 'parcial' | 'implementado';
export type TipoControle = 'toggle' | 'select' | 'texto' | 'numero';
export type Aba = 'geral' | 'corte' | 'volumes';

export interface OpcaoCampo {
  valor: string;
  label: string;
}

export interface CampoCatalogo {
  nome: string;
  label: string;
  aba: Aba;
  subSecao: string;
  controle: TipoControle;
  opcoes?: OpcaoCampo[];
  descricao: string;
  status: StatusCampo;
  statusObservacao?: string;
  dependeDe?: string[];
  /** true = habilitado (dependência satisfeita). Sem isso, o campo é sempre considerado habilitado. */
  condicao?: (valores: Record<string, string | null>) => boolean;
}

export interface ConfigConferenciaListItem {
  id: string;
  nucco: number;
  descricao: string;
  sankhyaAtualizadoEm: string | null;
}

export interface ConfigConferenciaDetalhe {
  id: string;
  nucco: number;
  descricao: string;
  campos: Record<string, string | null>;
  sankhyaAtualizadoEm: string | null;
  localAtualizadoEm: string;
}

export interface SincronizarResponse {
  ok: boolean;
  totalSincronizado: number;
}
