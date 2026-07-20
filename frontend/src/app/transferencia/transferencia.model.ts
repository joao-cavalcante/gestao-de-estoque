export type ModoItem = 'unit' | 'labelqty' | 'bulk';

/** Espelha ProdutoEstoqueDto do backend (mesmos nomes de campo — sem mapeamento). */
export interface ProdutoEstoque {
  codigo: string;
  nome: string;
  ctrl: string;
  modo: ModoItem;
  qtdEtiqueta: string | null;
  unidade: string;
}

/** Espelha ItemTransferenciaDto do backend. */
export interface ItemTransferencia {
  id: string;
  codigoProduto: string;
  nomeProduto: string;
  controle: string;
  quantidade: string;
  unidade: string;
}

export interface ModeloNota {
  codtop: number;
  codemp: number;
  codnat: number;
  ativo: boolean;
}

export interface ResultadoValidacaoLocal {
  ok: boolean;
  codigo: string;
  erro?: string;
}

export interface ResultadoIdentificacaoItem {
  ok: boolean;
  produto?: ProdutoEstoque;
  erro?: string;
}

export interface TransferenciaCriada {
  id: string;
  origem: string;
  destino: string;
  status: string;
}

export type CanalOrigem = 'coletor' | 'desktop';

export type StatusTransferencia = 'aberta' | 'confirmada' | 'cancelada' | 'abandonada';

export interface TransferenciaListItem {
  id: string;
  criadoEm: string;
  origem: string;
  destino: string;
  totalItens: number;
  totalUnidades: string;
  canalOrigem: CanalOrigem;
  status: StatusTransferencia;
  operadorNome: string;
}

export interface TransferenciasPaginadas {
  itens: TransferenciaListItem[];
  paginaAtual: number;
  totalPaginas: number;
  totalRegistros: number;
}

export interface FiltrosTransferencia {
  canal?: CanalOrigem;
  status?: StatusTransferencia;
  busca?: string;
  pagina?: number;
}
