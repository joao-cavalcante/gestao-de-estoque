import { Component, Input } from '@angular/core';
import { OqIconComponent, OqIconName } from '../icons/oq-icon.component';

export interface Modalidade {
  express?: boolean;
  retira?: boolean;
  entrega?: boolean;
}

interface Pin {
  chave: 'express' | 'retira' | 'entrega';
  icone: OqIconName;
  label: string;
  qtd: number | null;
}

/**
 * Pins da modalidade do pedido — TGFCAB.AD_EXPRESS / AD_RETIRA / AD_ENTREGA = 'S'. Usado no card e na
 * lista da Fila de Conferência e no Mapa de Separação. `compacto` = só ícone (tooltip com o nome).
 * `contagem` = OC do Mapa: quantas notas de cada modalidade (0 = pin não aparece).
 */
@Component({
  selector: 'oq-modalidade-pins',
  standalone: true,
  imports: [OqIconComponent],
  template: `
    @for (p of pins; track p.chave) {
      <span class="mp-pin" [class]="'mp-pin mp-pin--' + p.chave" [class.mp-pin--compacto]="compacto" [title]="titulo(p)">
        <oq-icon [name]="p.icone" [size]="compacto ? 13 : 11" />
        @if (!compacto) {
          {{ p.label }}@if (p.qtd != null) { <span class="mp-pin__qtd">{{ p.qtd }}</span> }
        }
      </span>
    }
  `,
  styles: [
    `
      :host {
        display: inline-flex;
        flex-wrap: wrap;
        align-items: center;
        gap: 4px;
      }
      :host:empty {
        display: none;
      }
      .mp-pin {
        display: inline-flex;
        align-items: center;
        gap: 4px;
        padding: 1px 6px;
        border: 1px solid currentColor;
        border-radius: var(--oq-radius-input);
        background: var(--oq-surface);
        font-family: var(--oq-font-display);
        font-size: 10px;
        font-weight: 700;
        letter-spacing: 0.05em;
        text-transform: uppercase;
        line-height: 16px;
        white-space: nowrap;
      }
      .mp-pin--compacto {
        padding: 0;
        border: none;
        background: none;
      }
      .mp-pin--express {
        color: var(--oq-critical-foreground);
      }
      .mp-pin--retira {
        color: var(--oq-brand-accent);
      }
      .mp-pin--entrega {
        color: var(--oq-success-foreground);
      }
      .mp-pin__qtd {
        margin-left: 2px;
        font-family: var(--oq-font-mono);
      }
    `,
  ],
})
export class OqModalidadePinsComponent {
  @Input() modalidade: Modalidade | null | undefined = null;
  /** OC do Mapa: quantidade de notas por modalidade. */
  @Input() contagem: { express: number; retira: number; entrega: number } | null = null;
  @Input() compacto = false;

  get pins(): Pin[] {
    const c = this.contagem;
    const m = this.modalidade;
    const lista: Pin[] = [
      { chave: 'express', icone: 'express', label: 'Express', qtd: c ? c.express : null },
      { chave: 'retira', icone: 'retira', label: 'Retira', qtd: c ? c.retira : null },
      { chave: 'entrega', icone: 'entrega', label: 'Entrega', qtd: c ? c.entrega : null },
    ];
    return lista.filter((p) => (c ? (p.qtd ?? 0) > 0 : !!m?.[p.chave]));
  }

  titulo(p: Pin): string {
    return p.qtd != null ? `${p.label}: ${p.qtd} nota(s)` : p.label;
  }
}
