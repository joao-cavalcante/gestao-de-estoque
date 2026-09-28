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
        Itens / Pág
        <select [ngModel]="itensPorPagina" (ngModelChange)="itensPorPaginaChange.emit($event)">
          @for (op of opcoes; track op) {
            <option [ngValue]="op">{{ op }}</option>
          }
        </select>
      </label>

      <span class="oq-pagination__range">
        <span class="oq-pagination__range-num">{{ inicio }}–{{ fim }}</span>
        DE
        <span class="oq-pagination__range-num">{{ total }}</span>
      </span>

      <div class="oq-pagination__pages">
        <button type="button" (click)="ir(pagina - 1)" [disabled]="pagina <= 1" aria-label="Página anterior">‹</button>
        @for (p of paginas; track p) {
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

  get paginas(): number[] {
    return Array.from({ length: this.totalPaginas }, (_, i) => i + 1);
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
