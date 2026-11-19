import { inject } from '@angular/core';
import { CanActivateFn, Router } from '@angular/router';

/**
 * Modo coletor (18/11/2026). A Fila de Tarefas da Torre (/fila-tarefas) continua intacta; em coletor o operador cai
 * na fila própria (/coletor). Coletor = aparelho de TOQUE com tela até 639px (não só a largura: janela estreita de
 * computador continua na Torre). O operador pode escolher "Abrir Torre completa" — vale só para a sessão do
 * navegador (sessionStorage), então ao reabrir o app o coletor volta para a fila dele.
 * /coletor também abre direto em qualquer aparelho (teste no computador), sem mudar a preferência.
 */
const CHAVE_PREFERENCIA = 'wms_modo_aparelho';

export function aparelhoEhColetor(): boolean {
  return typeof window !== 'undefined' && !!window.matchMedia?.('(max-width: 639px) and (pointer: coarse)').matches;
}

export function preferiuTorreNestaSessao(): boolean {
  try {
    return sessionStorage.getItem(CHAVE_PREFERENCIA) === 'torre';
  } catch {
    return false;
  }
}

export function definirPreferenciaTorre(torre: boolean): void {
  try {
    if (torre) sessionStorage.setItem(CHAVE_PREFERENCIA, 'torre');
    else sessionStorage.removeItem(CHAVE_PREFERENCIA);
  } catch {
    /* sem armazenamento: segue pela largura */
  }
}

export function modoColetorAtivo(): boolean {
  return aparelhoEhColetor() && !preferiuTorreNestaSessao();
}

/** Na rota da Fila da Torre: coletor vai para a fila própria; computador/tablet seguem normalmente. */
export const coletorRedirectGuard: CanActivateFn = () => (modoColetorAtivo() ? inject(Router).parseUrl('/coletor') : true);
