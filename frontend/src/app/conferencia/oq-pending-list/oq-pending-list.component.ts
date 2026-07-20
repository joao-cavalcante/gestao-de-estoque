import { Component, Input } from '@angular/core';
import { OqPanelSectionComponent } from '../oq-panel-section/oq-panel-section.component';
import { OqSeqBadgeComponent } from '../oq-seq-badge/oq-seq-badge.component';
import { OqQtyComponent } from '../oq-qty/oq-qty.component';
import { OqStatusChipComponent } from '../oq-status-chip/oq-status-chip.component';
import { OqIconComponent } from '../../shared/icons/oq-icon.component';
import { ConferenciaItem } from '../conferencia.model';

@Component({
  selector: 'oq-pending-list',
  standalone: true,
  // Sem isto, o elemento host (tag Angular, sem display por padrão) não
  // repassa a altura esticada pelo grid pro <oq-panel-section> de dentro —
  // o painel encolhe pro tamanho do conteúdo em vez de preencher a célula.
  host: { style: 'display: flex; flex-direction: column; min-height: 0; height: 100%;' },
  imports: [OqPanelSectionComponent, OqSeqBadgeComponent, OqQtyComponent, OqStatusChipComponent, OqIconComponent],
  templateUrl: './oq-pending-list.component.html',
  styleUrl: './oq-pending-list.component.scss',
})
export class OqPendingListComponent {
  @Input({ required: true }) items: ConferenciaItem[] = [];

  restante(item: ConferenciaItem): number {
    return item.expected - item.scanned;
  }
}
