export type StatusInventario = 'aberto' | 'em_contagem' | 'finalizado' | 'ajustado' | 'cancelado';
export type StatusItemInventario = 'pendente' | 'contado' | 'nao_previsto';
export type EscopoTipo = 'local' | 'grupo_produto' | 'produtos_especificos';

export interface InventarioCriado {
  id: string;
  status: StatusInventario;
}

export interface ItemInventario {
  id: string;
  produtoCodigo: string;
  nomeProduto: string;
  controle: string;
  local: string;
  quantidadeSistema: string;
  quantidadeContada: string;
  divergencia: string;
  statusItem: StatusItemInventario;
  canalOrigem: 'coletor' | 'desktop' | null;
}

export interface InventarioListItem {
  id: string;
  descricao: string;
  status: StatusInventario;
  abertoEm: string;
  abertoPorNome: string;
  totalItens: number;
  itensContados: number;
}

export interface InventariosPaginados {
  itens: InventarioListItem[];
  paginaAtual: number;
  totalPaginas: number;
  totalRegistros: number;
}

export interface InventarioDetalhe {
  id: string;
  descricao: string;
  status: StatusInventario;
  escopoTipo: EscopoTipo;
  escopoValores: string[];
  abertoPorNome: string;
  abertoEm: string;
  totalItens: number;
  itensContados: number;
  itens: ItemInventario[];
}

export interface AprovarResponse {
  ok: boolean;
  avisoRecontagem?: string | null;
}

export interface FiltrosInventario {
  status?: StatusInventario;
  busca?: string;
  pagina?: number;
}
