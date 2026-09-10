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
  /** Rótulo do botão primário — "Confirmar Conferência" (normal) ou "Concluir Etapa X" (conferência por etapa). */
  @Input() confirmarLabel = 'Confirmar Conferência';
  /** Rótulo do botão de sair — "Voltar" (normal) ou "Salvar e sair" (conferência por etapa: progresso persiste). */
  @Input() voltarLabel = 'Voltar';
  /** Modo simplificado (sem dimensão) — só a quantidade de volumes do pedido, nativo do Sankhya. */
  @Input() volume = 0;

  @Output() voltar = new EventEmitter<void>();
  @Output() confirmar = new EventEmitter<void>();
  @Output() volumeChange = new EventEmitter<number>();
  @Output() cancelar = new EventEmitter<void>();

  onVolumeMenos(): void {
    if (this.volume > 0) this.volumeChange.emit(this.volume - 1);
  }

  onVolumeMais(): void {
    this.volumeChange.emit(this.volume + 1);
  }

  get textoDivergencia(): string {
    const plural = this.divergenceCount > 1 ? 'S' : '';
    return `${this.divergenceCount} DIVERGÊNCIA${plural} PENDENTE${plural} DE RESOLUÇÃO`;
  }
}
