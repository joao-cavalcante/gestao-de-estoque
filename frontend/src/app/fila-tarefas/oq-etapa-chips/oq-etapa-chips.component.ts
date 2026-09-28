import { Component, EventEmitter, Input, Output } from '@angular/core';
import { OqIconComponent } from '../../shared/icons/oq-icon.component';
import { EtapaVisual } from '../tarefa-visual';

/**
 * Chips "Conferir por etapa" (V29) — um por tipo de separação com item na nota.
 * Usado no card e na linha da lista, com o mesmo comportamento: clique no chip
 * pendente abre a conferência daquela etapa; concluída fica desabilitada.
 * O layout do contêiner (host) é de quem usa — card rola na horizontal, lista idem.
 */
@Component({
  selector: 'oq-etapa-chips',
  standalone: true,
  host: { role: 'group', 'aria-label': 'Etapas da conferência' },
  imports: [OqIconComponent],
  templateUrl: './oq-etapa-chips.component.html',
  styleUrl: './oq-etapa-chips.component.scss',
})
export class OqEtapaChipsComponent {
  @Input({ required: true }) etapas: EtapaVisual[] = [];
  @Output() conferirEtapa = new EventEmitter<number>();

  clicar(tipo: number): void {
    this.conferirEtapa.emit(tipo);
  }
}
