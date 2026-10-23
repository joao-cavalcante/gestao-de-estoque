import { Component, EventEmitter, Input, Output } from '@angular/core';
import { OqModalidadePinsComponent } from '../../shared/oq-modalidade-pins/oq-modalidade-pins.component';
import { OqIconComponent } from '../../shared/icons/oq-icon.component';
import { CampoOrdenacao, FasePedido, Ordenacao, Tarefa, faseTarefa, tomNota } from '../tarefa.model';
import { EtapaVisual, StatusVisual, etapasVisiveis, statusVisual } from '../tarefa-visual';
import { OqEtapaChipsComponent } from '../oq-etapa-chips/oq-etapa-chips.component';

interface Linha {
  t: Tarefa;
  st: StatusVisual;
  ets: EtapaVisual[];
  fase: FasePedido;
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
 * Clicar na linha NÃO inicia conferência (pedido do usuário — toque sem querer
 * no tablet abria a nota): só o botão "Conferir"/"Continuar" ou os chips de etapa.
 */
@Component({
  selector: 'oq-task-list',
  standalone: true,
  imports: [OqIconComponent, OqEtapaChipsComponent, OqModalidadePinsComponent],
  templateUrl: './oq-task-list.component.html',
  styleUrl: './oq-task-list.component.scss',
})
export class OqTaskListComponent {
  /** Página já filtrada/ordenada pelo pai — status/etapas resolvidos uma vez por mudança, não a cada detecção. */
  @Input({ required: true }) set tarefas(v: Tarefa[]) {
    this.linhas = v.map((t) => ({ t, st: statusVisual(t), ets: etapasVisiveis(t), fase: faseTarefa(t) }));
  }
  linhas: Linha[] = [];
  @Input() ordenacao: Ordenacao | null = null;

  @Output() conferir = new EventEmitter<Tarefa | { tarefa: Tarefa; etapa: number }>();
  @Output() ordenar = new EventEmitter<CampoOrdenacao>();
  /** "✓ Carregado" — um toque, pedido inteiro. */
  @Output() carregado = new EventEmitter<Tarefa>();
  /** "Gerar nota" — abre o modal de faturamento (TOP por pedido). */
  @Output() faturar = new EventEmitter<Tarefa>();

  /** Tela da OC: linhas arrastáveis pra o operador montar a ordem (o pai guarda a ordem). */
  @Input() arrastavel = false;
  /** Id da linha sendo arrastada (vem do pai — vale entre as tabelas das seções). */
  @Input() arrastando: string | null = null;
  @Output() arrastarInicio = new EventEmitter<string | null>();
  /** Soltou sobre a linha [id]. */
  @Output() soltar = new EventEmitter<string>();

  readonly tomNota = tomNota;

  readonly colunas: Coluna[] = [
    { titulo: 'Status', classe: 'oq-lista__topo' },
    { titulo: 'Cliente', campo: 'cliente', classe: 'oq-lista__principal' },
    { titulo: 'Nº Único', campo: 'numeroUnico' },
    { titulo: 'Pedido', campo: 'nf' },
    { titulo: 'Nota fiscal' },
    { titulo: 'Ordem de Carga' },
    { titulo: 'Data', campo: 'data' },
    { titulo: 'Itens', campo: 'itens', classe: 'oq-lista__direita' },
    { titulo: 'Período p/ entrega' },
    { titulo: 'Ação', classe: 'oq-lista__acoes' },
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

  iniciarArraste(id: string | null): void {
    if (this.arrastavel) this.arrastarInicio.emit(id);
  }

  /** Permite soltar sobre a linha (sem isso o navegador recusa o drop). */
  sobre(ev: DragEvent): void {
    if (this.arrastavel) ev.preventDefault();
  }

  soltarEm(ev: DragEvent, id: string): void {
    if (!this.arrastavel) return;
    ev.preventDefault();
    this.soltar.emit(id);
  }

  conferirEtapa(t: Tarefa, tipo: number): void {
    this.conferir.emit({ tarefa: t, etapa: tipo });
  }
}
