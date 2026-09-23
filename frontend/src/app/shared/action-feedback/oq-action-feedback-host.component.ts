import { Component, inject } from '@angular/core';
import { OqIconComponent } from '../icons/oq-icon.component';
import { OqInlineAlertComponent } from '../oq-inline-alert/oq-inline-alert.component';
import { ActionFeedbackService } from './action-feedback.service';
import { FeedbackTom } from './action-feedback.types';

/**
 * Pilha global de toasts da biblioteca de feedback — montada UMA vez no
 * app.component. Reusa o oq-inline-alert (variante toast), o mesmo alerta
 * já usado por inventário/transferência, em vez de criar outro visual.
 */
@Component({
  selector: 'oq-action-feedback-host',
  standalone: true,
  imports: [OqInlineAlertComponent, OqIconComponent],
  template: `
    <div class="oq-afb-pilha" aria-live="assertive">
      @for (t of feedback.toasts(); track t.id) {
        <div class="oq-afb-toast" [class.oq-afb-toast--bloqueante]="t.bloqueante">
          <oq-inline-alert variant="toast" [tone]="tomAlerta(t.tom)" [title]="t.titulo" [detail]="t.detalhe" />
          <button type="button" class="oq-afb-toast__fechar" aria-label="Fechar" (click)="feedback.fecharToast(t.id)">
            <oq-icon name="x" [size]="14" />
          </button>
        </div>
      }
    </div>
  `,
  styles: `
    .oq-afb-pilha {
      position: fixed;
      right: 16px;
      bottom: 16px;
      z-index: 2000;
      display: flex;
      flex-direction: column;
      gap: 8px;
      width: min(420px, calc(100vw - 32px));
      pointer-events: none;
    }
    .oq-afb-toast {
      position: relative;
      pointer-events: auto;
      box-shadow: 0 6px 20px rgba(0, 0, 0, 0.18);
      border-radius: var(--oq-radius-input);
      background: var(--oq-surface);
      animation: oq-afb-entra 0.16s ease-out;
    }
    .oq-afb-toast--bloqueante {
      outline: 2px solid var(--oq-critical);
    }
    .oq-afb-toast__fechar {
      position: absolute;
      top: 6px;
      right: 6px;
      border: 0;
      background: none;
      color: var(--oq-text-secondary);
      cursor: pointer;
      padding: 4px;
      display: inline-flex;
    }
    @keyframes oq-afb-entra {
      from { transform: translateY(8px); opacity: 0; }
      to { transform: none; opacity: 1; }
    }
    @media print {
      .oq-afb-pilha { display: none; }
    }
  `,
})
export class OqActionFeedbackHostComponent {
  readonly feedback = inject(ActionFeedbackService);

  tomAlerta(tom: FeedbackTom): 'critical' | 'warning' | 'success' {
    return tom === 'info' ? 'success' : tom;
  }
}
