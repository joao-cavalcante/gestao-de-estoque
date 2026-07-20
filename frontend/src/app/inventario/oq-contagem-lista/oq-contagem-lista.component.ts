import { Component, Input } from '@angular/core';
import { OqIconComponent } from '../../shared/icons/oq-icon.component';
import { OqInlineAlertComponent } from '../../shared/oq-inline-alert/oq-inline-alert.component';
import { ItemInventario } from '../inventario.model';

@Component({
  selector: 'oq-contagem-lista',
  standalone: true,
  host: { style: 'display: flex; flex-direction: column; min-height: 0; flex: 1;' },
  imports: [OqIconComponent, OqInlineAlertComponent],
  templateUrl: './oq-contagem-lista.component.html',
  styleUrl: './oq-contagem-lista.component.scss',
})
export class OqContagemListaComponent {
  @Input({ required: true }) items: ItemInventario[] = [];
}
