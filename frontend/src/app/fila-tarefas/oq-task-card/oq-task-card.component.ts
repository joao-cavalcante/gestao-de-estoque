import { Component, EventEmitter, Input, Output } from '@angular/core';
import { CommonModule } from '@angular/common';
import { OqIconComponent, OqIconName } from '../../shared/icons/oq-icon.component';
import { StatusTarefa, Tarefa, TIPOS_SEPARACAO } from '../tarefa.model';

interface StatusVisual {
  icone: OqIconName;
  label: string;
  corVar: string;
  gira: boolean;
}

const STATUS_VISUAL: Record<StatusTarefa, StatusVisual> = {
  aguardando: { icone: 'circle', label: 'AGUARDANDO', corVar: 'var(--oq-status-idle)', gira: false },
  andamento: { icone: 'gear', label: 'EM ANDAMENTO', corVar: 'var(--oq-status-active)', gira: true },
  aguardando_corte: { icone: 'circle-alert', label: 'AGUARDANDO CORTE', corVar: 'var(--oq-status-active)', gira: false },
  concluido: { icone: 'check', label: 'CONCLUÍDO', corVar: 'var(--oq-status-done)', gira: false },
};

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
  imports: [CommonModule, OqIconComponent],
  templateUrl: './oq-task-card.component.html',
  styleUrl: './oq-task-card.component.scss',
})
export class OqTaskCardComponent {
  @Input({ required: true }) tarefa!: Tarefa;

  @Output() verDetalhes = new EventEmitter<Tarefa>();
  @Output() conferir = new EventEmitter<Tarefa | { tarefa: Tarefa; etapa: number }>();

  /** Etapas da conferência por etapa (V29) — com rótulo/ícone/progresso resolvidos. Vazio = nota não segmentada. */
  get etapasVisiveis(): {
    tipo: number;
    label: string;
    icone: OqIconName;
    concluida: boolean;
    emAndamento: boolean;
    progresso: string;
    botao: string;
  }[] {
    return (this.tarefa.etapas ?? [])
      .map((e) => {
        const cat = TIPOS_SEPARACAO.find((t) => t.id === e.tipo);
        const concluida = e.status === 'C';
        const emAndamento = !concluida && e.conferidos > 0;
        return {
          tipo: e.tipo,
          label: cat?.label ?? `Tipo ${e.tipo}`,
          icone: (cat?.icone ?? 'box') as OqIconName,
          concluida,
          emAndamento,
          progresso: e.total > 0 ? `${e.conferidos}/${e.total}` : '',
          botao: concluida ? 'Concluída' : emAndamento ? 'Continuar' : 'Conferir',
        };
      })
      .sort((a, b) => a.tipo - b.tipo);
  }

  conferirEtapa(tipo: number): void {
    this.conferir.emit({ tarefa: this.tarefa, etapa: tipo });
  }

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
