import { Component, EventEmitter, Input, Output } from '@angular/core';
import { OqIconComponent } from '../../shared/icons/oq-icon.component';
import { CampoOrdenacao, Ordenacao, Tarefa } from '../tarefa.model';
import { EtapaVisual, StatusVisual, etapasVisiveis, statusVisual } from '../tarefa-visual';
import { OqEtapaChipsComponent } from '../oq-etapa-chips/oq-etapa-chips.component';

interface Linha {
  t: Tarefa;
  st: StatusVisual;
  ets: EtapaVisual[];
}

interface Coluna {
  titulo: string;
  campo?: CampoOrdenacao;
  classe?: string;
}

/**
 * Modo LISTA da Fila de Tarefas — uma linha por pedido, mesmas informações e
 * mesmas ações do card (oq-task-card): mesma fonte de dados já filtrada e
 * paginada pelo pai; só a renderização muda. Status e etapas vêm do mesmo
 * tarefa-visual.ts do card, e os chips de etapa são o mesmo componente.
 *
 * Linha clicável (clique / Enter) = o botão "Conferir"/"Continuar" do card.
 * Em nota por etapa o card só tem os chips; aqui a linha abre a conferência
 * sem etapa, e a própria tela de conferência mostra o seletor (ou assume a
 * única etapa pendente) — os chips continuam abrindo direto a etapa.
 */
@Component({
  selector: 'oq-task-list',
  standalone: true,
  imports: [OqIconComponent, OqEtapaChipsComponent],
  templateUrl: './oq-task-list.component.html',
  styleUrl: './oq-task-list.component.scss',
})
export class OqTaskListComponent {
  /** Página já filtrada/ordenada pelo pai — status/etapas resolvidos uma vez por mudança, não a cada detecção. */
  @Input({ required: true }) set tarefas(v: Tarefa[]) {
    this.linhas = v.map((t) => ({ t, st: statusVisual(t), ets: etapasVisiveis(t) }));
  }
  linhas: Linha[] = [];
  @Input() ordenacao: Ordenacao | null = null;

  @Output() conferir = new EventEmitter<Tarefa | { tarefa: Tarefa; etapa: number }>();
  @Output() ordenar = new EventEmitter<CampoOrdenacao>();

  readonly colunas: Coluna[] = [
    { titulo: 'Status', classe: 'col-status' },
    { titulo: 'Cliente', campo: 'cliente', classe: 'col-cliente' },
    { titulo: 'Nº Único', campo: 'numeroUnico', classe: 'col-num' },
    { titulo: 'NF', campo: 'nf', classe: 'col-num' },
    { titulo: 'Ordem de Carga', classe: 'col-num' },
    { titulo: 'Data', campo: 'data', classe: 'col-num' },
    { titulo: 'Itens', campo: 'itens', classe: 'col-itens' },
    { titulo: 'Período p/ entrega', classe: 'col-periodo' },
    { titulo: 'Conferir por etapa', classe: 'col-etapas' },
  ];

  /** aria-sort do cabeçalho — só a coluna ordenada anuncia a direção. */
  ariaSort(campo?: CampoOrdenacao): 'ascending' | 'descending' | 'none' | null {
    if (!campo) return null;
    if (this.ordenacao?.campo !== campo) return 'none';
    return this.ordenacao.direcao === 'asc' ? 'ascending' : 'descending';
  }

  indicador(campo?: CampoOrdenacao): string {
    if (!campo || this.ordenacao?.campo !== campo) return '↕';
    return this.ordenacao.direcao === 'asc' ? '▲' : '▼';
  }

  abrir(t: Tarefa): void {
    this.conferir.emit(t);
  }

  conferirEtapa(t: Tarefa, tipo: number): void {
    this.conferir.emit({ tarefa: t, etapa: tipo });
  }
}
