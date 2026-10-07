import { Component, OnDestroy, OnInit, computed, effect, inject, signal, untracked } from '@angular/core';
import { OqPedidoVendaComponent } from './oq-pedido-venda.component';
import { OqMapaKpisComponent } from './oq-mapa-kpis.component';
import { exportarMapaExcel } from './mapa-separacao.excel';
import { OqModalidadePinsComponent } from '../shared/oq-modalidade-pins/oq-modalidade-pins.component';
import { NgTemplateOutlet } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { Subscription, from } from 'rxjs';
import { mergeMap, toArray } from 'rxjs/operators';
import { SyncTickService } from '../shared/app-header/sync-tick.service';
import { FiltrosSalvosService } from '../shared/filtros-salvos.service';
import { OqIconComponent, OqIconName } from '../shared/icons/oq-icon.component';
import { OqSpinnerComponent } from '../shared/icons/oq-spinner.component';
import { OqSkeletonComponent } from '../shared/oq-skeleton/oq-skeleton.component';
import { OqViewToggleComponent } from '../shared/lista-layout/oq-view-toggle.component';
import { OqPaginacaoComponent } from '../shared/lista-layout/oq-paginacao.component';
import { ITENS_POR_PAGINA, ViewMode, itensValidosPara, lerViewMode, salvarViewMode } from '../shared/lista-layout/view-mode';
import { MapaSeparacaoService } from './mapa-separacao.service';
import {
  PedidoVenda,
  CategoriaSeparacaoDto,
  MapaSeparacaoDto,
  OrdemCargaResumoDto,
  PedidoSemOrdemCargaDto,
  formatarPeso,
  formatarQtd,
} from './mapa-separacao.model';

const ICONE_CATEGORIA: Record<string, OqIconName> = {
  '1': 'seco',
  '2': 'refrigerado',
  '3': 'congelado',
  '0': 'circle-alert',
};

/** Quantos mapas S/ OC buscar ao mesmo tempo no Sankhya (cada um é ~3 chamadas). */
const CONCORRENCIA_MAPAS = 3;

const CHAVE_VIEW_MODE = 'mapa-separacao-view-mode';

type SituacaoFiltro = 'todas' | 'nao-impressas' | 'impressas' | 'complementar';

/**
 * Mapa de Separação por Ordem de Carga — porte do Dashboard HTML5/JSP que
 * substituiu o iReport 513 no Sankhya (ver backend MapaSeparacaoService).
 * Classificação por TGFPRO.AD_TIPOSEPARACAO como no original, mas SEM a
 * quebra por nota (economia de papel): seco e congelado saem somados na OC
 * inteira, uma folha cada; refrigerado sai uma folha por cliente. Pesável
 * leva ícone de balança. Identidade visual do WMS (tokens de
 * styles.scss, ícones seco/refrigerado/congelado já usados na Fila de
 * Tarefas) em vez do CSS solto do JSP original.
 *
 * TELA DE CONTROLE, não busca solta: abre já mostrando as Ordens de Carga
 * ABERTAS (SITUACAO='A') e as FECHADAS no Sankhya que ainda têm nota na
 * fila de conferência (a OC é fechada antes da separação terminar) como cards (mesmo idioma visual de Fila de
 * Tarefas/Liberação de Corte/Impressão de Etiquetas) — o operador vê de
 * cara quantas tem pra separar, clica na que quer e vai direto pro
 * relatório/impressão. Busca ao vivo, sem cache/mirror.
 *
 * Painel no mesmo layout da Fila de Tarefas (barra, cards ↔ lista, paginação —
 * estilos globais em styles/_lista-layout.scss); o RELATÓRIO continua no layout
 * próprio (.ms-page), que é o que as regras de impressão A4 esperam.
 *
 * Filtro "S/ Ordem de Carga": troca o painel pelos PEDIDOS sem OC. Cada
 * Número Único vira um mapa próprio (mesmas categorias, soma só dentro do
 * pedido) — vários selecionados saem como mapas independentes, uma folha
 * nova por pedido, nunca consolidados.
 */
@Component({
  selector: 'app-mapa-separacao',
  standalone: true,
  imports: [FormsModule, OqIconComponent, OqSpinnerComponent, OqSkeletonComponent, NgTemplateOutlet, OqViewToggleComponent, OqPaginacaoComponent, OqModalidadePinsComponent, OqPedidoVendaComponent, OqMapaKpisComponent],
  templateUrl: './mapa-separacao.component.html',
  styleUrl: './mapa-separacao.component.scss',
})
export class MapaSeparacaoComponent implements OnInit, OnDestroy {
  private readonly service = inject(MapaSeparacaoService);
  private readonly syncTick = inject(SyncTickService);
  private readonly filtrosSalvos = inject(FiltrosSalvosService);

  /** "S/ Ordem de Carga" lembrado por usuário (busca por texto não é lembrada); padrão = com OC. */
  private semOrdemCargaSalvo(): boolean {
    return this.filtrosSalvos.ler<{ semOrdemCarga?: boolean }>('mapa-separacao')?.semOrdemCarga === true;
  }

  private salvarFiltros(): void {
    this.filtrosSalvos.salvar('mapa-separacao', {
      semOrdemCarga: this.semOrdemCarga,
      modalidades: [...this.filtroModalidade],
      situacao: this.filtroSituacao,
    });
  }

  // ─── Filtros rápidos (mesmos da Fila: Express, Cliente retira, Entrega) + "Não impressos" ───
  readonly modalidadesFiltro = [
    { id: 'express', label: 'Express', icone: 'express' },
    { id: 'retira', label: 'Cliente retira', icone: 'retira' },
    { id: 'entrega', label: 'Entrega', icone: 'entrega' },
  ] as const;
  filtroModalidade = new Set<string>(
    (this.filtrosSalvos.ler<{ modalidades?: string[] }>('mapa-separacao')?.modalidades ?? []).filter((m) =>
      ['express', 'retira', 'entrega'].includes(m),
    ),
  );
  /** Situação da impressão (substituiu o "Não impressos"); o antigo naoImpressos salvo vira 'nao-impressas'. */
  readonly opcoesSituacao: { id: SituacaoFiltro; label: string }[] = [
    { id: 'todas', label: 'Todas' },
    { id: 'nao-impressas', label: 'Não impressas' },
    { id: 'impressas', label: 'Impressas' },
    { id: 'complementar', label: 'Com complementar' },
  ];
  filtroSituacao: SituacaoFiltro = (() => {
    const salvo = this.filtrosSalvos.ler<{ situacao?: SituacaoFiltro; naoImpressos?: boolean }>('mapa-separacao');
    if (salvo?.situacao && ['todas', 'nao-impressas', 'impressas', 'complementar'].includes(salvo.situacao)) return salvo.situacao;
    return salvo?.naoImpressos ? 'nao-impressas' : 'todas';
  })();

  alternarModalidade(id: string): void {
    if (this.filtroModalidade.has(id)) this.filtroModalidade.delete(id);
    else this.filtroModalidade.add(id);
    this.filtroModalidade = new Set(this.filtroModalidade);
    this.pagina.set(1);
    this.salvarFiltros();
  }

  setSituacao(s: SituacaoFiltro): void {
    this.filtroSituacao = s;
    this.pagina.set(1);
    this.salvarFiltros();
  }

  private passaSituacaoOc(oc: OrdemCargaResumoDto): boolean {
    const novos = (oc.pedidosNovos?.length ?? 0) > 0;
    switch (this.filtroSituacao) {
      case 'nao-impressas': return !oc.impressoEm;
      case 'impressas': return !!oc.impressoEm && !novos;
      case 'complementar': return novos;
      default: return true;
    }
  }

  private passaSituacaoPedido(p: PedidoSemOrdemCargaDto): boolean {
    switch (this.filtroSituacao) {
      case 'nao-impressas': return !p.impressoEm;
      case 'impressas': return !!p.impressoEm;
      case 'complementar': return false; // complementar é só de OC
      default: return true;
    }
  }

  /** Quantidade de cada opção de situação — conta o que está na tela com a busca e a modalidade, sem a própria situação. */
  contagemSituacao(s: SituacaoFiltro): number {
    const atual = this.filtroSituacao;
    this.filtroSituacao = s;
    const n = this.semOrdemCarga ? this.pedidosSemOcFiltrados.length : this.listaFiltrada.length + this.pedidosSemOcFiltrados.length;
    this.filtroSituacao = atual;
    return n;
  }

  /** Quantidade de cada modalidade (OCs que têm pedido dela + pedidos sem OC dela), com busca e situação aplicadas. */
  contagemModalidade(m: string): number {
    const atual = this.filtroModalidade;
    this.filtroModalidade = new Set([m]);
    const n = this.semOrdemCarga ? this.pedidosSemOcFiltrados.length : this.listaFiltrada.length + this.pedidosSemOcFiltrados.length;
    this.filtroModalidade = atual;
    return n;
  }

  get temFiltroAtivo(): boolean {
    return this.filtroSituacao !== 'todas' || this.filtroModalidade.size > 0 || this.filtroLista.trim() !== '';
  }

  limparFiltros(): void {
    this.filtroSituacao = 'todas';
    this.filtroModalidade = new Set();
    this.filtroLista = '';
    this.pagina.set(1);
    this.salvarFiltros();
  }

  /** Passa no filtro de modalidade se tiver QUALQUER uma das marcadas (igual à Fila). */
  private passaModalidadeOc(oc: OrdemCargaResumoDto): boolean {
    const f = this.filtroModalidade;
    return f.size === 0 || (f.has('express') && (oc.qtdExpress ?? 0) > 0) || (f.has('retira') && (oc.qtdRetira ?? 0) > 0) ||
      (f.has('entrega') && (oc.qtdEntrega ?? 0) > 0);
  }

  private passaModalidadePedido(p: PedidoSemOrdemCargaDto): boolean {
    const f = this.filtroModalidade;
    const m = p.modalidade;
    return f.size === 0 || (f.has('express') && !!m?.express) || (f.has('retira') && !!m?.retira) || (f.has('entrega') && !!m?.entrega);
  }

  onSemOrdemCargaChange(): void {
    this.salvarFiltros();
    this.selecionados.set(new Set());
    this.pagina.set(1);
    this.erro.set(null);
    this.carregarPainel();
  }
  private syncSub?: Subscription;

  readonly abertas = signal<OrdemCargaResumoDto[]>([]);
  readonly carregandoAbertas = signal(true);
  readonly erroAbertas = signal<string | null>(null);
  filtroLista = '';
  /** true = painel mostra só pedidos SEM Ordem de Carga (um mapa por Número Único). */
  semOrdemCarga = this.semOrdemCargaSalvo();

  readonly pedidosSemOc = signal<PedidoSemOrdemCargaDto[]>([]);
  /** Números Únicos marcados pra gerar os mapas S/ OC de uma vez. */
  readonly selecionados = signal<Set<number>>(new Set());

  /** Mapas abertos: 1 (Ordem de Carga) ou N independentes (S/ Ordem de Carga, um por Número Único). */
  readonly mapas = signal<MapaSeparacaoDto[]>([]);

  /** Pedido de Venda (porte do Jasper) de cada Nº Único dos mapas abertos — impresso junto, um por pedido. */
  readonly pedidosVenda = signal<PedidoVenda[]>([]);
  readonly carregandoPedidosVenda = signal(false);
  private pedidosVendaSub?: Subscription;
  private readonly aoMudarMapas = effect(
    () => {
      const nunotas = [...new Set(this.mapas().flatMap((m) => m.nunotasPedidos ?? []))];
      untracked(() => this.buscarPedidosVenda(nunotas));
    },
    { allowSignalWrites: true },
  );

  private buscarPedidosVenda(nunotas: number[]): void {
    this.pedidosVendaSub?.unsubscribe();
    this.pedidosVenda.set([]);
    if (nunotas.length === 0) {
      this.carregandoPedidosVenda.set(false);
      return;
    }
    this.carregandoPedidosVenda.set(true);
    this.pedidosVendaSub = this.service.pedidosVenda(nunotas).subscribe({
      next: (lista) => {
        // Pedidos em ordem alfabética do cliente (mesmo nome do cabeçalho impresso: razão social, senão nome);
        // empate (mesmo cliente) pelo Nº Único.
        const cliente = (p: PedidoVenda) => (p.cabecalho?.['RAZAOSOCIAL'] || p.cabecalho?.['NOMEPARC'] || '').trim();
        this.pedidosVenda.set(
          [...lista].sort((a, b) => cliente(a).localeCompare(cliente(b), 'pt-BR', { sensitivity: 'base', numeric: true }) || a.nunota - b.nunota),
        );
        this.carregandoPedidosVenda.set(false);
      },
      error: () => {
        this.pedidosVenda.set(nunotas.map((n) => ({ nunota: n, cabecalho: {}, itens: [], parcelas: [], comissoes: [], erro: 'falha ao buscar o pedido no Sankhya' })));
        this.carregandoPedidosVenda.set(false);
      },
    });
  }
  readonly dados = computed(() => this.mapas()[0] ?? null);
  readonly carregando = signal(false);
  readonly erro = signal<string | null>(null);

  /**
   * Campo "fora da lista": OC (modo normal) ou Nº Único (modo S/ OC) que não aparece no painel —
   * ex.: já toda conferida (reimpressão), acabou de abrir no Sankhya, ou a lista falhou ao carregar.
   */
  /**
   * Busca única (antes eram 2 campos: busca + "OC fora da lista"): texto filtra a lista; um NÚMERO com
   * Enter abre direto o mapa daquela OC (ou Nº Único no modo S/ OC), mesmo fora da lista.
   */
  get numeroBusca(): number | null {
    const t = this.filtroLista.trim();
    if (!/^\d+$/.test(t)) return null;
    const n = Number(t);
    return n > 0 ? n : null;
  }

  readonly formatarQtd = formatarQtd;
  readonly formatarPeso = formatarPeso;

  /** Cards (grid) ou lista (tabela) no painel — preferência do navegador, só troca a renderização. */
  readonly viewMode = signal<ViewMode>(lerViewMode(CHAVE_VIEW_MODE));
  readonly opcoesItensPorPagina = computed(() => ITENS_POR_PAGINA[this.viewMode()]);
  readonly itensPorPagina = signal(itensValidosPara(this.viewMode(), 20));
  /** Paginação em memória do painel (1-based) — vale pra lista de OCs e pra de pedidos S/ OC. */
  readonly pagina = signal(1);

  /** Total da lista que está no painel agora (OCs ou pedidos S/ OC), já com a busca. */
  get totalPainel(): number {
    return this.semOrdemCarga ? this.pedidosSemOcFiltrados.length : this.listaFiltrada.length;
  }

  /** Página válida — busca/recarga podem encolher a lista. */
  private get paginaValida(): number {
    return Math.min(this.pagina(), Math.max(1, Math.ceil(this.totalPainel / this.itensPorPagina())));
  }

  get paginaExibida(): number {
    return this.paginaValida;
  }

  private fatia<T>(lista: T[]): T[] {
    const n = this.itensPorPagina();
    const ini = (this.paginaValida - 1) * n;
    return lista.slice(ini, ini + n);
  }

  get ocsDaPagina(): OrdemCargaResumoDto[] {
    return this.fatia(this.listaFiltrada);
  }

  get pedidosDaPagina(): PedidoSemOrdemCargaDto[] {
    return this.fatia(this.pedidosSemOcFiltrados);
  }

  onBuscaChange(): void {
    this.pagina.set(1);
  }

  onItensPorPaginaChange(valor: number): void {
    this.itensPorPagina.set(valor);
    this.pagina.set(1);
  }

  /** Troca cards ↔ lista mantendo o 1º item visível (se os itens/página mudarem). */
  onViewModeChange(modo: ViewMode): void {
    if (modo === this.viewMode()) return;
    const primeiro = (this.paginaValida - 1) * this.itensPorPagina();
    const n = itensValidosPara(modo, this.itensPorPagina());
    this.viewMode.set(modo);
    this.itensPorPagina.set(n);
    this.pagina.set(Math.floor(primeiro / n) + 1);
    salvarViewMode(CHAVE_VIEW_MODE, modo);
  }

  /**
   * O painel só lista o que ainda tem conferência pra fazer (regra do usuário): o backend já manda só OC
   * com pedido não concluído e só pedido S/ OC não concluído — o filtro aqui é só a busca por texto.
   */
  get listaFiltrada(): OrdemCargaResumoDto[] {
    const termo = this.filtroLista.trim().toLowerCase();
    return this.abertas().filter(
      (oc) =>
        (!termo ||
          String(oc.ordemCarga).includes(termo) ||
          !!oc.placa?.toLowerCase().includes(termo) ||
          !!oc.nomeMotorista?.toLowerCase().includes(termo)) &&
        this.passaModalidadeOc(oc) &&
        this.passaSituacaoOc(oc),
    );
  }

  get pedidosSemOcFiltrados(): PedidoSemOrdemCargaDto[] {
    const termo = this.filtroLista.trim().toLowerCase();
    return this.pedidosSemOc().filter(
      (p) =>
        (!termo ||
          String(p.nunota).includes(termo) ||
          (p.numNota != null && String(p.numNota).includes(termo)) ||
          (p.codParc != null && String(p.codParc).includes(termo)) ||
          !!p.nomeParceiro?.toLowerCase().includes(termo)) &&
        this.passaModalidadePedido(p) &&
        this.passaSituacaoPedido(p),
    );
  }

  // ─── Painel com OC em blocos por prioridade (pedido do usuário: hierarquia visual pro separador) ───

  /** OCs da busca/filtro em ordem de saída (dd/MM/yyyy) e depois número. */
  private get ocsOrdenadas(): OrdemCargaResumoDto[] {
    const chave = (d: string) => {
      const m = /^(\d{2})\/(\d{2})\/(\d{4})/.exec(d ?? '');
      return m ? `${m[3]}${m[2]}${m[1]}` : '99999999';
    };
    return [...this.listaFiltrada].sort((a, b) => chave(a.dataPrevSaida).localeCompare(chave(b.dataPrevSaida)) || a.ordemCarga - b.ordemCarga);
  }

  /** OC já impressa que ganhou pedido depois — extrema atenção, primeiro bloco. */
  get ocsComPedidoNovo(): OrdemCargaResumoDto[] {
    return this.ocsOrdenadas.filter((oc) => (oc.pedidosNovos?.length ?? 0) > 0);
  }

  get ocsASeparar(): OrdemCargaResumoDto[] {
    return this.ocsOrdenadas.filter((oc) => !oc.impressoEm && !(oc.pedidosNovos?.length ?? 0));
  }

  get ocsImpressas(): OrdemCargaResumoDto[] {
    return this.ocsOrdenadas.filter((oc) => !!oc.impressoEm && !(oc.pedidosNovos?.length ?? 0));
  }

  /** Sem OC: express primeiro, depois retira; dentro de cada um, não impresso primeiro. */
  get pedidosUrgentes(): PedidoSemOrdemCargaDto[] {
    return this.pedidosSemOcFiltrados
      .filter((p) => p.modalidade?.express || p.modalidade?.retira)
      .sort((a, b) => Number(!a.modalidade?.express) - Number(!b.modalidade?.express) || Number(!!a.impressoEm) - Number(!!b.impressoEm) || a.nunota - b.nunota);
  }

  get pedidosOutrosSemOc(): PedidoSemOrdemCargaDto[] {
    return this.pedidosSemOcFiltrados.filter((p) => !p.modalidade?.express && !p.modalidade?.retira);
  }

  get kgOcsASeparar(): number {
    return this.ocsASeparar.reduce((t, oc) => t + (oc.pesoPendenteKg ?? 0), 0);
  }

  get kgOcsImpressas(): number {
    return this.ocsImpressas.reduce((t, oc) => t + (oc.pesoPendenteKg ?? 0), 0);
  }

  /** Faixa do topo: tudo que falta conferir — todas as OCs da tela (impressas ou não) + pedidos sem OC. */
  get kgTotalASeparar(): number {
    return this.listaFiltrada.reduce((t, oc) => t + (oc.pesoPendenteKg ?? 0), 0) + this.pedidosSemOcFiltrados.reduce((t, p) => t + (p.pesoKg ?? 0), 0);
  }

  get kgUrgentes(): number {
    return this.pedidosUrgentes.reduce((t, p) => t + (p.pesoKg ?? 0), 0);
  }

  /** Bloco "Impressas" recolhido — lembrado no navegador. */
  readonly impressasRecolhidas = signal(this.filtrosSalvos.ler<{ impressasRecolhidas?: boolean }>('mapa-separacao-blocos')?.impressasRecolhidas === true);

  alternarImpressas(): void {
    this.impressasRecolhidas.set(!this.impressasRecolhidas());
    this.filtrosSalvos.salvar('mapa-separacao-blocos', { impressasRecolhidas: this.impressasRecolhidas() });
  }

  readonly exportando = signal(false);

  /** Excel do que está na tela (busca + filtros), na mesma ordem dos blocos. */
  async exportarExcel(): Promise<void> {
    if (this.exportando()) return;
    this.exportando.set(true);
    try {
      const d = new Date();
      const p2 = (n: number) => String(n).padStart(2, '0');
      const carimbo = `${d.getFullYear()}-${p2(d.getMonth() + 1)}-${p2(d.getDate())}_${p2(d.getHours())}${p2(d.getMinutes())}`;
      const ocs = this.semOrdemCarga ? [] : [...this.ocsComPedidoNovo, ...this.ocsASeparar, ...this.ocsImpressas];
      const pedidos = this.semOrdemCarga ? this.pedidosSemOcFiltrados : [...this.pedidosUrgentes, ...this.pedidosOutrosSemOc];
      await exportarMapaExcel(ocs, pedidos, `mapa-separacao_${carimbo}.xlsx`);
    } catch (e) {
      console.error(e);
      this.erroAbertas.set('Falha ao gerar o arquivo Excel.');
    } finally {
      this.exportando.set(false);
    }
  }

  consultarNovosDaOc(oc: OrdemCargaResumoDto): void {
    this.consultarNovos({ ordemCarga: oc.ordemCarga, nunotas: (oc.pedidosNovos ?? []).map((p) => p.nunota) });
  }

  /** Card/lista: tonelada a partir de 1 t, abaixo disso kg. */
  peso(kg: number | null | undefined): string {
    const v = kg ?? 0;
    if (v <= 0) return '—';
    return v >= 1000 ? this.toneladas(v) : `${Math.round(v).toLocaleString('pt-BR')} kg`;
  }

  toneladas(kg: number): string {
    return (kg / 1000).toLocaleString('pt-BR', { minimumFractionDigits: 2, maximumFractionDigits: 2 }) + ' t';
  }

  /** Selecionados que ainda aparecem na lista filtrada (todas as páginas) — é o que o botão gera. */
  get selecionadosVisiveis(): number[] {
    const sel = this.selecionados();
    return this.pedidosSemOcFiltrados.filter((p) => sel.has(p.nunota)).map((p) => p.nunota);
  }

  get todosVisiveisSelecionados(): boolean {
    const lista = this.pedidosSemOcFiltrados;
    return lista.length > 0 && lista.every((p) => this.selecionados().has(p.nunota));
  }

  alternarSelecao(nunota: number): void {
    const novo = new Set(this.selecionados());
    if (novo.has(nunota)) novo.delete(nunota);
    else novo.add(nunota);
    this.selecionados.set(novo);
  }

  alternarTodos(): void {
    const lista = this.pedidosSemOcFiltrados.map((p) => p.nunota);
    const novo = new Set(this.selecionados());
    if (this.todosVisiveisSelecionados) lista.forEach((n) => novo.delete(n));
    else lista.forEach((n) => novo.add(n));
    this.selecionados.set(novo);
  }

  iconeCategoria(codigo: string): OqIconName {
    return ICONE_CATEGORIA[codigo] ?? 'circle-alert';
  }

  /** 100% conferida — só faz sentido pra OC que já tem pelo menos 1 nota rastreada (ver totalNotas). */
  ocConcluida(oc: OrdemCargaResumoDto): boolean {
    return oc.totalNotas > 0 && oc.notasConferidas === oc.totalNotas;
  }

  progressoPct(oc: OrdemCargaResumoDto): number {
    if (oc.totalNotas <= 0) return 0;
    return Math.min(100, Math.round((oc.notasConferidas / oc.totalNotas) * 100));
  }

  ngOnInit(): void {
    this.carregarPainel();
    // Refresh automático no mesmo ciclo do sync da Fila de Tarefas (60s, contador
    // do cabeçalho) — é esse sync que atualiza o progresso de conferência. Só
    // recarrega no painel: com um relatório aberto, não mexe (seria ida à toa ao
    // Sankhya e a lista nem está visível).
    this.syncSub = this.syncTick.onTick.subscribe(() => {
      if (!this.dados() && !this.carregando()) this.carregarPainel(true);
    });
  }

  ngOnDestroy(): void {
    this.syncSub?.unsubscribe();
  }

  /** Lista do painel conforme o filtro "S/ Ordem de Carga". */
  carregarPainel(silencioso = false): void {
    if (this.semOrdemCarga) this.carregarSemOrdemCarga(silencioso);
    else this.carregarAbertas(silencioso);
  }

  /** `silencioso`: refresh automático — não troca a lista pelo spinner nem apaga a lista se falhar. */
  carregarAbertas(silencioso = false): void {
    if (!silencioso) this.carregandoAbertas.set(true);
    this.erroAbertas.set(null);
    // Express / retira sem OC aparecem no painel com OC também (bloco próprio, sem precisar de filtro).
    this.service.listarSemOrdemCarga().subscribe({ next: (l) => this.pedidosSemOc.set(l), error: () => undefined });
    this.service.listarAbertas().subscribe({
      next: (lista) => {
        // Defesa: OC sem pedido pendente (0/0 ou 100% conferida) não tem o que separar.
        this.abertas.set(lista.filter((oc) => oc.totalNotas > oc.notasConferidas));
        this.carregandoAbertas.set(false);
      },
      error: (err) => {
        if (!silencioso) this.abertas.set([]);
        this.carregandoAbertas.set(false);
        this.erroAbertas.set(err?.error?.erro ?? 'Falha ao carregar as Ordens de Carga.');
      },
    });
  }

  private carregarSemOrdemCarga(silencioso = false): void {
    if (!silencioso) this.carregandoAbertas.set(true);
    this.erroAbertas.set(null);
    this.service.listarSemOrdemCarga().subscribe({
      next: (lista) => {
        this.pedidosSemOc.set(lista);
        // Pedido que saiu da lista (ganhou OC, saiu da fila) não fica selecionado escondido.
        const existentes = new Set(lista.map((p) => p.nunota));
        this.selecionados.set(new Set([...this.selecionados()].filter((n) => existentes.has(n))));
        this.carregandoAbertas.set(false);
      },
      error: (err) => {
        if (!silencioso) this.pedidosSemOc.set([]);
        this.carregandoAbertas.set(false);
        this.erroAbertas.set(err?.error?.erro ?? 'Falha ao carregar os pedidos sem Ordem de Carga.');
      },
    });
  }

  /** Enter na busca / botão "Abrir mapa": número de OC (modo normal) ou Nº Único (modo S/ OC), mesmo fora da lista. */
  consultarManual(): void {
    const n = this.numeroBusca;
    if (!n) return;
    if (this.semOrdemCarga) this.consultarSemOrdemCarga([n]);
    else this.consultar(n);
  }

  consultar(ordemCarga: number): void {
    this.carregando.set(true);
    this.erro.set(null);
    this.mapas.set([]);

    this.service.consultar(ordemCarga).subscribe({
      next: (r) => {
        this.mapas.set([r]);
        this.carregando.set(false);
      },
      error: (err) => {
        this.erro.set(err?.error?.erro ?? 'Falha ao consultar a Ordem de Carga.');
        this.carregando.set(false);
      },
    });
  }

  /** "Imprimir só os pedidos novos": mapa da OC só com os pedidos que entraram depois da última impressão. */
  consultarNovos(e: { ordemCarga: number; nunotas: number[] }): void {
    this.carregando.set(true);
    this.erro.set(null);
    this.mapas.set([]);
    this.service.consultar(e.ordemCarga, e.nunotas).subscribe({
      next: (r) => {
        this.mapas.set([r]);
        this.carregando.set(false);
      },
      error: (err) => {
        this.erro.set(err?.error?.erro ?? 'Falha ao consultar os pedidos complementares da Ordem de Carga.');
        this.carregando.set(false);
      },
    });
  }

  /**
   * Um mapa por Número Único — cada pedido é buscado e montado SOZINHO no backend (nunca soma
   * entre pedidos). Pedido que falhar não derruba os outros: sai no aviso, os demais abrem.
   */
  consultarSemOrdemCarga(nunotas: number[]): void {
    if (nunotas.length === 0) return;
    this.carregando.set(true);
    this.erro.set(null);
    this.mapas.set([]);

    from(nunotas)
      .pipe(
        mergeMap(
          (nunota) =>
            new Promise<{ nunota: number; mapa?: MapaSeparacaoDto; erro?: string }>((resolve) =>
              this.service.consultarSemOrdemCarga(nunota).subscribe({
                next: (mapa) => resolve({ nunota, mapa }),
                error: (err) => resolve({ nunota, erro: err?.error?.erro ?? `Falha ao consultar o pedido ${nunota}.` }),
              }),
            ),
          CONCORRENCIA_MAPAS,
        ),
        toArray(),
      )
      .subscribe((resultados) => {
        // Mantém a ordem escolhida na tela (mergeMap devolve na ordem de chegada).
        const ordem = new Map(nunotas.map((n, i) => [n, i]));
        resultados.sort((a, b) => ordem.get(a.nunota)! - ordem.get(b.nunota)!);
        const falhas = resultados.filter((r) => r.erro).map((r) => r.erro!);
        this.mapas.set(resultados.filter((r) => r.mapa).map((r) => r.mapa!));
        this.erro.set(falhas.length ? falhas.join(' · ') : null);
        this.carregando.set(false);
      });
  }

  /** Volta pro painel — não recarrega a lista (evita ida desnecessária ao Sankhya); "Atualizar" faz isso à parte. */
  voltar(): void {
    this.mapas.set([]);
    this.erro.set(null);
  }

  tituloRelatorio(): string {
    const mapas = this.mapas();
    const primeiro = mapas[0];
    if (!primeiro) return '';
    if (!primeiro.semOrdemCarga && primeiro.somenteAlgunsPedidos) {
      return `Ordem de Carga ${primeiro.ordemCarga} — Mapa Complementar (pedidos ${primeiro.nunotasPedidos?.join(', ')})`;
    }
    if (!primeiro.semOrdemCarga) return `Ordem de Carga ${primeiro.ordemCarga}`;
    return mapas.length === 1
      ? `S/ Ordem de Carga — Nro. Único ${primeiro.nunota}`
      : `S/ Ordem de Carga — ${mapas.length} mapas`;
  }

  /** O que vai pro papel: só o mapa ou só os Pedidos de Venda (botões separados). */
  readonly modoImpressao = signal<'mapa' | 'pedidos' | null>(null);

  imprimir(modo: 'mapa' | 'pedidos' = 'mapa'): void {
    const mapas = this.mapas();
    if (mapas.length === 0) return;
    if (modo === 'pedidos' && this.pedidosVenda().length === 0) return;

    if (modo === 'pedidos') {
      this.imprimirPedidosIsolado(mapas);
      return;
    }

    const tituloOriginal = document.title;
    const base = mapas[0].semOrdemCarga
      ? mapas.length === 1
        ? `S-OC NU ${mapas[0].nunota}`
        : `S-OC ${mapas.length} mapas`
      : `O.C. ${mapas[0].ordemCarga}`;
    document.title = base;
    this.modoImpressao.set(modo);
    const restaurar = () => {
      document.title = tituloOriginal;
      this.modoImpressao.set(null);
      window.removeEventListener('afterprint', restaurar);
    };
    window.addEventListener('afterprint', restaurar, { once: true });
    setTimeout(() => window.print(), 50);
    this.registrarImpressao(mapas);
  }

  /**
   * "Imprimir pedidos": janela própria só com os Pedidos de Venda (A4 paisagem, um por página). Imprimir de
   * dentro da tela do app trocava de página nomeada no meio do layout (menu, rolagem) e soltava folha em
   * branco no início e no fim — documento isolado não tem nada em volta.
   */
  private imprimirPedidosIsolado(mapas: MapaSeparacaoDto[]): void {
    const pedidos = Array.from(document.querySelectorAll('oq-pedido-venda'));
    if (pedidos.length === 0) return;
    const janela = window.open('', '_blank');
    if (!janela) {
      this.erro.set('O navegador bloqueou a janela de impressão — libere pop-ups deste site.');
      return;
    }
    const titulo = mapas[0].semOrdemCarga ? `Pedidos S-OC` : `Pedidos O.C. ${mapas[0].ordemCarga}`;
    const estilos = Array.from(document.querySelectorAll('style, link[rel="stylesheet"]')).map((e) => e.outerHTML).join('');
    janela.document.write(
      `<!doctype html><html><head><meta charset="utf-8"><title>${titulo}</title><base href="${location.origin}/">${estilos}` +
        '<style>@page{size:A4 landscape;margin:5mm 6mm}' +
        'html,body{height:auto!important;overflow:visible!important;background:#fff!important;margin:0!important}' +
        '.pv{page:auto!important;margin-top:0!important;padding:0!important}</style></head><body>' +
        pedidos.map((p) => p.outerHTML).join('') +
        '</body></html>',
    );
    janela.document.close();
    const imprimir = () => {
      janela.focus();
      janela.print();
      setTimeout(() => janela.close(), 300);
    };
    // Espera o logo carregar antes de imprimir.
    const imagens = Array.from(janela.document.images);
    Promise.all(
      imagens.map((img) => (img.complete ? Promise.resolve() : new Promise((ok) => { img.onload = img.onerror = () => ok(null); }))),
    ).then(() => setTimeout(imprimir, 150));
  }

  /** Grava a impressão no backend e já marca IMPRESSO na lista local (sem recarregar o painel). */
  private registrarImpressao(mapas: MapaSeparacaoDto[]): void {
    const ocs = mapas.filter((m) => !m.semOrdemCarga && m.ordemCarga != null).map((m) => m.ordemCarga as number);
    const nunotas = mapas.filter((m) => m.semOrdemCarga && m.nunota != null).map((m) => m.nunota as number);
    if (!ocs.length && !nunotas.length) return;
    // Fotografia: quais pedidos saíram no mapa de cada OC — detecta pedido incluído depois.
    const pedidosPorOc: Record<string, number[]> = {};
    for (const m of mapas) {
      if (!m.semOrdemCarga && m.ordemCarga != null) pedidosPorOc[String(m.ordemCarga)] = m.nunotasPedidos ?? [];
    }
    const agora = new Date().toISOString();
    this.service.registrarImpressao(ocs, nunotas, pedidosPorOc).subscribe({
      next: () => {
        this.abertas.update((l) =>
          l.map((oc) => {
            if (!ocs.includes(oc.ordemCarga)) return oc;
            const saiu = new Set(pedidosPorOc[String(oc.ordemCarga)] ?? []);
            return { ...oc, impressoEm: agora, pedidosNovos: (oc.pedidosNovos ?? []).filter((p) => !saiu.has(p.nunota)) };
          }),
        );
        this.pedidosSemOc.update((l) => l.map((p) => (nunotas.includes(p.nunota) ? { ...p, impressoEm: agora } : p)));
      },
      error: () => undefined, // registro de impressão não pode atrapalhar a impressão
    });
  }

  /** Rótulo da etapa no peso por etapa: Frio / Refrigerado / Seco (mesma classificação do sistema). */
  rotuloEtapaPeso(codigo: string): string {
    return codigo === '3' ? 'Frio' : codigo === '2' ? 'Refrigerado' : codigo === '1' ? 'Seco' : 'Sem classificação';
  }

  /** "14:32" (hoje) ou "29/09 14:32" — selo IMPRESSO. */
  horaImpressao(iso: string | null | undefined): string {
    if (!iso) return '';
    const d = new Date(iso);
    const hoje = new Date();
    const hh = d.toLocaleTimeString('pt-BR', { hour: '2-digit', minute: '2-digit' });
    return d.toDateString() === hoje.toDateString() ? hh : `${d.toLocaleDateString('pt-BR', { day: '2-digit', month: '2-digit' })} ${hh}`;
  }

  trackMapa(_index: number, mapa: MapaSeparacaoDto): string {
    return mapa.semOrdemCarga ? `nu-${mapa.nunota}` : `oc-${mapa.ordemCarga}`;
  }

  trackCategoria(_index: number, categoria: CategoriaSeparacaoDto): string {
    return categoria.codigo;
  }
}
