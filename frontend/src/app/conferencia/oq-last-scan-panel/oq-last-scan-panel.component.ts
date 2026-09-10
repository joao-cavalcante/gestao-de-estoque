import { Component, EventEmitter, Input, Output } from '@angular/core';
import { OqIconComponent } from '../../shared/icons/oq-icon.component';
import { ConferenciaItem } from '../conferencia.model';

@Component({
  selector: 'oq-last-scan-panel',
  standalone: true,
  // Mesma razão do host style em oq-pending-list/oq-panel-section: sem
  // isto a tag host não estica, e o <section> interno encolhe pro
  // conteúdo em vez de preencher a célula do grid pai (linha 1.2fr).
  host: { style: 'display: flex; flex-direction: column; min-height: 0; height: 100%;' },
  imports: [OqIconComponent],
  templateUrl: './oq-last-scan-panel.component.html',
  styleUrl: './oq-last-scan-panel.component.scss',
})
export class OqLastScanPanelComponent {
  @Input() item: ConferenciaItem | null = null;
  /** Variante de uma linha (tarja) usada no layout de celular, abaixo do scan-bar. */
  @Input() compacto = false;
  /** CCO.EXIBIRQTDCONF — false esconde a quantidade na tarja compacta. */
  @Input() exibirQtd = true;
  @Output() resolver = new EventEmitter<ConferenciaItem>();

  get isCritico(): boolean {
    return this.item?.status === 'critical';
  }
}
