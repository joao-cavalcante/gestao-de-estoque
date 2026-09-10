import { Component, EventEmitter, Input, Output } from '@angular/core';
import { OqPanelSectionComponent } from '../oq-panel-section/oq-panel-section.component';
import { OqIconComponent } from '../../shared/icons/oq-icon.component';
import { ConferenciaItem } from '../conferencia.model';

@Component({
  selector: 'oq-pending-list',
  standalone: true,
  // Sem isto, o elemento host (tag Angular, sem display por padrão) não
  // repassa a altura esticada pelo grid pro <oq-panel-section> de dentro —
  // o painel encolhe pro tamanho do conteúdo em vez de preencher a célula.
  host: { style: 'display: flex; flex-direction: column; min-height: 0; height: 100%;' },
  imports: [OqPanelSectionComponent, OqIconComponent],
  templateUrl: './oq-pending-list.component.html',
  styleUrl: './oq-pending-list.component.scss',
})
export class OqPendingListComponent {
  @Input({ required: true }) items: ConferenciaItem[] = [];
  /** CCO.EXIBIRQTD — false esconde a quantidade negociada de cada item. */
  @Input() exibirQtd = true;
  /** Clique no item — identifica o produto sem bipar/digitar o código (elimina o Tab no mobile). */
  @Output() selecionar = new EventEmitter<ConferenciaItem>();

  /** Mesmo formato da conferência antiga: decimal pt-BR com 3 casas (1.3-3). */
  formatarQtd(n: number): string {
    return (n ?? 0).toLocaleString('pt-BR', { minimumFractionDigits: 3, maximumFractionDigits: 3 });
  }

  /** true quando a unidade do pedido (comercial) difere da unidade base do produto — aí mostra a linha "Pedido: X". */
  temComercial(item: ConferenciaItem): boolean {
    return !!item.unidadeComercial && item.unidadeComercial !== item.unidadePadrao;
  }
}
