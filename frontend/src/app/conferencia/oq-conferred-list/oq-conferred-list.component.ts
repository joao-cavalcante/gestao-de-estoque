import { Component, EventEmitter, Input, Output } from '@angular/core';
import { OqPanelSectionComponent } from '../oq-panel-section/oq-panel-section.component';
import { OqIconComponent } from '../../shared/icons/oq-icon.component';
import { ConferenciaItem } from '../conferencia.model';

@Component({
  selector: 'oq-conferred-list',
  standalone: true,
  // Ver comentário equivalente em oq-pending-list.component.ts.
  host: { style: 'display: flex; flex-direction: column; min-height: 0; height: 100%;' },
  imports: [OqPanelSectionComponent, OqIconComponent],
  templateUrl: './oq-conferred-list.component.html',
  styleUrl: './oq-conferred-list.component.scss',
})
export class OqConferredListComponent {
  @Input({ required: true }) items: ConferenciaItem[] = [];
  /** CCO.EXIBIRQTDCONF — false esconde a quantidade conferida de cada item. */
  @Input() exibirQtd = true;

  /** Desfaz TUDO que foi conferido pra esse item (produto+controle) — corrige bipe errado. */
  @Output() devolver = new EventEmitter<ConferenciaItem>();

  isCritico(item: ConferenciaItem): boolean {
    return item.status === 'critical';
  }

  /** Diferença bipado - esperado (só positiva importa aqui, é o excesso). */
  excedente(item: ConferenciaItem): number {
    return item.scanned - item.expected;
  }

  formatarQtd(n: number): string {
    return (n ?? 0).toLocaleString('pt-BR', { minimumFractionDigits: 3, maximumFractionDigits: 3 });
  }
}
