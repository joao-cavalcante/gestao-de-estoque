import { Component, Input } from '@angular/core';

@Component({
  selector: 'oq-kpi-bar',
  standalone: true,
  templateUrl: './oq-kpi-bar.component.html',
  styleUrl: './oq-kpi-bar.component.scss',
})
export class OqKpiBarComponent {
  @Input() filaTotal = 0;
  @Input() aguardando = 0;
  @Input() andamento = 0;
  @Input() concluido = 0;
  @Input() atencao = 0;

  /** Sempre 3 dígitos (008, não 8) — regra explícita da spec. */
  formatarKpi(valor: number): string {
    return valor.toString().padStart(3, '0');
  }
}
