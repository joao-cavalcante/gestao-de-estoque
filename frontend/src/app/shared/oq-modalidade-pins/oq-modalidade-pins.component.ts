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
        gap: 4px 10px;
      }
      :host:empty {
        display: none;
      }
      /* 17/11/2026: sem caixinha em caixa alta ("cara de IA") — ícone + nome em texto normal. Express é urgência
         (vermelho, negrito); Retira em roxo; Entrega é o caso comum (cinza). */
      .mp-pin {
        display: inline-flex;
        align-items: center;
        gap: 4px;
        font-family: var(--oq-font-body);
        font-size: 12.5px;
        font-weight: 500;
        line-height: 18px;
        white-space: nowrap;
      }
      .mp-pin--compacto {
        padding: 0;
      }
      /* Cor no próprio pino (usuário, 17/11/2026: "por que não colocar a cor no pin?"): etiqueta de fundo leve,
         texto normal. Express = vermelho (urgência), Retira = âmbar (atenção); Entrega fica só texto. */
      .mp-pin--express,
      .mp-pin--retira {
        padding: 1px 7px 1px 6px;
        border-radius: var(--oq-radius-input);
        font-weight: 700;
      }
      .mp-pin--express {
        color: var(--oq-critical-foreground);
        background: color-mix(in srgb, var(--oq-critical) 14%, transparent);
      }
      .mp-pin--retira {
        color: var(--oq-warning-foreground);
        background: color-mix(in srgb, var(--oq-attention) 18%, transparent);
      }
      .mp-pin--compacto.mp-pin--express,
      .mp-pin--compacto.mp-pin--retira {
        padding: 2px 3px;
      }
      .mp-pin--entrega {
        color: var(--oq-text-secondary);
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
      { chave: 'retira', icone: 'retira', label: 'Cliente retira', qtd: c ? c.retira : null },
      { chave: 'entrega', icone: 'entrega', label: 'Entrega', qtd: c ? c.entrega : null },
    ];
    return lista.filter((p) => (c ? (p.qtd ?? 0) > 0 : !!m?.[p.chave]));
  }

  titulo(p: Pin): string {
    return p.qtd != null ? `${p.label}: ${p.qtd} nota(s)` : p.label;
  }
}
