export type StatusTarefa = 'aguardando' | 'andamento' | 'concluido';
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
  itens: number;
  valor: number;
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
}

export const FILTROS_STATUS = ['todos', 'aguardando', 'andamento', 'concluido', 'atencao'] as const;
export type FiltroStatus = (typeof FILTROS_STATUS)[number];
