import { Component, Input } from '@angular/core';

/**
 * Faixa de indicadores do Mapa de Separação — mesmo visual da oq-kpi-bar da Fila de Conferência
 * (mono, 3 dígitos, fundo da faixa de KPI). As 3 contagens de OC (não impressas + impressas + com pedido
 * complementar) somam o total de OCs do painel; o bloco de complementares fica vermelho e pulsando quando > 0.
 */
@Component({
  selector: 'oq-mapa-kpis',
  standalone: true,
  template: `
    <div class="oq-kpi-bar">
      <div class="oq-kpi-bar__block">
        <span class="oq-kpi-bar__label">OCs não impressas</span>
        <span class="oq-kpi-bar__value">{{ tres(aSeparar) }}</span>
      </div>
      <div class="oq-kpi-bar__block">
        <span class="oq-kpi-bar__label">OCs impressas</span>
        <span class="oq-kpi-bar__value">{{ tres(impressas) }}</span>
      </div>
      <div class="oq-kpi-bar__block" [class.oq-kpi-bar__block--alerta]="ocsComPedidoNovo > 0" [class.oq-alert-pulse]="ocsComPedidoNovo > 0">
        <span class="oq-kpi-bar__label">OCs com pedido complementar</span>
        <span class="oq-kpi-bar__value">{{ tres(ocsComPedidoNovo) }}</span>
      </div>
      <div class="oq-kpi-bar__block">
        <span class="oq-kpi-bar__label">Peso total a separar</span>
        <span class="oq-kpi-bar__value">{{ toneladas(kgASeparar) }}</span>
      </div>
      <div class="oq-kpi-bar__block">
        <span class="oq-kpi-bar__label">Express / Retira s/ OC</span>
        <span class="oq-kpi-bar__value">{{ tres(urgentes) }}</span>
      </div>
    </div>
  `,
  styles: `
    .oq-kpi-bar {
      display: flex;
      flex-wrap: wrap;
      align-items: stretch;
      background: var(--oq-kpi-strip-bg);
      border-top: 1px solid var(--oq-border);
    }
    .oq-kpi-bar__block {
      display: flex;
      flex: 1;
      flex-direction: column;
      justify-content: center;
      gap: 2px;
      padding: 10px 20px;
      border-right: 1px solid var(--oq-border);
    }
    .oq-kpi-bar__block:last-child { border-right: none; }
    .oq-kpi-bar__label {
      font-family: var(--oq-font-mono);
      font-size: 10px;
      text-transform: uppercase;
      letter-spacing: 0.1em;
      color: var(--oq-text-secondary);
    }
    .oq-kpi-bar__value {
      font-family: var(--oq-font-mono);
      font-weight: 600;
      font-size: 26px;
      line-height: 1;
      font-variant-numeric: tabular-nums;
      color: var(--oq-text-primary);
    }
    .oq-kpi-bar__block--alerta { background: var(--oq-critical-soft); }
    .oq-kpi-bar__block--alerta .oq-kpi-bar__label,
    .oq-kpi-bar__block--alerta .oq-kpi-bar__value { color: var(--oq-critical-foreground); }
    @media (max-width: 700px) {
      .oq-kpi-bar__block { flex: 1 1 33%; }
    }
  `,
})
export class OqMapaKpisComponent {
  @Input() aSeparar = 0;
  @Input() kgASeparar = 0;
  @Input() impressas = 0;
  @Input() urgentes = 0;
  @Input() ocsComPedidoNovo = 0;

  /** Sempre 3 dígitos, igual à Fila (008, não 8). */
  tres(v: number): string {
    return v.toString().padStart(3, '0');
  }

  toneladas(kg: number): string {
    return (kg / 1000).toLocaleString('pt-BR', { minimumFractionDigits: 1, maximumFractionDigits: 1 }) + ' t';
  }
}
