import { Component, EventEmitter, Input, Output } from '@angular/core';
import { FormsModule } from '@angular/forms';

/**
 * Rodapé de paginação das telas de fila (Itens/Pág · 1–20 DE 31 · ‹ 1 2 3 ›) — o da
 * Fila de Tarefas, compartilhado. Só apresenta: quem usa guarda página/itens e fatia
 * (em memória ou no servidor). `pagina` é 1-based.
 */
@Component({
  selector: 'oq-paginacao',
  standalone: true,
  imports: [FormsModule],
  host: { style: 'display: contents;' },
  template: `
    <footer class="oq-pagination">
      <label class="oq-pagination__per-page">
        Itens por página
        <select [ngModel]="itensPorPagina" (ngModelChange)="itensPorPaginaChange.emit($event)">
          @for (op of opcoes; track op) {
            <option [ngValue]="op">{{ op }}</option>
          }
        </select>
      </label>

      <span class="oq-pagination__range">
        <span class="oq-pagination__range-num">{{ inicio }}–{{ fim }}</span>
        de
        <span class="oq-pagination__range-num">{{ total }}</span>
      </span>

      <div class="oq-pagination__pages">
        <button type="button" (click)="ir(pagina - 1)" [disabled]="pagina <= 1" aria-label="Página anterior">‹</button>
        @for (p of paginas; track $index) {
          @if (p === null) {
            <span class="oq-pagination__reticencias" aria-hidden="true">…</span>
          } @else {
            <button
              type="button"
              class="oq-pagination__page"
              [class.oq-pagination__page--active]="pagina === p"
              [attr.aria-current]="pagina === p ? 'page' : null"
              (click)="ir(p)"
            >
              {{ p }}
            </button>
          }
        }
        <button type="button" (click)="ir(pagina + 1)" [disabled]="pagina >= totalPaginas" aria-label="Próxima página">›</button>
      </div>
    </footer>
  `,
})
export class OqPaginacaoComponent {
  @Input({ required: true }) total = 0;
  @Input({ required: true }) pagina = 1;
  @Input({ required: true }) itensPorPagina = 20;
  @Input() opcoes: number[] = [10, 20, 50];

  @Output() paginaChange = new EventEmitter<number>();
  @Output() itensPorPaginaChange = new EventEmitter<number>();

  get totalPaginas(): number {
    return Math.max(1, Math.ceil(this.total / this.itensPorPagina));
  }

  /**
   * Janela de páginas: primeira, última e duas de cada lado da atual; o resto vira "…" (null).
   * Com 31 páginas a lista inteira estourava a largura no celular e no tablet.
   */
  get paginas(): (number | null)[] {
    const total = this.totalPaginas;
    if (total <= 7) return Array.from({ length: total }, (_, i) => i + 1);
    const ini = Math.max(2, Math.min(this.pagina - 2, total - 5));
    const fim = Math.min(total - 1, Math.max(this.pagina + 2, 6));
    const meio = Array.from({ length: fim - ini + 1 }, (_, i) => ini + i);
    return [1, ini > 2 ? null : 2, ...meio.filter((p) => p !== 2 || ini <= 2), fim < total - 1 ? null : total - 1, total]
      .filter((p, i, a) => p === null || a.indexOf(p) === i);
  }

  get inicio(): number {
    return this.total === 0 ? 0 : (this.pagina - 1) * this.itensPorPagina + 1;
  }

  get fim(): number {
    return Math.min(this.pagina * this.itensPorPagina, this.total);
  }

  ir(p: number): void {
    const alvo = Math.min(Math.max(1, p), this.totalPaginas);
    if (alvo !== this.pagina) this.paginaChange.emit(alvo);
  }
}
