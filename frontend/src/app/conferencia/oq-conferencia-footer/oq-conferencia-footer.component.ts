import { Component, EventEmitter, Input, Output } from '@angular/core';
import { OqIconComponent } from '../../shared/icons/oq-icon.component';

@Component({
  selector: 'oq-conferencia-footer',
  standalone: true,
  imports: [OqIconComponent],
  templateUrl: './oq-conferencia-footer.component.html',
  styleUrl: './oq-conferencia-footer.component.scss',
})
export class OqConferenciaFooterComponent {
  @Input() divergenceCount = 0;
  @Input() pendingCount = 0;
  @Input() conferredCount = 0;
  @Input() canConfirm = false;

  @Output() voltar = new EventEmitter<void>();
  @Output() confirmar = new EventEmitter<void>();

  get textoDivergencia(): string {
    const plural = this.divergenceCount > 1 ? 'S' : '';
    return `${this.divergenceCount} DIVERGÊNCIA${plural} PENDENTE${plural} DE RESOLUÇÃO`;
  }
}
