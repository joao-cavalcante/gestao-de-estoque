import { Component, EventEmitter, Input, Output } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { OqStatusChipComponent } from '../../conferencia/oq-status-chip/oq-status-chip.component';
import { ChipTone } from '../../conferencia/conferencia.model';
import { CampoCatalogo } from '../config-conferencia.model';

@Component({
  selector: 'oq-config-campo-row',
  standalone: true,
  imports: [FormsModule, OqStatusChipComponent],
  templateUrl: './oq-config-campo-row.component.html',
  styleUrl: './oq-config-campo-row.component.scss',
})
export class OqConfigCampoRowComponent {
  @Input({ required: true }) campo!: CampoCatalogo;
  @Input() valor: string | null = null;
  @Input() habilitado = true;
  @Output() valorChange = new EventEmitter<string>();

  get valorToggle(): boolean {
    return this.valor === 'S';
  }

  onToggle(marcado: boolean): void {
    this.valorChange.emit(marcado ? 'S' : 'N');
  }

  onSelect(v: string): void {
    this.valorChange.emit(v);
  }

  onTexto(v: string): void {
    this.valorChange.emit(v);
  }

  get badgeTone(): ChipTone {
    switch (this.campo.status) {
      case 'implementado':
        return 'success';
      case 'parcial':
        return 'warning';
      default:
        return 'neutral';
    }
  }

  get badgeLabel(): string {
    switch (this.campo.status) {
      case 'implementado':
        return 'IMPLEMENTADO';
      case 'parcial':
        return 'PARCIAL';
      case 'nao_implementado':
        return 'NÃO IMPLEMENTADO';
      default:
        return 'NÃO APLICÁVEL';
    }
  }

  get ehNaoAplicavel(): boolean {
    return this.campo.status === 'nao_aplicavel';
  }
}
