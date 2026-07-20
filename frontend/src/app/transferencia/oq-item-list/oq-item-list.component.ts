import { Component, EventEmitter, Input, Output } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { OqIconComponent } from '../../shared/icons/oq-icon.component';
import { ItemTransferencia } from '../transferencia.model';

@Component({
  selector: 'oq-item-list',
  standalone: true,
  host: { style: 'display: flex; flex-direction: column; min-height: 0; flex: 1;' },
  imports: [FormsModule, OqIconComponent],
  templateUrl: './oq-item-list.component.html',
  styleUrl: './oq-item-list.component.scss',
})
export class OqItemListComponent {
  @Input({ required: true }) items: ItemTransferencia[] = [];
  @Output() alterarQtd = new EventEmitter<{ id: string; qtd: number }>();
  @Output() remover = new EventEmitter<string>();

  idEmEdicao: string | null = null;
  qtdEdicao = '';

  abrirEdicao(item: ItemTransferencia): void {
    this.idEmEdicao = item.id;
    this.qtdEdicao = item.quantidade;
  }

  confirmarEdicao(item: ItemTransferencia): void {
    const n = parseFloat(String(this.qtdEdicao).replace(',', '.'));
    if (!n || n <= 0) {
      this.remover.emit(item.id);
    } else {
      this.alterarQtd.emit({ id: item.id, qtd: n });
    }
    this.idEmEdicao = null;
  }

  cancelarEdicao(): void {
    this.idEmEdicao = null;
  }
}
