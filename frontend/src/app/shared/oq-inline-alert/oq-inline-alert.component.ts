import { Component, Input } from '@angular/core';
import { OqIconComponent, OqIconName } from '../icons/oq-icon.component';

export type OqAlertTone = 'critical' | 'warning' | 'success';

/**
 * Alerta compartilhado (crítico/atenção/sucesso) — único ponto que resolve
 * `--oq-critical-bg`/`--oq-warning-soft`/`--oq-success-soft` etc. Existia
 * duplicado inline em cada feature (oq-last-scan-panel, oq-pending-list...);
 * extraído aqui pra não repetir a mesma classe de bug de novo (componente
 * com fundo fixo que não migra de tema porque referencia um token que só
 * existe num dos dois temas, ou um token que não é de tema nenhum).
 */
@Component({
  selector: 'oq-inline-alert',
  standalone: true,
  imports: [OqIconComponent],
  templateUrl: './oq-inline-alert.component.html',
  styleUrl: './oq-inline-alert.component.scss',
})
export class OqInlineAlertComponent {
  @Input() tone: OqAlertTone = 'critical';
  @Input({ required: true }) title = '';
  @Input() detail?: string;
  /** 'toast' só muda a ênfase visual (borda lateral mais grossa) — segue no fluxo normal, quem decide auto-dismiss é quem usa. */
  @Input() variant: 'inline' | 'toast' = 'inline';

  get icone(): OqIconName {
    switch (this.tone) {
      case 'warning':
        return 'triangle';
      case 'success':
        return 'check';
      default:
        return 'circle-alert';
    }
  }
}
