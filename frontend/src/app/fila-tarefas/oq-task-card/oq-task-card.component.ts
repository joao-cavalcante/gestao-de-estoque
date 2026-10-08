import { Component, EventEmitter, Input, Output } from '@angular/core';
import { OqModalidadePinsComponent } from '../../shared/oq-modalidade-pins/oq-modalidade-pins.component';
import { CommonModule } from '@angular/common';
import { OqIconComponent } from '../../shared/icons/oq-icon.component';
import { FasePedido, Tarefa, faseTarefa } from '../tarefa.model';
import { EtapaVisual, StatusVisual, etapasVisiveis, statusVisual } from '../tarefa-visual';
import { OqEtapaChipsComponent } from '../oq-etapa-chips/oq-etapa-chips.component';

@Component({
  selector: 'oq-task-card',
  standalone: true,
  // O item de grid de verdade é a tag <oq-task-card> (o host), não o
  // <article class="oq-card"> de dentro. Sem `min-width:0` o grid expande a
  // coluna pro min-content do nome do cliente (nowrap) em vez de respeitar o
  // 1fr; sem `display:flex` o <article> fica com a altura do conteúdo (não
  // estica com a linha do grid), e cards sem etapa ficam mais baixos que os
  // com etapa na mesma linha. Mesmo problema de propagação já visto na tela
  // de Conferência.
  host: { style: 'display: flex; min-width: 0;' },
  imports: [CommonModule, OqIconComponent, OqEtapaChipsComponent, OqModalidadePinsComponent],
  templateUrl: './oq-task-card.component.html',
  styleUrl: './oq-task-card.component.scss',
})
export class OqTaskCardComponent {
  @Input({ required: true }) tarefa!: Tarefa;

  @Output() conferir = new EventEmitter<Tarefa | { tarefa: Tarefa; etapa: number }>();
  /** "✓ Carregado" — um toque, pedido inteiro. */
  @Output() carregado = new EventEmitter<Tarefa>();
  /** "Gerar nota" — abre o modal de faturamento (TOP por pedido). */
  @Output() faturar = new EventEmitter<Tarefa>();

  /** Fase do fluxo (conferir → corte → carregar → nota → pronto): decide o único botão principal. */
  get fase(): FasePedido {
    return faseTarefa(this.tarefa);
  }

  /** Etapas da conferência por etapa (V29) — ver tarefa-visual.ts (mesmo cálculo da lista). */
  get etapasVisiveis(): EtapaVisual[] {
    return etapasVisiveis(this.tarefa);
  }

  conferirEtapa(tipo: number): void {
    this.conferir.emit({ tarefa: this.tarefa, etapa: tipo });
  }

  get statusVisual(): StatusVisual {
    return statusVisual(this.tarefa);
  }

  get classeCard(): Record<string, boolean> {
    return {
      'oq-card--critical': this.tarefa.alerta === 'critico',
      'oq-card--attention': this.tarefa.alerta === 'atencao',
    };
  }
}
