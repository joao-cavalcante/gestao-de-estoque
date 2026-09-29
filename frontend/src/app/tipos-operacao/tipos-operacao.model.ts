/**
 * Espelho local dos Tipos de Operação — ver db/migrations/V16, V17 e V49. Derivado
 * do que a Fila de Tarefas já sincroniza (não de uma consulta própria ao
 * Sankhya — a entidade TipoOperacao isolada é incompleta).
 */
export interface TipoOperacao {
  id: string;
  codtop: number;
  descricao: string;
  nucco: number | null;
  /** TGFCAB.TIPMOV — 'C'/'O' compra, 'V'/'P' venda (null = ainda não sincronizado). */
  tipmov: string | null;
  /** Conferência por etapa (Secos/Refrigerado/Congelado) pras notas deste TOP — V49. */
  conferenciaPorEtapa: boolean;
  localAtualizadoEm: string;
}

export interface SincronizarTipoOperacaoResponse {
  ok: boolean;
  totalAtualizado: number;
}

/** Filtro da tela por TIPMOV: Compras = C (compra) e O (pedido de compra); Vendas = V (venda) e P (pedido de venda). */
export type FiltroTipmov = 'todos' | 'compras' | 'vendas';

export const TIPMOV_COMPRAS = ['C', 'O'];
export const TIPMOV_VENDAS = ['V', 'P'];

const ROTULO_TIPMOV: Record<string, string> = {
  C: 'Compra',
  O: 'Pedido de compra',
  V: 'Venda',
  P: 'Pedido de venda',
};

export function rotuloTipmov(tipmov: string | null): string {
  if (!tipmov) return 'TIPMOV —';
  return ROTULO_TIPMOV[tipmov] ?? `TIPMOV ${tipmov}`;
}
