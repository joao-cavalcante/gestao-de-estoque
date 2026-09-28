/** Modo de exibição das telas de fila — cards (grid) ou lista (tabela). */
export type ViewMode = 'cards' | 'list';

/**
 * Preferência do navegador por tela (localStorage `<tela>-view-mode`, ex.: "fila-view-mode").
 * Storage bloqueado (aba privada/política) ou valor estranho → padrão "cards".
 */
export function lerViewMode(chave: string): ViewMode {
  try {
    return localStorage.getItem(chave) === 'list' ? 'list' : 'cards';
  } catch {
    return 'cards';
  }
}

export function salvarViewMode(chave: string, modo: ViewMode): void {
  try {
    localStorage.setItem(chave, modo);
  } catch {
    /* sem storage: vale só enquanto a tela estiver aberta */
  }
}

/** Opções de itens/página por modo — a lista cabe mais linhas por tela que o grid de cards. */
export const ITENS_POR_PAGINA: Record<ViewMode, number[]> = { cards: [10, 20, 50], list: [20, 50, 100] };

/** Mantém o valor se o modo oferece; senão o mais próximo (10 → 20 na lista, 100 → 50 nos cards). */
export function itensValidosPara(modo: ViewMode, valor: number): number {
  const opcoes = ITENS_POR_PAGINA[modo];
  if (opcoes.includes(valor)) return valor;
  return opcoes.reduce((melhor, o) => (Math.abs(o - valor) < Math.abs(melhor - valor) ? o : melhor), opcoes[0]);
}
