import { Component, EventEmitter, Input, Output } from '@angular/core';
import { CommonModule } from '@angular/common';
import { OqIconComponent, OqIconName } from '../../shared/icons/oq-icon.component';
import { StatusTarefa, Tarefa } from '../tarefa.model';

interface StatusVisual {
  icone: OqIconName;
  label: string;
  corVar: string;
  gira: boolean;
}

const STATUS_VISUAL: Record<StatusTarefa, StatusVisual> = {
  aguardando: { icone: 'circle', label: 'AGUARDANDO', corVar: 'var(--oq-status-idle)', gira: false },
  andamento: { icone: 'gear', label: 'EM ANDAMENTO', corVar: 'var(--oq-status-active)', gira: true },
  concluido: { icone: 'check', label: 'CONCLUÍDO', corVar: 'var(--oq-status-done)', gira: false },
};

@Component({
  selector: 'oq-task-card',
  standalone: true,
  // Sem isto, o item de grid de verdade é a tag <oq-task-card> (o host),
  // não o <article class="oq-card"> de dentro — e por padrão ela não tem
  // min-width:0, então o grid expande a coluna pro min-content do nome do
  // cliente (nowrap) em vez de respeitar o 1fr. Mesmo problema de
  // propagação de largura em custom element já visto na tela de Conferência.
  host: { style: 'display: block; min-width: 0;' },
  imports: [CommonModule, OqIconComponent],
  templateUrl: './oq-task-card.component.html',
  styleUrl: './oq-task-card.component.scss',
})
export class OqTaskCardComponent {
  @Input({ required: true }) tarefa!: Tarefa;

  @Output() verDetalhes = new EventEmitter<Tarefa>();
  @Output() conferir = new EventEmitter<Tarefa>();

  get statusVisual(): StatusVisual {
    return STATUS_VISUAL[this.tarefa.status];
  }

  get classeCard(): Record<string, boolean> {
    return {
      'oq-card--critical': this.tarefa.alerta === 'critico',
      'oq-card--attention': this.tarefa.alerta === 'atencao',
    };
  }

  formatarValor(valor: number): string {
    return valor.toLocaleString('pt-BR', { style: 'currency', currency: 'BRL' });
  }
}
