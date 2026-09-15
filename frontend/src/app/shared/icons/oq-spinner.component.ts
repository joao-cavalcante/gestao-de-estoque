import { Component, Input } from '@angular/core';

/**
 * Spinner de carregamento — anel parcial girando, currentColor (o pai
 * controla a cor via CSS `color`). Usa a keyframe global `oq-spin`
 * (styles.scss), respeitando `prefers-reduced-motion` como o resto do app.
 */
@Component({
  selector: 'oq-spinner',
  standalone: true,
  host: { style: 'display: inline-flex;', role: 'status', 'aria-label': 'Carregando' },
  template: `
    <svg [attr.width]="size" [attr.height]="size" viewBox="0 0 24 24" fill="none" class="oq-spin" aria-hidden="true">
      <circle cx="12" cy="12" r="9" stroke="currentColor" stroke-width="2.5" stroke-linecap="round" stroke-dasharray="14 100" opacity="0.85" />
    </svg>
  `,
})
export class OqSpinnerComponent {
  @Input() size = 14;
}
