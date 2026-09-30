import { Component, ElementRef, Injector, ViewChild, afterNextRender, computed, effect, inject, OnDestroy, OnInit, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { Router } from '@angular/router';
import { Subscription } from 'rxjs';
import { SyncTickService } from '../shared/app-header/sync-tick.service';
import { AuthService } from '../auth/auth.service';
import { OqKpiBarComponent } from './oq-kpi-bar/oq-kpi-bar.component';
import { OqToolbarComponent } from './oq-toolbar/oq-toolbar.component';
import { OqTaskCardComponent } from './oq-task-card/oq-task-card.component';
import { OqTaskListComponent } from './oq-task-list/oq-task-list.component';
import { OqEmptyStateComponent } from './oq-empty-state/oq-empty-state.component';
import { OqSkeletonComponent } from '../shared/oq-skeleton/oq-skeleton.component';
import { ConferenciasService } from './conferencias.service';
import { CampoOrdenacao, FILTROS_STATUS, FiltroStatus, FiltrosAvancados, OpcaoComCodigo, Ordenacao, Tarefa, ViewMode } from './tarefa.model';
import { FiltrosSalvosService } from '../shared/filtros-salvos.service';
import { OqPaginacaoComponent } from '../shared/lista-layout/oq-paginacao.component';
import { ITENS_POR_PAGINA, itensValidosPara, lerViewMode, salvarViewMode } from '../shared/lista-layout/view-mode';

/** O que a fila lembra por usuário — tudo menos a busca por texto (e a página atual). */
interface FiltrosFilaSalvos {
  status: FiltroStatus;
  tiposSeparacao: number[];
  modalidades?: string[];
  avancados: FiltrosAvancados;
  itensPorPagina: number;
}

const TELA_FILTROS = 'fila-tarefas';
/** Preferência de visualização — do navegador, não do usuário (pedido: chave fixa no localStorage). */
const CHAVE_VIEW_MODE = 'fila-view-mode';
const TODOS_ITENS_POR_PAGINA = [10, 20, 50, 100];

/** "28/09/2026 ..." → 20260928 pra ordenar por data; formato desconhecido cai no fim. */
function chaveData(data: string): number {
  const m = /^(\d{2})\/(\d{2})\/(\d{4})/.exec(data.trim());
  return m ? Number(m[3] + m[2] + m[1]) : Number.MAX_SAFE_INTEGER;
}

@Component({
  selector: 'app-fila-tarefas',
  standalone: true,
  imports: [
    FormsModule,
    OqKpiBarComponent,
    OqToolbarComponent,
    OqTaskCardComponent,
    OqTaskListComponent,
    OqEmptyStateComponent,
    OqSkeletonComponent,
    OqPaginacaoComponent,
  ],
  templateUrl: './fila-tarefas.component.html',
  styleUrl: './fila-tarefas.component.scss',
})
export class FilaTarefasComponent implements OnInit, OnDestroy {
  private readonly conferenciasService = inject(ConferenciasService);
  private readonly router = inject(Router);
  readonly syncTick = inject(SyncTickService);
  private readonly authService = inject(AuthService);
  private readonly injector = inject(Injector);
  private syncSub?: Subscription;

  /** Cards (grid) ou lista (tabela) — só troca o template; dados, filtros e paginação são os mesmos. */
  readonly viewMode = signal<ViewMode>(lerViewMode(CHAVE_VIEW_MODE));
  /** Ordenação pelos cabeçalhos da lista; vale pros dois modos (null = ordem do backend). */
  readonly ordenacao = signal<Ordenacao | null>(null);
  readonly opcoesItensPorPagina = computed(() => ITENS_POR_PAGINA[this.viewMode()]);

  /** Área que rola (grid de cards ou lista) — pra devolver a posição ao voltar pra um modo. */
  @ViewChild('rolagem') private rolagem?: ElementRef<HTMLElement>;
  private readonly scrollPorModo: Partial<Record<ViewMode, number>> = {};

  private get tenantAtual(): string {
    return this.authService.obterTenantSlug() ?? '';
  }

  carregando = signal(true);
  erro = signal<string | null>(null);

  private readonly tarefas = signal<Tarefa[]>([]);

  filtroAtivo = signal<FiltroStatus>('todos');
  termoBusca = signal('');
  paginaAtual = signal(1);
  itensPorPagina = signal(20);

  /** Filtro de tipo de separação (V29) — só aparece quando há tarefas segmentadas. Multi-select. */
  filtroTipoSeparacao = signal<ReadonlySet<number>>(new Set());
  /** Filtro rápido de modalidade (AD_EXPRESS / AD_RETIRA / AD_ENTREGA) — passa se tiver QUALQUER uma selecionada. */
  filtroModalidade = signal<ReadonlySet<string>>(new Set());
  dropdownFiltrosAberto = signal(false);
  filtrosAvancados = signal<FiltrosAvancados>({
    codigoParceiro: null,
    codigoVendedor: null,
    codigoTipoOperacao: null,
    ordemCarga: null,
    vinculoOrdemCarga: 'todos',
  });

  private readonly filtrosSalvos = inject(FiltrosSalvosService);

  /**
   * Restaura os filtros do usuário (antes do 1º effect rodar, pra não gravar o
   * padrão por cima) e grava a cada mudança — voltar da conferência não perde o filtro.
   */
  private readonly restaurado = this.restaurarFiltros();
  private readonly efeitoSalvarFiltros = effect(() => {
    this.filtrosSalvos.salvar<FiltrosFilaSalvos>(TELA_FILTROS, {
      status: this.filtroAtivo(),
      tiposSeparacao: [...this.filtroTipoSeparacao()],
      modalidades: [...this.filtroModalidade()],
      avancados: this.filtrosAvancados(),
      itensPorPagina: this.itensPorPagina(),
    });
  });

  private restaurarFiltros(): boolean {
    const f = this.filtrosSalvos.ler<FiltrosFilaSalvos>(TELA_FILTROS);
    if (!f) return false;
    if (f.status && (FILTROS_STATUS as readonly string[]).includes(f.status)) this.filtroAtivo.set(f.status);
    if (Array.isArray(f.tiposSeparacao)) this.filtroTipoSeparacao.set(new Set(f.tiposSeparacao.filter((t) => typeof t === 'number')));
    if (Array.isArray(f.modalidades)) this.filtroModalidade.set(new Set(f.modalidades.filter((m) => ['express', 'retira', 'entrega'].includes(m))));
    if (f.avancados) {
      // Filtro salvo antes do Com/Sem OC: a caixa "Somente com Ordem de Carga" marcada vira 'com'.
      const { somenteComOrdemCarga, ...resto } = f.avancados as FiltrosAvancados & { somenteComOrdemCarga?: boolean };
      const vinculo = (['todos', 'com', 'sem'] as const).includes(resto.vinculoOrdemCarga)
        ? resto.vinculoOrdemCarga
        : somenteComOrdemCarga ? 'com' : 'todos';
      this.filtrosAvancados.set({ ...this.filtrosAvancados(), ...resto, vinculoOrdemCarga: vinculo });
    }
    if (f.itensPorPagina && TODOS_ITENS_POR_PAGINA.includes(f.itensPorPagina)) {
      this.itensPorPagina.set(itensValidosPara(this.viewMode(), f.itensPorPagina));
    }
    return true;
  }

  ngOnInit(): void {
    this.carregarFila();
    this.syncSub = this.syncTick.onTick.subscribe(() => this.carregarFila());
  }

  ngOnDestroy(): void {
    this.syncSub?.unsubscribe();
  }

  carregarFila(): void {
    this.carregando.set(true);
    this.erro.set(null);
    this.conferenciasService.listarFila(this.tenantAtual).subscribe({
      next: (tarefas) => {
        this.tarefas.set(tarefas);
        this.carregando.set(false);
      },
      error: (err) => {
        this.erro.set(err?.error?.erro ?? 'Falha ao buscar a fila no Sankhya.');
        this.carregando.set(false);
      },
    });
  }

  /**
   * A fila é uma FILA DE TRABALHO — só tarefas acionáveis: aguardando
   * conferência, conferência em andamento e aguardando corte (recontagem
   * entra como aguardando/andamento). Concluídas e canceladas não aparecem.
   */
  private readonly tarefasAtivas = computed(() => this.tarefas().filter((t) => t.status !== 'concluido'));

  readonly kpis = computed(() => {
    const todas = this.tarefasAtivas();
    return {
      total: todas.length,
      aguardando: todas.filter((t) => t.status === 'aguardando').length,
      andamento: todas.filter((t) => t.status === 'andamento').length,
      aguardandoCorte: todas.filter((t) => t.status === 'aguardando_corte').length,
    };
  });

  /** Opções "cód - descrição" de cada select do painel avançado — só o que já aparece na fila carregada. */
  readonly opcoesParceiros = computed(() => this.opcoesComCodigo((t) => t.codigoCliente, (t) => t.cliente));
  readonly opcoesVendedores = computed(() => this.opcoesComCodigo((t) => t.codigoResponsavel, (t) => t.responsavel));
  readonly opcoesTiposOperacao = computed(() => this.opcoesComCodigo((t) => t.codigoTipoOperacao, (t) => t.tipoOperacao));

  private opcoesComCodigo(
    extrairCodigo: (t: Tarefa) => string | null,
    extrairLabel: (t: Tarefa) => string,
  ): OpcaoComCodigo[] {
    const vistos = new Map<string, string>();
    for (const t of this.tarefasAtivas()) {
      const codigo = extrairCodigo(t);
      const label = extrairLabel(t);
      if (codigo && label && label !== '—' && !vistos.has(codigo)) vistos.set(codigo, label);
    }
    return [...vistos.entries()]
      .map(([codigo, label]) => ({ codigo, label }))
      .sort((a, b) => a.label.localeCompare(b.label));
  }

  readonly totalFiltrosAvancadosAtivos = computed(() => {
    const f = this.filtrosAvancados();
    return (
      [f.codigoParceiro, f.codigoVendedor, f.codigoTipoOperacao, f.ordemCarga].filter((v) => v !== null).length +
      (f.vinculoOrdemCarga !== 'todos' ? 1 : 0)
    );
  });

  /** true = alguma tarefa carregada tem etapas → tenant segmentado (V29). */
  readonly temSegmentacao = computed(() => this.tarefasAtivas().some((t) => (t.etapas?.length ?? 0) > 0));

  readonly tarefasFiltradas = computed(() => {
    const filtro = this.filtroAtivo();
    const termo = this.termoBusca().trim().toLowerCase();
    const avancados = this.filtrosAvancados();
    const tipos = this.filtroTipoSeparacao();
    const modalidades = this.filtroModalidade();

    return this.tarefasAtivas().filter((t) => {
      const passaFiltro = filtro === 'todos' || t.status === filtro;

      const passaBusca =
        !termo ||
        t.cliente.toLowerCase().includes(termo) ||
        t.pedido.toLowerCase().includes(termo) ||
        t.nf.toLowerCase().includes(termo) ||
        t.numeroUnico.toLowerCase().includes(termo);

      const passaAvancados =
        (!avancados.codigoParceiro || t.codigoCliente === avancados.codigoParceiro) &&
        (!avancados.codigoVendedor || t.codigoResponsavel === avancados.codigoVendedor) &&
        (!avancados.codigoTipoOperacao || t.codigoTipoOperacao === avancados.codigoTipoOperacao) &&
        (!avancados.ordemCarga || String(t.ordemCarga ?? '') === avancados.ordemCarga.trim()) &&
        (avancados.vinculoOrdemCarga === 'todos' ||
          (avancados.vinculoOrdemCarga === 'com' ? t.ordemCarga != null : t.ordemCarga == null));

      // Filtro de tipo de separação: passa se tem etapa PENDENTE de algum tipo selecionado.
      const passaTipoSeparacao =
        tipos.size === 0 || (t.etapas ?? []).some((e) => e.status === 'P' && tipos.has(e.tipo));

      const passaModalidade =
        modalidades.size === 0 ||
        (modalidades.has('express') && t.express) ||
        (modalidades.has('retira') && t.retira) ||
        (modalidades.has('entrega') && t.entrega);

      return passaFiltro && passaBusca && passaAvancados && passaTipoSeparacao && passaModalidade;
    });
  });

  /** Filtradas + ordenadas — base da paginação (e do contador) nos dois modos. */
  readonly tarefasOrdenadas = computed(() => {
    const lista = this.tarefasFiltradas();
    const ord = this.ordenacao();
    if (!ord) return lista;
    const fator = ord.direcao === 'asc' ? 1 : -1;
    const chave = (t: Tarefa): string | number => {
      switch (ord.campo) {
        case 'cliente': return t.cliente.toLocaleLowerCase('pt-BR');
        case 'numeroUnico': return Number(t.numeroUnico) || 0;
        case 'nf': return t.nf;
        case 'data': return chaveData(t.data);
        case 'itens': return t.itens;
      }
    };
    // sort estável: empate mantém a ordem do backend
    return [...lista].sort((a, b) => {
      const ka = chave(a);
      const kb = chave(b);
      const cmp = typeof ka === 'number' && typeof kb === 'number' ? ka - kb : String(ka).localeCompare(String(kb), 'pt-BR');
      return cmp * fator;
    });
  });

  readonly totalPaginas = computed(() =>
    Math.max(1, Math.ceil(this.tarefasFiltradas().length / this.itensPorPagina())),
  );

  readonly tarefasPaginadas = computed(() => {
    const inicio = (this.paginaAtual() - 1) * this.itensPorPagina();
    return this.tarefasOrdenadas().slice(inicio, inicio + this.itensPorPagina());
  });

  irParaPagina(pagina: number): void {
    this.paginaAtual.set(Math.min(Math.max(1, pagina), this.totalPaginas()));
  }

  onFiltroChange(filtro: FiltroStatus): void {
    this.filtroAtivo.set(filtro);
    this.paginaAtual.set(1);
  }

  onBuscaChange(termo: string): void {
    this.termoBusca.set(termo);
    this.paginaAtual.set(1);
  }

  /** Alterna um tipo de separação no filtro (V29). */
  onTipoSeparacaoToggle(tipo: number): void {
    const atual = new Set(this.filtroTipoSeparacao());
    if (atual.has(tipo)) atual.delete(tipo);
    else atual.add(tipo);
    this.filtroTipoSeparacao.set(atual);
    this.paginaAtual.set(1);
  }

  /** Alterna uma modalidade no filtro rápido (Express / Retira / Entrega). */
  onModalidadeToggle(modalidade: string): void {
    const atual = new Set(this.filtroModalidade());
    if (atual.has(modalidade)) atual.delete(modalidade);
    else atual.add(modalidade);
    this.filtroModalidade.set(atual);
    this.paginaAtual.set(1);
  }

  onItensPorPaginaChange(valor: number): void {
    this.itensPorPagina.set(valor);
    this.paginaAtual.set(1);
  }

  /**
   * Troca cards ↔ lista sem ir ao backend. Mantém o 1º pedido visível na página (se o modo novo
   * não tem a mesma opção de itens/página, recalcula a página) e devolve a rolagem de quando
   * aquele modo foi visto por último.
   */
  onViewModeChange(modo: ViewMode): void {
    const atual = this.viewMode();
    if (modo === atual) return;
    if (this.rolagem) this.scrollPorModo[atual] = this.rolagem.nativeElement.scrollTop;

    const primeiroItem = (this.paginaAtual() - 1) * this.itensPorPagina();
    const novoPorPagina = itensValidosPara(modo, this.itensPorPagina());
    this.viewMode.set(modo);
    if (novoPorPagina !== this.itensPorPagina()) {
      this.itensPorPagina.set(novoPorPagina);
      this.paginaAtual.set(Math.floor(primeiroItem / novoPorPagina) + 1);
    }
    salvarViewMode(CHAVE_VIEW_MODE, modo);
    afterNextRender(
      () => {
        if (this.rolagem) this.rolagem.nativeElement.scrollTop = this.scrollPorModo[modo] ?? 0;
      },
      { injector: this.injector },
    );
  }

  /** Clique no cabeçalho: asc → desc → sem ordenação (volta à ordem do backend). */
  onOrdenar(campo: CampoOrdenacao): void {
    const atual = this.ordenacao();
    if (atual?.campo !== campo) this.ordenacao.set({ campo, direcao: 'asc' });
    else if (atual.direcao === 'asc') this.ordenacao.set({ campo, direcao: 'desc' });
    else this.ordenacao.set(null);
    this.paginaAtual.set(1);
  }

  abrirFiltrosAvancados(): void {
    this.dropdownFiltrosAberto.update((v) => !v);
  }

  aplicarFiltrosAvancados(filtros: FiltrosAvancados): void {
    this.filtrosAvancados.set(filtros);
    this.paginaAtual.set(1);
    this.dropdownFiltrosAberto.set(false);
  }

  onConferir(evento: Tarefa | { tarefa: Tarefa; etapa?: number }): void {
    const tarefa = 'tarefa' in evento ? evento.tarefa : evento;
    const etapa = 'tarefa' in evento ? evento.etapa : undefined;
    // Conferência já cortada e aguardando liberação — o operador vai pra tela
    // de liberação de corte, não reabre a conferência.
    if (tarefa.status === 'aguardando_corte') {
      this.router.navigate(['/liberacao-corte']);
      return;
    }
    this.router.navigate(['/conferencia', tarefa.numeroUnico], {
      state: { tarefa },
      queryParams: etapa != null ? { etapa } : undefined,
    });
  }
}
