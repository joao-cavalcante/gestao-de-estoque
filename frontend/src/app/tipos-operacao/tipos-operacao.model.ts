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
  /** Qtd. de usuários autorizados (V51) — 0 = sem restrição, todos conferem. */
  usuariosAutorizados: number;
  /** TOPs de destino do faturamento (restrição D no Sankhya — RestricaoTop), sincronizadas junto com as TOPs. */
  destinos?: { codtop: number; descricao: string; serie?: string | null }[];
}

export interface SincronizarTipoOperacaoResponse {
  ok: boolean;
  totalAtualizado: number;
}

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
