import { Component, EventEmitter, Input, Output, inject, signal } from '@angular/core';
import { HttpErrorResponse } from '@angular/common/http';
import { OqIconComponent } from '../../shared/icons/oq-icon.component';
import { OqInlineAlertComponent } from '../../shared/oq-inline-alert/oq-inline-alert.component';
import { TransferenciaService } from '../transferencia.service';
import { ItemTransferencia } from '../transferencia.model';

@Component({
  selector: 'oq-resumo',
  standalone: true,
  host: { style: 'display: flex; flex-direction: column; min-height: 0; flex: 1;' },
  imports: [OqIconComponent, OqInlineAlertComponent],
  templateUrl: './oq-resumo.component.html',
  styleUrl: './oq-resumo.component.scss',
})
export class OqResumoComponent {
  private readonly service = inject(TransferenciaService);

  @Input({ required: true }) transferenciaId = '';
  @Input({ required: true }) origem = '';
  @Input({ required: true }) destino = '';
  @Input({ required: true }) items: ItemTransferencia[] = [];

  @Output() voltar = new EventEmitter<void>();
  @Output() concluido = new EventEmitter<void>();

  readonly confirmando = signal(false);
  readonly concluidoComSucesso = signal(false);
  readonly erro = signal<string | null>(null);

  get totalUnidades(): number {
    return this.items.reduce((soma, item) => soma + Number(item.quantidade), 0);
  }

  confirmar(): void {
    if (this.confirmando()) return;
    this.confirmando.set(true);
    this.erro.set(null);
    this.service.confirmarTransferencia(this.transferenciaId).subscribe({
      next: () => {
        this.confirmando.set(false);
        this.concluidoComSucesso.set(true);
        setTimeout(() => this.concluido.emit(), 1400);
      },
      error: (err: HttpErrorResponse) => {
        this.confirmando.set(false);
        this.erro.set(err.error?.erro ?? 'Não foi possível confirmar a transferência');
      },
    });
  }
}
