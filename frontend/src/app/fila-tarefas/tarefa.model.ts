export type StatusTarefa = 'aguardando' | 'andamento' | 'concluido' | 'aguardando_corte';
export type SeveridadeAlerta = 'critico' | 'atencao' | null;

export interface Tarefa {
  id: string;
  cliente: string;
  codigoCliente: string | null;
  status: StatusTarefa;
  alerta: SeveridadeAlerta;
  motivoAlerta?: string;

  pedido: string;
  numeroUnico: string;
  nf: string;
  data: string;
  transporte: string;
  responsavel: string;
  codigoResponsavel: string | null;
  tipoOperacao: string;
  codigoTipoOperacao: string | null;
  ordemCarga: number | null;
  itens: number;
  valor: number;
  /**
   * Conferência por etapa (V29) — presente só quando o tenant tem o módulo
   * `conferencia_segmentada`. Uma entrada por tipo de separação com item na nota.
   */
  etapas?: TarefaEtapa[];
}

export interface TarefaEtapa {
  /** 1 Secos | 2 Resfriados | 3 Congelados. */
  tipo: number;
  status: 'P' | 'C';
  /** Progresso da etapa (itens conferidos / total) — pra "Continuar 3/8" no card. */
  total: number;
  conferidos: number;
}

/** Catálogo dos tipos de separação (TGFPRO.AD_TIPOSEPARACAO) — rótulo + ícone. */
export const TIPOS_SEPARACAO = [
  { id: 1, label: 'Secos', icone: 'seco' as const },
  { id: 2, label: 'Refrigerado', icone: 'refrigerado' as const },
  { id: 3, label: 'Congelado', icone: 'congelado' as const },
];

export function rotuloTipoSeparacao(tipo: number): string {
  return TIPOS_SEPARACAO.find((t) => t.id === tipo)?.label ?? `Tipo ${tipo}`;
}

/** Uma opção de select "cód - descrição" — padrão do oq-searchable-select. */
export interface OpcaoComCodigo {
  codigo: string;
  label: string;
}

/** Filtros avançados — por CÓDIGO (não pelo texto exibido), Cliente/Vendedor/Tipo de Operação. */
export interface FiltrosAvancados {
  codigoParceiro: string | null;
  codigoVendedor: string | null;
  codigoTipoOperacao: string | null;
  /** Texto digitado no campo "Ordem de Carga" (match exato pelo número). */
  ordemCarga: string | null;
}

export const FILTROS_STATUS = ['todos', 'aguardando', 'andamento', 'concluido', 'atencao'] as const;
export type FiltroStatus = (typeof FILTROS_STATUS)[number];
