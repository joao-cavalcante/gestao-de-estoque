import { Injectable, signal } from '@angular/core';

export type Tema = 'light' | 'dark';

const CHAVE_TEMA = 'wms_tema';

/**
 * Tema é uma preferência global do usuário, um único ponto de controle
 * (atributo `data-theme` na tag <html>) — nunca uma característica fixa de
 * uma tela. Nenhum componente deve setar `data-theme` por conta própria.
 *
 * Ordem de resolução do valor inicial: preferência salva > prefers-color-scheme
 * do SO > 'light'. Enquanto o usuário não escolher manualmente, a troca de
 * prefers-color-scheme do SO em tempo real continua sendo seguida (ver
 * escutaMudancaDoSistema).
 */
@Injectable({ providedIn: 'root' })
export class ThemeService {
  readonly tema = signal<Tema>(lerTemaInicial());

  constructor() {
    aplicarNoDocumento(this.tema());
    this.escutaMudancaDoSistema();
  }

  definir(tema: Tema): void {
    this.tema.set(tema);
    localStorage.setItem(CHAVE_TEMA, tema);
    aplicarNoDocumento(tema);
  }

  alternar(): void {
    this.definir(this.tema() === 'dark' ? 'light' : 'dark');
  }

  private escutaMudancaDoSistema(): void {
    if (localStorage.getItem(CHAVE_TEMA)) return; // usuário já escolheu — SO não sobrescreve mais
    const mql = window.matchMedia('(prefers-color-scheme: dark)');
    mql.addEventListener('change', (ev) => {
      if (localStorage.getItem(CHAVE_TEMA)) return;
      const tema: Tema = ev.matches ? 'dark' : 'light';
      this.tema.set(tema);
      aplicarNoDocumento(tema);
    });
  }
}

function lerTemaInicial(): Tema {
  const salvo = localStorage.getItem(CHAVE_TEMA);
  if (salvo === 'light' || salvo === 'dark') return salvo;
  return window.matchMedia('(prefers-color-scheme: dark)').matches ? 'dark' : 'light';
}

function aplicarNoDocumento(tema: Tema): void {
  document.documentElement.setAttribute('data-theme', tema);
}
