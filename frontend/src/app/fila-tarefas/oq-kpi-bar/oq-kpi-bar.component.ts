import { Component, Input } from '@angular/core';

@Component({
  selector: 'oq-kpi-bar',
  standalone: true,
  templateUrl: './oq-kpi-bar.component.html',
  styleUrl: './oq-kpi-bar.component.scss',
})
export class OqKpiBarComponent {
  @Input() filaTotal = 0;
  /** "Fila total" na visão geral; "Pedidos da OC" dentro de uma OC. */
  @Input() rotuloTotal = 'Fila total';
  @Input() conferir = 0;
  @Input() corte = 0;
  @Input() carregar = 0;
  @Input() nota = 0;

  /** Sempre 3 dígitos (008, não 8) — regra explícita da spec. */
  formatarKpi(valor: number): string {
    return valor.toString().padStart(3, '0');
  }
}
