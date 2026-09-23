import { Component, EventEmitter, Input, Output } from '@angular/core';
import { OqIconComponent } from '../../shared/icons/oq-icon.component';
import { OqSpinnerComponent } from '../../shared/icons/oq-spinner.component';

@Component({
  selector: 'oq-conferencia-footer',
  standalone: true,
  imports: [OqIconComponent, OqSpinnerComponent],
  templateUrl: './oq-conferencia-footer.component.html',
  styleUrl: './oq-conferencia-footer.component.scss',
})
export class OqConferenciaFooterComponent {
  @Input() divergenceCount = 0;
  @Input() pendingCount = 0;
  @Input() conferredCount = 0;
  /** Itens que já bateram o total (não conta parcial) — numerador da barra PROGRESSO. */
  @Input() progressoFeito = 0;
  /** Total de itens distintos (parcial não conta 2x) — denominador da barra PROGRESSO. */
  @Input() progressoTotal = 0;
  @Input() canConfirm = false;
  /** true = requisição de confirmar/concluir etapa em voo — mostra spinner no botão primário. */
  @Input() confirmando = false;
  /** CCO.FORMACAOVOLUMES 'S'/'T'/'D' — exige volume > 0; destaca o contador quando ainda zerado. */
  @Input() exigeVolume = false;
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

  /**
   * true só pra clique/toque de verdade. Enter/Espaço num botão com foco também
   * geram "click" (com detail === 0) — e o leitor de código de barras termina
   * toda leitura com Enter. Caso real (nota 57735): o botão "Concluir Etapa"
   * ficou com foco, o Enter de um bipe acionou a conclusão da última etapa e
   * abriu o pop-up de finalização divergente com itens ainda não bipados.
   * Mesmo guarda no +/− de volumes (o bipe somava volume sozinho).
   */
  soPonteiro(event: MouseEvent): boolean {
    (event.currentTarget as HTMLElement | null)?.blur();
    return event.detail > 0;
  }

  onVolumeMenos(): void {
    if (this.volume > 0) this.volumeChange.emit(this.volume - 1);
  }

  onVolumeMais(): void {
    this.volumeChange.emit(this.volume + 1);
  }

  /** true = CCO exige volume mas o contador ainda está zerado — destaca e explica o botão desabilitado. */
  get faltaVolume(): boolean {
    return this.exigeVolume && this.volume === 0;
  }

  get tituloBotaoPrimario(): string {
    return this.faltaVolume ? 'Informe a quantidade de volumes antes de confirmar' : '';
  }

  get textoDivergencia(): string {
    const plural = this.divergenceCount > 1 ? 'S' : '';
    return `${this.divergenceCount} DIVERGÊNCIA${plural} PENDENTE${plural} DE RESOLUÇÃO`;
  }
}
