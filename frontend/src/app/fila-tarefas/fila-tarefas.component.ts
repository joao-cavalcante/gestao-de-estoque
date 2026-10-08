import { Component, ElementRef, Injector, ViewChild, afterNextRender, computed, effect, inject, OnDestroy, OnInit, signal } from '@angular/core';
import { OqConferidosChecklistComponent } from '../reconferencia/oq-conferidos-checklist.component';
import { ReconferenciaDetalhe, ReconferenciaService } from '../reconferencia/reconferencia.service';
import { FormsModule } from '@angular/forms';
import { ActivatedRoute, Router } from '@angular/router';
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
import {
  aCarregar,
  semNota,
  CampoOrdenacao,
  EscopoOc,
  FASES,
  FasePedido,
  faseTarefa,
  FILTROS_STATUS,
  FiltroStatus,
  FiltrosAvancados,
  OpcaoComCodigo,
  Ordenacao,
  ROTULO_FASE,
  Tarefa,
  ViewMode,
} from './tarefa.model';
import { OqFechamentoOcModalComponent } from './oq-fechamento-oc-modal/oq-fechamento-oc-modal.component';
import { OqIconComponent } from '../shared/icons/oq-icon.component';
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
    OqConferidosChecklistComponent,
    FormsModule,
    OqKpiBarComponent,
    OqToolbarComponent,
    OqTaskCardComponent,
    OqTaskListComponent,
    OqEmptyStateComponent,
    OqSkeletonComponent,
    OqPaginacaoComponent,
    OqFechamentoOcModalComponent,
    OqIconComponent,
  ],
  templateUrl: './fila-tarefas.component.html',
  styleUrl: './fila-tarefas.component.scss',
})
export class FilaTarefasComponent implements OnInit, OnDestroy {
  private readonly conferenciasService = inject(ConferenciasService);
  private readonly router = inject(Router);
  private readonly rota = inject(ActivatedRoute);
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
    // Vindo do fim da conferência ("Ir para a OC"): abre a fila já dentro da OC do pedido.
    const oc = Number(this.rota.snapshot.queryParamMap.get('oc'));
    if (oc > 0) {
      this.selecionarOc(oc);
      this.router.navigate([], { queryParams: {}, replaceUrl: true });
    }
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
   * Conferência ainda em andamento no chão (CONFERIR/CORTE) — base das opções dos filtros avançados
   * e da detecção de tenant segmentado. As fases depois da conferência entram por [tarefasDoEscopo].
   */
  private readonly tarefasAtivas = computed(() => this.tarefas().filter((t) => t.status !== 'concluido'));

  /** OC do filtro (null = sem OC escolhida). */
  readonly ocFiltrada = computed(() => this.filtrosAvancados().ordemCarga?.trim() || null);

  /**
   * Escopo da fila — a OC é o "lugar de trabalho" de quem separa (usuário, 08/10/2026): ou todas, ou
   * uma OC, ou só os pedidos sem OC (retira/express). Fica no mesmo estado salvo dos filtros avançados.
   */
  readonly escopo = computed<EscopoOc>(() => {
    const oc = this.ocFiltrada();
    if (oc) return { tipo: 'oc', oc };
    return this.filtrosAvancados().vinculoOrdemCarga === 'sem' ? { tipo: 'sem' } : { tipo: 'todas' };
  });

  /**
   * Pedidos que valem no escopo, pela fase do fluxo (conferir → corte → carregar → nota → pronto):
   * - dentro da OC: tudo da OC (inclusive PRONTO, até a OC ser fechada no Sankhya);
   * - sem OC: conferir, corte e nota (não há carregamento controlado);
   * - todas: tudo que ainda não terminou (conferência, corte, carregamento, fechamento da OC, nota).
   */
  private readonly tarefasDoEscopo = computed(() => {
    const e = this.escopo();
    return this.tarefas().filter((t) => {
      const fase = faseTarefa(t);
      if (e.tipo === 'oc') return String(t.ordemCarga ?? '') === e.oc && !t.ordemCargaFechada;
      if (e.tipo === 'sem') return t.ordemCarga == null && fase !== 'pronto' && fase !== 'carregar';
      // Todas: tudo que ainda não terminou — inclusive carregamento e fechamento de OC (usuário, 08/10/2026:
      // pedido não carregado e sem nota sumia da fila geral). Concluído e OC fechada ficam de fora.
      return fase !== 'pronto' && !t.ordemCargaFechada;
    });
  });

  /** Pedidos da OC aberta a carregar ("Carregar tudo" / "Ver itens" da OC). */
  private readonly tarefasACarregar = computed(() => {
    const oc = this.ocFiltrada();
    if (!oc) return [];
    return this.tarefas().filter((t) => aCarregar(t) && String(t.ordemCarga) === oc);
  });

  /** Progresso de um conjunto de pedidos pelas etapas do fluxo. */
  private progresso(lista: Tarefa[]) {
    const fases = lista.map(faseTarefa);
    const conta = (f: FasePedido) => fases.filter((x) => x === f).length;
    const conferidos = lista.length - conta('conferir') - conta('corte');
    const carregados = conta('fechamento') + conta('nota') + conta('pronto');
    return { pedidos: lista.length, conferidos, carregados, notas: conta('pronto'), aCarregar: conta('carregar'), corte: conta('corte') };
  }

  /**
   * OCs abertas para o seletor do topo: com pedido em andamento, ou com tudo pronto há pouco (até 2 dias,
   * esperando o "Fechar OC"). OC fechada no Sankhya some. Mais nova primeiro.
   */
  readonly ocsAbertas = computed(() => {
    const limite = Number(new Date(Date.now() - 2 * 86_400_000).toISOString().slice(0, 10).replaceAll('-', ''));
    const porOc = new Map<number, Tarefa[]>();
    for (const t of this.tarefas()) {
      if (t.ordemCarga == null || t.ordemCargaFechada) continue;
      porOc.set(t.ordemCarga, [...(porOc.get(t.ordemCarga) ?? []), t]);
    }
    return [...porOc.entries()]
      .map(([oc, lista]) => ({
        oc,
        motorista: lista.find((t) => t.motorista)?.motorista ?? null,
        ...this.progresso(lista),
        recente: lista.some((t) => chaveData(t.data) >= limite),
      }))
      .filter((o) => o.notas < o.pedidos || o.recente)
      // Em andamento (já tem pedido conferido e ainda falta algo) primeiro; depois a mais nova.
      .sort((a, b) => Number(b.conferidos > 0 && b.notas < b.pedidos) - Number(a.conferidos > 0 && a.notas < a.pedidos) || b.oc - a.oc);
  });

  // ─── Seletor de OC com busca (dezenas de OCs abertas não cabem em botões) ───
  /** Texto do campo "OC": número da OC ou nome do motorista. */
  readonly buscaOc = signal('');
  /** "Ver todas": lista vertical com todas as OCs abertas. */
  readonly listaOcsAberta = signal(false);

  /** Resultado da busca (máx. 8) — ou todas, com "Ver todas". */
  readonly ocsSugeridas = computed(() => {
    const termo = this.buscaOc().trim().toLowerCase();
    const todas = this.ocsAbertas();
    if (!termo) return this.listaOcsAberta() ? todas : [];
    return todas
      .filter((o) => String(o.oc).includes(termo) || !!o.motorista?.toLowerCase().includes(termo))
      .sort((a, b) => Number(String(b.oc).startsWith(termo)) - Number(String(a.oc).startsWith(termo)))
      .slice(0, 8);
  });

  /** Enter no campo: abre a OC digitada (número exato) ou a única sugestão. */
  abrirOcDigitada(): void {
    const termo = this.buscaOc().trim();
    const exata = this.ocsAbertas().find((o) => String(o.oc) === termo);
    const unica = this.ocsSugeridas().length === 1 ? this.ocsSugeridas()[0] : null;
    const alvo = exata ?? unica;
    if (alvo) this.selecionarOc(alvo.oc);
  }

  /** Pedidos sem OC ainda com trabalho (conferir/corte/nota) — botão "Sem OC" do seletor. */
  readonly pendentesSemOc = computed(
    () => this.tarefas().filter((t) => t.ordemCarga == null && ['conferir', 'corte', 'nota'].includes(faseTarefa(t))).length,
  );

  /** Cabeçalho da OC aberta: transporte + régua conferidos → carregados → notas → Fechar OC. */
  readonly resumoOc = computed(() => {
    const oc = this.ocFiltrada();
    if (!oc) return null;
    const daOc = this.tarefas().filter((t) => String(t.ordemCarga ?? '') === oc);
    if (daOc.length === 0) return null;
    const p = this.progresso(daOc);
    const faltam = (f: (t: Tarefa) => boolean) => daOc.filter(f).map((t) => t.numeroUnico);
    return {
      oc,
      fechada: daOc.some((t) => t.ordemCargaFechada),
      motorista: daOc.find((t) => t.motorista)?.motorista ?? null,
      transporte: daOc.find((t) => t.transporte !== '—')?.transporte ?? null,
      ...p,
      // Pronta pra fechar = tudo conferido e carregado (a nota sai no próprio Fechar OC).
      completa: p.carregados === p.pedidos,
      faltaConferir: faltam((t) => ['conferir', 'corte'].includes(faseTarefa(t))),
      faltaCarregar: faltam((t) => faseTarefa(t) === 'carregar'),
    };
  });

  /** Contagem por fase nas pílulas (substitui a faixa de KPIs). */
  readonly contagensFase = computed(() => {
    const k = this.kpis();
    return { todos: k.total, conferir: k.conferir, corte: k.corte, carregar: k.carregar, nota: k.nota };
  });

  /** KPIs = fases do escopo atual (dentro da OC, os números são daquela OC). */
  readonly kpis = computed(() => {
    const fases = this.tarefasDoEscopo().map(faseTarefa);
    const conta = (f: FasePedido) => fases.filter((x) => x === f).length;
    return { total: fases.length, conferir: conta('conferir'), corte: conta('corte'), carregar: conta('carregar'), nota: conta('nota') + conta('fechamento') };
  });

  selecionarOc(oc: number | null): void {
    this.buscaOc.set('');
    this.listaOcsAberta.set(false);
    this.filtrosAvancados.set({ ...this.filtrosAvancados(), ordemCarga: oc == null ? null : String(oc), vinculoOrdemCarga: 'todos' });
    this.filtroAtivo.set('todos');
    this.paginaAtual.set(1);
  }

  selecionarSemOc(): void {
    this.filtrosAvancados.set({ ...this.filtrosAvancados(), ordemCarga: null, vinculoOrdemCarga: 'sem' });
    this.filtroAtivo.set('todos');
    this.paginaAtual.set(1);
  }

  /** Toque numa etapa da régua da OC: mostra só os pedidos daquela fase (de novo = todos). */
  filtrarFase(fase: FiltroStatus): void {
    this.filtroAtivo.set(this.filtroAtivo() === fase ? 'todos' : fase);
    this.paginaAtual.set(1);
  }

  readonly rotuloFase = ROTULO_FASE;
  /** Seção "Pronto" da OC começa ABERTA — recolhida, o pedido sumia da tela e a régua (1/4) não batia com os cards. */
  readonly prontosAbertos = signal(true);

  /** Dentro da OC: os pedidos agrupados pela fase, na ordem do fluxo (sem paginação — uma OC é pequena). */
  readonly gruposOc = computed(() => {
    if (this.escopo().tipo !== 'oc') return [];
    const lista = this.tarefasOrdenadas();
    return FASES.map((fase) => ({ fase, tarefas: lista.filter((t) => faseTarefa(t) === fase) })).filter((g) => g.tarefas.length > 0);
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

    return this.tarefasDoEscopo().filter((t) => {
      // "Nota" = sem nota confirmada: gerar no card (sem OC) ou no fechamento da OC.
      const passaFiltro = filtro === 'todos' || (filtro === 'nota' ? semNota(t) : faseTarefa(t) === filtro);

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
        // OC / sem OC já são o escopo (tarefasDoEscopo); "só com OC" continua valendo na visão de todas.
        (avancados.vinculoOrdemCarga !== 'com' || t.ordemCarga != null);

      // Filtro de tipo de separação: em conferência, passa se tem etapa PENDENTE do tipo; depois de conferido,
      // passa se o pedido TEM itens do tipo (ex.: Refrigerado a carregar — antes sumia com o filtro ligado).
      const emConferencia = faseTarefa(t) === 'conferir' || faseTarefa(t) === 'corte';
      const passaTipoSeparacao =
        tipos.size === 0 || (t.etapas ?? []).some((e) => tipos.has(e.tipo) && (!emConferencia || e.status === 'P'));

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

  // ─── Carregamento na própria fila (pop-up com o checklist do "Ver conferidos") ───
  private readonly reconferenciaService = inject(ReconferenciaService);
  /** Pop-up aberto: um bloco por pedido (o "Carregar OC" traz todos os da OC que faltam). */
  readonly carregamento = signal<{
    titulo: string;
    blocos: { nunota: string; cliente: string; detalhe: ReconferenciaDetalhe | null; erro: string | null }[];
  } | null>(null);

  /** "✓ Carregado" do card: um toque, pedido inteiro. */
  readonly salvandoCarga = signal(false);
  onCarregado(tarefa: Tarefa): void {
    this.darBaixaCarregamento([tarefa.numeroUnico]);
  }

  /** "✓ Carregar tudo" da faixa: todos os pedidos a carregar da OC, de uma vez. */
  carregarTudoOc(): void {
    this.darBaixaCarregamento(this.tarefasACarregar().map((t) => t.numeroUnico));
  }

  // ─── Nota por pedido (fase NOTA): modal de faturamento com a TOP do pedido ───
  /** Pedido sem OC: "Gerar nota" com a mesma lógica do Fechar OC (TOP automática, fatura e confirma). */
  readonly faturamento = signal<number | null>(null);

  onFaturar(tarefa: Tarefa): void {
    const nunota = Number(tarefa.numeroUnico);
    if (nunota > 0) this.faturamento.set(nunota);
  }

  // ─── Fechar OC: fatura + confirma as notas dos pedidos e fecha a OC no Sankhya ───
  readonly fechamentoOc = signal<number | null>(null);

  abrirFechamentoOc(): void {
    const oc = Number(this.ocFiltrada());
    if (oc > 0) this.fechamentoOc.set(oc);
  }

  fecharFechamentoOc(): void {
    this.fechamentoOc.set(null);
    this.carregarFila();
  }

  /** Fecha e relê a fila — pedido com nota confirmada passa pra PRONTO. */
  fecharFaturamento(): void {
    this.faturamento.set(null);
    this.carregarFila();
  }

  private darBaixaCarregamento(nunotas: string[]): void {
    if (nunotas.length === 0 || this.salvandoCarga()) return;
    this.salvandoCarga.set(true);
    this.reconferenciaService.carregarPedidos(nunotas).subscribe({
      next: () => {
        this.salvandoCarga.set(false);
        this.carregarFila();
      },
      error: () => this.salvandoCarga.set(false),
    });
  }

  /** "Ver itens" da OC: checklist (opcional) de todos os pedidos a carregar. */
  carregarOc(): void {
    const oc = this.ocFiltrada();
    if (!oc) return;
    const pedidos = this.tarefasACarregar();
    if (pedidos.length === 0) return;
    this.abrirCarregamento(`Carregamento · OC ${oc}`, pedidos);
  }

  private abrirCarregamento(titulo: string, pedidos: Tarefa[]): void {
    const comSessao = pedidos.filter((t) => !!t.carregamento?.sessaoId);
    this.carregamento.set({
      titulo,
      blocos: comSessao.map((t) => ({ nunota: t.numeroUnico, cliente: t.cliente, detalhe: null, erro: null })),
    });
    comSessao.forEach((t, idx) => {
      this.reconferenciaService.detalhe(t.carregamento!.sessaoId!).subscribe({
        next: (d) => this.atualizarBloco(idx, { detalhe: d }),
        error: () => this.atualizarBloco(idx, { erro: 'Não foi possível carregar os itens deste pedido.' }),
      });
    });
  }

  private atualizarBloco(idx: number, patch: { detalhe?: ReconferenciaDetalhe; erro?: string }): void {
    const atual = this.carregamento();
    if (!atual) return;
    this.carregamento.set({ ...atual, blocos: atual.blocos.map((b, i) => (i === idx ? { ...b, ...patch } : b)) });
  }

  /** Fecha e relê a fila — o pedido todo carregado sai da lista e a faixa da OC atualiza. */
  fecharCarregamento(): void {
    this.carregamento.set(null);
    this.carregarFila();
  }

  aplicarFiltrosAvancados(filtros: FiltrosAvancados): void {
    this.filtrosAvancados.set(filtros);
    this.paginaAtual.set(1);
    this.dropdownFiltrosAberto.set(false);
  }

  onConferir(evento: Tarefa | { tarefa: Tarefa; etapa?: number }): void {
    const tarefa = 'tarefa' in evento ? evento.tarefa : evento;
    const etapa = 'tarefa' in evento ? evento.etapa : undefined;
    // Conferida a carregar: abre o checklist de carregamento (o "Ver conferidos" da nota).
    if (aCarregar(tarefa) && tarefa.carregamento?.sessaoId) {
      this.abrirCarregamento(`Carregamento · ${tarefa.cliente}`, [tarefa]);
      return;
    }
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
