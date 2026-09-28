import { Component, EventEmitter, Input, Output } from '@angular/core';
import { OqIconComponent, OqIconName } from '../icons/oq-icon.component';
import { ViewMode } from './view-mode';

/**
 * Alternador cards ↔ lista das telas de fila — mesmo visual do grupo de status
 * da Fila de Tarefas (.oq-toolbar__pills, estilos globais em _lista-layout.scss).
 */
@Component({
  selector: 'oq-view-toggle',
  standalone: true,
  imports: [OqIconComponent],
  host: { style: 'display: contents;' },
  template: `
    <div class="oq-toolbar__pills" role="group" aria-label="Modo de visualização">
      @for (m of modos; track m.valor) {
        <button
          type="button"
          class="oq-toolbar__pill oq-toolbar__pill--icon oq-toolbar__pill--view"
          [class.oq-toolbar__pill--active]="modo === m.valor"
          [attr.aria-pressed]="modo === m.valor"
          [attr.aria-label]="m.label"
          [title]="m.label"
          (click)="modoChange.emit(m.valor)"
        >
          <oq-icon [name]="m.icone" [size]="14" />
        </button>
      }
    </div>
  `,
})
export class OqViewToggleComponent {
  @Input() modo: ViewMode = 'cards';
  @Output() modoChange = new EventEmitter<ViewMode>();

  readonly modos: { valor: ViewMode; icone: OqIconName; label: string }[] = [
    { valor: 'cards', icone: 'grid', label: 'Visualizar em cards' },
    { valor: 'list', icone: 'list', label: 'Visualizar em lista' },
  ];
}
