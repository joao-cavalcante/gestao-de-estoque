export type StatusTarefa = 'aguardando' | 'andamento' | 'concluido' | 'aguardando_corte';
export type SeveridadeAlerta = 'critico' | 'atencao' | null;

export interface Tarefa {
  id: string;
  cliente: string;
  codigoCliente: string | null;
  status: StatusTarefa;
  /**
   * Status granular original do backend (TarefaApiDto.statusOperacional,
   * ver ConferenciasService) — `status` acima agrupa em 4 buckets pra
   * filtro/KPI/ícone, mas o card usa este campo quando precisa de um rótulo
   * mais fino (ex.: "aguardando_recontagem" vs "aguardando" comum, que caem
   * no mesmo bucket 'aguardando' mas significam coisas bem diferentes pro
   * operador).
   */
  statusOperacional?: string;
  alerta: SeveridadeAlerta;
  motivoAlerta?: string;

  pedido: string;
  numeroUnico: string;
  nf: string;
  data: string;
  /** Veículo da Ordem de Carga — "PLACA · MODELO" ou "—". */
  transporte: string;
  /** Motorista da Ordem de Carga (null sem OC). */
  motorista: string | null;
  responsavel: string;
  codigoResponsavel: string | null;
  tipoOperacao: string;
  codigoTipoOperacao: string | null;
  ordemCarga: number | null;
  itens: number;
  valor: number;
  /** TGFCAB.AD_TURNOENTREGA já traduzido — "Diurno" | "Noturno" | "Qualquer" | "—". */
  periodoEntrega: string;
  /** Modalidade do pedido — TGFCAB.AD_EXPRESS / AD_RETIRA / AD_ENTREGA = 'S'. */
  express: boolean;
  retira: boolean;
  entrega: boolean;
  /**
   * Conferência por etapa (V29) — presente só quando o tenant tem o módulo
   * `conferencia_segmentada`. Uma entrada por tipo de separação com item na nota.
   */
  etapas?: TarefaEtapa[];
  /** Carregamento (checklist do "Ver conferidos") — só nota conferida com OC. */
  carregamento?: CarregamentoTarefa;
}

export interface CarregamentoTarefa {
  /** Itens conferidos (produto+controle) e quantos já foram checados como carregados. */
  total: number;
  carregados: number;
  /** Sessão a abrir no checklist (a que ainda tem item a carregar). */
  sessaoId: string | null;
}

/** Conferida, com OC e com item ainda não carregado — vira "A CARREGAR" na fila filtrada por OC. */
export function aCarregar(t: Tarefa): boolean {
  return t.status === 'concluido' && t.ordemCarga != null && !!t.carregamento && t.carregamento.carregados < t.carregamento.total;
}

export interface TarefaEtapa {
  /** 1 Secos | 2 Resfriados | 3 Congelados. */
  tipo: number;
  status: 'P' | 'C';
  /** Concluída COM divergência — chip vermelho no card. */
  divergente?: boolean;
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
  /** Vínculo com Ordem de Carga (TGFCAB.ORDEMCARGA; nulo/0 = sem): todos | só com OC | só sem OC. */
  vinculoOrdemCarga: VinculoOrdemCarga;
}

export type VinculoOrdemCarga = 'todos' | 'com' | 'sem';

export const FILTROS_STATUS = ['todos', 'aguardando', 'andamento', 'aguardando_corte'] as const;
export type FiltroStatus = (typeof FILTROS_STATUS)[number];

/** Modo de exibição — compartilhado com as outras telas de fila. */
export type { ViewMode } from '../shared/lista-layout/view-mode';

/** Colunas ordenáveis (cabeçalho da lista) — a ordenação vale pros dois modos. */
export type CampoOrdenacao = 'cliente' | 'numeroUnico' | 'nf' | 'data' | 'itens';

export interface Ordenacao {
  campo: CampoOrdenacao;
  direcao: 'asc' | 'desc';
}
