import { Component, EventEmitter, Input, Output } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { OqIconComponent } from '../../shared/icons/oq-icon.component';
import { OqFiltrosAvancadosComponent } from '../oq-filtros-avancados/oq-filtros-avancados.component';
import { FiltroStatus, FiltrosAvancados, OpcaoComCodigo, TIPOS_SEPARACAO, ViewMode } from '../tarefa.model';
import { OqViewToggleComponent } from '../../shared/lista-layout/oq-view-toggle.component';

interface PillFiltro {
  valor: FiltroStatus;
  label: string;
}

@Component({
  selector: 'oq-toolbar',
  standalone: true,
  imports: [FormsModule, OqIconComponent, OqFiltrosAvancadosComponent, OqViewToggleComponent],
  templateUrl: './oq-toolbar.component.html',
  styleUrl: './oq-toolbar.component.scss',
})
export class OqToolbarComponent {
  @Input() total = 0;
  @Input() visiveis = 0;
  @Input() filtroAtivo: FiltroStatus = 'todos';
  @Input() termoBusca = '';
  @Input() filtrosAvancadosAtivos = 0;
  @Input() dropdownFiltrosAberto = false;
  @Input() filtrosAvancados: FiltrosAvancados = {
    codigoParceiro: null,
    codigoVendedor: null,
    codigoTipoOperacao: null,
    ordemCarga: null,
    vinculoOrdemCarga: 'todos',
  };
  @Input() opcoesParceiros: OpcaoComCodigo[] = [];
  @Input() opcoesVendedores: OpcaoComCodigo[] = [];
  @Input() opcoesTiposOperacao: OpcaoComCodigo[] = [];
  /** Conferência por etapa (V29) — chips de tipo de separação só aparecem se true. */
  @Input() temSegmentacao = false;
  @Input() tiposSeparacaoSelecionados: ReadonlySet<number> = new Set();
  /** Cards (grid atual) ou lista (tabela) — só muda a renderização, não os dados. */
  @Input() viewMode: ViewMode = 'cards';

  @Output() filtroChange = new EventEmitter<FiltroStatus>();
  @Output() tipoSeparacaoToggle = new EventEmitter<number>();
  @Output() termoBuscaChange = new EventEmitter<string>();
  @Output() abrirFiltros = new EventEmitter<void>();
  @Output() aplicarFiltrosAvancados = new EventEmitter<FiltrosAvancados>();
  @Output() fecharFiltrosAvancados = new EventEmitter<void>();
  @Output() viewModeChange = new EventEmitter<ViewMode>();


  readonly pills: PillFiltro[] = [
    { valor: 'todos', label: 'Todos' },
    { valor: 'aguardando', label: 'Aguard.' },
    { valor: 'andamento', label: 'Andam.' },
    { valor: 'aguardando_corte', label: 'Corte' },
  ];

  readonly tiposSeparacao = TIPOS_SEPARACAO;

  onBuscaInput(valor: string): void {
    this.termoBuscaChange.emit(valor);
  }
}
