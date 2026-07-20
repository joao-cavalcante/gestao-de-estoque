import { Injectable, signal } from '@angular/core';

/**
 * Estado do drawer de navegação, compartilhado entre o botão de toggle
 * (que vive dentro do header de cada tela, ex.: oq-header da Fila de
 * Tarefas) e o próprio drawer (renderizado uma vez, global, em
 * app.component) — os dois não são mais o mesmo componente.
 */
@Injectable({ providedIn: 'root' })
export class NavMenuService {
  readonly aberto = signal(false);

  alternar(): void {
    this.aberto.update((v) => !v);
  }

  abrir(): void {
    this.aberto.set(true);
  }

  fechar(): void {
    this.aberto.set(false);
  }
}
