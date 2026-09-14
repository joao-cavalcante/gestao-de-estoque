import { Injectable } from '@angular/core';

const CHAVE_BALANCA_ESTACAO = 'wms_estacao_balanca_id';

/**
 * Config local da estação (por PC/navegador, igual ao `LS_BALANCA` que o
 * oq-scan-bar já usa pra "lembrar" a balança escolhida — aqui é a mesma
 * ideia, só que fixada de propósito pra identificar a estação no login por
 * crachá, ANTES de existir qualquer sessão autenticada).
 *
 * Precisa de 1 login normal nesta estação pra ser configurada (tela
 * Balanças → "Fixar nesta estação") — depois disso o login por crachá não
 * depende mais de autenticação prévia.
 */
@Injectable({ providedIn: 'root' })
export class EstacaoService {
  obterBalancaId(): string | null {
    return localStorage.getItem(CHAVE_BALANCA_ESTACAO);
  }

  definirBalancaId(balancaId: string): void {
    localStorage.setItem(CHAVE_BALANCA_ESTACAO, balancaId);
  }

  limpar(): void {
    localStorage.removeItem(CHAVE_BALANCA_ESTACAO);
  }
}
