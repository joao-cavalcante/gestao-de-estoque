import { AfterViewInit, Component, ElementRef, OnDestroy, ViewChild, computed, inject, signal } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { ActivatedRoute, Router } from '@angular/router';
import { OqIconComponent } from '../../shared/icons/oq-icon.component';
import { GalpaoService } from '../galpao.service';
import { AlertasPlanta, AnalisesPlanta, Endereco, IndicadoresPlanta, Movimento, ProdutoAnalise, ROTULO_MOVIMENTO, ROTULO_VALIDADE, SaldoEndereco, VisaoPlanta } from '../galpao.model';
import { MovimentoModalComponent, TipoModal, novoId } from '../movimento-modal/movimento-modal.component';
import { Camadas, Camera, Cena3d } from '../cena-3d';
import { COR_LIVRE_PADRAO, ContextoAlertas, ContextoAnalise, MODOS_ANALISE, ModoCor, OPCOES_MODO, corEndereco, faixaRazao, faixaValidade, legendaModo, recente } from '../modos-cor';
import { exportarEnderecosCsv, exportarEnderecosExcel } from '../enderecos-exportacao';
import { mensagemErro } from '../plantas-lista/plantas-lista.component';

const POR_PAGINA = 100;
/** Feed consultado a cada 30 s; movimento novo recarrega a planta mantendo câmera e seleção. */
const INTERVALO_FEED_MS = 30_000;

type ColunaOrdem = 'codigo' | 'estrutura' | 'modulo' | 'nivel' | 'setor' | 'tipo' | 'quantidade' | 'ocupacao' | 'base' | 'bloqueado';

const CAMERAS: { id: Camera; rotulo: string; icone: string; dica?: string }[] = [
  { id: 'iso', rotulo: 'Isométrica', icone: 'box' },
  { id: 'aerea', rotulo: 'Vista aérea', icone: 'grid' },
  { id: 'panoramica', rotulo: 'Panorâmica', icone: 'building' },
  { id: 'corte', rotulo: 'Corte da estrutura', icone: 'filter' },
  { id: 'primeira', rotulo: 'Primeira pessoa', icone: 'user', dica: 'WASD' },
];

const CAMADAS: { id: keyof Camadas; rotulo: string }[] = [
  { id: 'estruturas', rotulo: 'Estruturas' },
  { id: 'posicoes', rotulo: 'Paletes / posições' },
  { id: 'rotulos', rotulo: 'Rótulos e docas' },
  { id: 'rotas', rotulo: 'Rotas de eventos físicos' },
];

/**
 * Visão operacional da planta publicada — mesmo arranjo do Planta Log: filtros, cor e câmera à esquerda;
 * indicadores e 3D no centro; endereço selecionado e feed de movimentações à direita. A "Tabela de posições"
 * é a alternativa sem WebGL (ordenação, filtro, CSV/Excel). Movimentar: só ADMINISTRADOR por enquanto.
 */
@Component({
  selector: 'app-planta-visao',
  standalone: true,
  imports: [CommonModule, FormsModule, OqIconComponent, MovimentoModalComponent],
  templateUrl: './planta-visao.component.html',
  styleUrl: './planta-visao.component.scss',
})
export class PlantaVisaoComponent implements AfterViewInit, OnDestroy {
  private readonly service = inject(GalpaoService);
  private readonly route = inject(ActivatedRoute);
  private readonly router = inject(Router);
  private readonly host = inject(ElementRef<HTMLElement>);

  @ViewChild('palco') palco?: ElementRef<HTMLDivElement>;
  private cena: Cena3d | null = null;
  private timerFeed: ReturnType<typeof setInterval> | null = null;

  readonly opcoesModo = OPCOES_MODO;
  readonly cameras = CAMERAS;
  readonly opcoesCamadas = CAMADAS;
  readonly rotuloMov = ROTULO_MOVIMENTO;

  readonly carregando = signal(true);
  readonly erro = signal<string | null>(null);
  readonly semWebgl = signal(false);
  readonly visao = signal<VisaoPlanta | null>(null);
  readonly indicadores = signal<IndicadoresPlanta | null>(null);
  /** Fotografia analítica (ABC, validade, saúde…) — carregada à parte, a 3D não espera o Sankhya. */
  readonly analises = signal<AnalisesPlanta | null>(null);
  readonly carregandoAnalises = signal(false);
  readonly rotuloValidade = ROTULO_VALIDADE;
  readonly alertas = signal<AlertasPlanta | null>(null);
  readonly contextoAlertas = computed<ContextoAlertas | null>(() => {
    const a = this.alertas();
    if (!a) return null;
    const porProduto = new Map<number, 'CRITICO' | 'ATENCAO'>();
    const porEndereco = new Map<string, 'CRITICO' | 'ATENCAO'>();
    const marcar = <K>(m: Map<K, 'CRITICO' | 'ATENCAO'>, k: K, sev: 'CRITICO' | 'ATENCAO') => {
      if (m.get(k) !== 'CRITICO') m.set(k, sev);
    };
    for (const x of a.alertas) {
      if (x.status === 'RESOLVIDO' || x.severidade === 'INFO') continue;
      if (x.enderecoId) marcar(porEndereco, x.enderecoId, x.severidade);
      else if (x.codprod != null) marcar(porProduto, x.codprod, x.severidade);
    }
    return { porProduto, porEndereco };
  });
  readonly criticosAbertos = computed(() => this.alertas()?.alertas.filter((x) => x.status !== 'RESOLVIDO' && x.severidade === 'CRITICO').length ?? 0);
  readonly feed = signal<Movimento[]>([]);
  readonly modo = signal<ModoCor>('setor');
  readonly vista = signal<'3d' | 'tabela'>('3d');
  readonly camera = signal<Camera>('iso');
  readonly camadas = signal<Camadas>({ estruturas: true, posicoes: true, rotulos: true, rotas: false });
  readonly selecionado = signal<Endereco | null>(null);
  readonly busca = signal('');
  readonly buscando = signal(false);
  readonly resultadoBusca = signal<number[] | null>(null);
  readonly filtroSetor = signal('');
  readonly filtroEstrutura = signal('');
  readonly pagina = signal(1);
  readonly ordem = signal<{ coluna: ColunaOrdem; asc: boolean }>({ coluna: 'codigo', asc: true });
  readonly buscaTabela = signal('');
  readonly exportando = signal(false);
  /** Movimentação por endereço: só ADMINISTRADOR por enquanto (backend confere de novo). */
  readonly podeMovimentar = signal(false);
  readonly modal = signal<{ tipo: TipoModal; endereco: Endereco; saldo: SaldoEndereco | null } | null>(null);
  readonly feedback = signal<{ texto: string; erro: boolean } | null>(null);
  readonly bloqueando = signal(false);
  private corLivre = COR_LIVRE_PADRAO;

  readonly plantaId = this.route.snapshot.paramMap.get('id') ?? '';

  readonly enderecos = computed(() => this.visao()?.enderecos ?? []);
  readonly setores = computed(() => this.visao()?.geometria?.setores ?? []);
  readonly mapaSetores = computed(() => new Map(this.setores().map((s) => [s.codigo, s.cor])));
  readonly estruturas = computed(() => [...new Set(this.enderecos().map((e) => e.estrutura))].sort());
  readonly resumoSetores = computed(() => this.setores().map((s) => s.nome).join(' · ') || 'Nenhum setor');
  readonly contextoAnalise = computed<ContextoAnalise | null>(() => {
    const a = this.analises();
    if (!a) return null;
    return {
      porProduto: new Map(a.produtos.map((p) => [p.codprod, p])),
      validadeLote: new Map(a.validadesLote.map((v) => [`${v.codprod}|${v.controle}`, v.dtval])),
      hoje: new Intl.DateTimeFormat('en-CA', { timeZone: 'America/Sao_Paulo' }).format(new Date()),
      coberturaMin: a.parametros.coberturaMinDias,
      coberturaMax: a.parametros.coberturaMaxDias,
    };
  });
  readonly legenda = computed(() => {
    const p = this.analises()?.parametros;
    return legendaModo(this.modo(), this.setores(), this.corLivre, p ? { a: p.limiteA, b: p.limiteB, coberturaMin: p.coberturaMinDias, coberturaMax: p.coberturaMaxDias } : undefined);
  });
  readonly resumo = computed(() => {
    const e = this.enderecos();
    const ocupados = e.filter((x) => x.quantidade > 0).length;
    return {
      total: e.length,
      ocupados,
      pct: e.length ? (ocupados / e.length) * 100 : null,
      bloqueados: e.filter((x) => x.bloqueado).length,
      pendentes: e.reduce((s, x) => s + x.contagensPendentes, 0),
    };
  });

  // ─── tabela de posições ──────────────────────────────────────────────────
  readonly linhasTabela = computed(() => {
    const setor = this.filtroSetor();
    const termo = this.buscaTabela().trim().toLowerCase();
    const numero = /^\d+$/.test(termo) ? Number(termo) : null;
    const idx = this.resultadoBusca();
    const base = idx ? idx.map((i) => this.enderecos()[i]) : this.enderecos();
    const filtradas = base.filter(
      (e) =>
        (!setor || e.setor === setor) &&
        (!termo ||
          e.codigo.toLowerCase().includes(termo) ||
          e.estrutura.toLowerCase() === termo ||
          e.saldos.some((s) => s.codprod === numero || s.produto.toLowerCase().includes(termo))),
    );
    const { coluna, asc } = this.ordem();
    const valor = (e: Endereco): string | number => {
      switch (coluna) {
        case 'quantidade': return e.quantidade;
        case 'ocupacao': return e.ocupacao ?? -1;
        case 'modulo': return e.modulo;
        case 'nivel': return e.nivel;
        case 'bloqueado': return e.bloqueado ? 1 : 0;
        case 'setor': return this.nomeSetor(e.setor);
        case 'base': return e.baseOcupacao ?? '';
        default: return e[coluna];
      }
    };
    return [...filtradas].sort((a, b) => {
      const va = valor(a);
      const vb = valor(b);
      const r = typeof va === 'number' && typeof vb === 'number' ? va - vb : String(va).localeCompare(String(vb), 'pt-BR', { numeric: true });
      return asc ? r : -r;
    });
  });
  readonly totalPaginas = computed(() => Math.max(1, Math.ceil(this.linhasTabela().length / POR_PAGINA)));
  readonly paginaTabela = computed(() => {
    const p = Math.min(this.pagina(), this.totalPaginas());
    return this.linhasTabela().slice((p - 1) * POR_PAGINA, p * POR_PAGINA);
  });

  ngAfterViewInit(): void {
    this.corLivre = getComputedStyle(this.host.nativeElement).getPropertyValue('--gp-vazio').trim() || COR_LIVRE_PADRAO;
    this.criarCena();
    this.carregar();
    this.carregarIndicadores();
    this.carregarAnalises(false);
    this.carregarAlertas(false);
    this.service.disponivel().subscribe({ next: (r) => this.podeMovimentar.set(r.podeEditar), error: () => undefined });
    this.timerFeed = setInterval(() => this.carregarFeed(true), INTERVALO_FEED_MS);
  }

  ngOnDestroy(): void {
    if (this.timerFeed) clearInterval(this.timerFeed);
    this.cena?.destruir();
  }

  /** [manterSelecao] = depois de um movimento: recarrega sem mexer na câmera e volta a selecionar o endereço. */
  carregar(manterSelecao = false): void {
    const codigoSelecionado = manterSelecao ? this.selecionado()?.codigo : undefined;
    this.carregando.set(true);
    this.service.visao(this.plantaId).subscribe({
      next: (v) => {
        this.visao.set(v);
        this.erro.set(null);
        this.carregando.set(false);
        this.selecionado.set(null);
        this.resultadoBusca.set(null);
        const agora = Date.now();
        this.cena?.carregar(
          v.geometria,
          v.enderecos.map((e) => ({ ...e, recente: recente(e, agora) })),
          manterSelecao,
        );
        this.aplicarCores();
        this.aplicarFiltros(false);
        if (codigoSelecionado) {
          const i = v.enderecos.findIndex((e) => e.codigo === codigoSelecionado);
          if (i >= 0) this.selecionarIndice(i);
        }
      },
      error: (e) => {
        this.erro.set(mensagemErro(e, 'Não foi possível carregar a planta.'));
        this.carregando.set(false);
      },
    });
    this.carregarFeed(false);
  }

  carregarIndicadores(): void {
    this.service.indicadores(this.plantaId).subscribe({
      next: (i) => this.indicadores.set(i),
      error: () => this.indicadores.set({ saldoContabil: null, produtosComSaldo: null, lidoEm: new Date().toISOString(), aviso: 'Saldo contábil indisponível' }),
    });
  }

  carregarAnalises(atualizar: boolean): void {
    this.carregandoAnalises.set(true);
    this.service.analises(this.plantaId, atualizar).subscribe({
      next: (a) => {
        this.analises.set(a);
        this.carregandoAnalises.set(false);
        if (MODOS_ANALISE.has(this.modo())) this.aplicarCores();
      },
      error: (e) => {
        this.carregandoAnalises.set(false);
        if (MODOS_ANALISE.has(this.modo())) this.feedback.set({ texto: mensagemErro(e, 'Análises indisponíveis: as posições ficam N/D.'), erro: true });
      },
    });
  }

  carregarAlertas(atualizar: boolean): void {
    this.service.alertas(this.plantaId, atualizar).subscribe({
      next: (a) => {
        this.alertas.set(a);
        if (this.modo() === 'alertas') this.aplicarCores();
      },
      error: () => undefined,
    });
  }

  abrirAlertas(): void {
    this.router.navigate(['/galpao', this.plantaId, 'alertas']);
  }

  /** Análise do produto (ABC, saúde…) pro painel do endereço. */
  analiseDe(codprod: number): ProdutoAnalise | undefined {
    return this.contextoAnalise()?.porProduto.get(codprod);
  }

  validadeDoLote(s: SaldoEndereco): string {
    const ctx = this.contextoAnalise();
    if (!ctx) return 'N/D';
    const d = ctx.validadeLote.get(`${s.codprod}|${s.controle}`);
    return d ? `${d.split('-').reverse().join('/')} · ${this.rotuloValidade[faixaValidade(d, ctx.hoje)]}` : 'N/D';
  }

  abrirProdutos(): void {
    this.router.navigate(['/galpao', this.plantaId, 'produtos']);
  }

  /** [recarregarSeNovo] = no ciclo automático: chegou movimento novo, a planta é recarregada. */
  carregarFeed(recarregarSeNovo: boolean): void {
    this.service.feed(this.plantaId).subscribe({
      next: (f) => {
        const antes = this.feed()[0]?.id;
        this.feed.set(f);
        if (recarregarSeNovo && f[0] && f[0].id !== antes && !this.modal()) this.carregar(true);
      },
      error: () => undefined,
    });
  }

  atualizarTudo(): void {
    this.carregar(true);
    this.carregarIndicadores();
    this.carregarAnalises(true);
    this.carregarAlertas(true);
  }

  voltar(): void {
    this.router.navigate(['/galpao']);
  }

  abrirControle(): void {
    this.router.navigate(['/galpao', this.plantaId, 'controle']);
  }

  abrirEditor(): void {
    this.router.navigate(['/galpao', this.plantaId, 'editor']);
  }

  // ─── visualização ────────────────────────────────────────────────────────

  trocarModo(m: string): void {
    const opcao = OPCOES_MODO.find((o) => o.valor === m);
    if (!opcao?.disponivel) return;
    this.modo.set(m as ModoCor);
    if (MODOS_ANALISE.has(m) && !this.analises() && !this.carregandoAnalises()) this.carregarAnalises(false);
    if (m === 'alertas' && !this.alertas()) this.carregarAlertas(false);
    this.aplicarCores();
  }

  private aplicarCores(): void {
    const lista = this.enderecos();
    const setores = this.mapaSetores();
    const modo = this.modo();
    const agora = Date.now();
    const ctx = this.contextoAnalise();
    const al = this.contextoAlertas();
    this.cena?.definirCores((_p, i) => corEndereco(lista[i], modo, setores, this.corLivre, agora, ctx, al));
  }

  trocarCamera(c: Camera): void {
    this.camera.set(c);
    this.cena?.definirCamera(c);
    if (c === 'primeira') this.feedback.set({ texto: 'Primeira pessoa: W, A, S, D para andar (Shift acelera), mouse para olhar e Esc para sair.', erro: false });
  }

  alternarCamada(id: keyof Camadas, ligado: boolean): void {
    this.camadas.update((c) => ({ ...c, [id]: ligado }));
    this.cena?.definirCamadas({ [id]: ligado });
  }

  visaoGeral(): void {
    this.limparBusca();
    this.filtroSetor.set('');
    this.filtroEstrutura.set('');
    this.trocarCamera('iso');
  }

  trocarVista(v: '3d' | 'tabela'): void {
    this.vista.set(v);
    this.pagina.set(1);
  }

  // ─── filtros e busca ─────────────────────────────────────────────────────

  aoFiltrar(): void {
    this.aplicarFiltros(true);
  }

  /** Setor e prédio/corredor realçam as posições (o resto apaga), como no Planta Log. */
  private aplicarFiltros(enquadrar: boolean): void {
    if (this.resultadoBusca()) return;
    const setor = this.filtroSetor();
    const estr = this.filtroEstrutura().trim().toLowerCase();
    if (!setor && !estr) {
      this.cena?.destacar([], false);
      return;
    }
    const ids: number[] = [];
    this.enderecos().forEach((e, i) => {
      if ((!setor || e.setor === setor) && (!estr || e.estrutura.toLowerCase().includes(estr))) ids.push(i);
    });
    this.cena?.destacar(ids, enquadrar);
  }

  /**
   * Produto (código ou descrição), endereço, estrutura ou documento (NUNOTA / nº do pedido ou nota):
   * número consulta também o Sankhya e junta os produtos do documento.
   */
  buscar(): void {
    const termo = this.busca().trim().toLowerCase();
    this.pagina.set(1);
    if (!termo) return this.limparBusca();
    const numero = /^\d+$/.test(termo) ? Number(termo) : null;
    const achados = this.localizar((e) =>
      e.codigo.toLowerCase().includes(termo) ||
      e.estrutura.toLowerCase() === termo ||
      e.saldos.some((s) => s.codprod === numero || s.produto.toLowerCase().includes(termo)),
    );
    if (numero == null) return this.mostrarBusca(achados, null);
    this.buscando.set(true);
    this.service.documento(this.plantaId, numero).subscribe({
      next: (doc) => {
        this.buscando.set(false);
        const produtos = new Set(doc.codprods);
        const doDoc = this.localizar((e) => e.saldos.some((s) => produtos.has(s.codprod)));
        const todos = [...new Set([...achados, ...doDoc])];
        const nome = doc.numnota && doc.numnota !== doc.nunota ? `Documento ${doc.nunota} (nº ${doc.numnota})` : `Documento ${doc.nunota}`;
        const extra = doDoc.length
          ? `${nome}: ${doDoc.length} posição(ões) com produtos dele.`
          : `${nome}: nenhum dos ${doc.codprods.length} produto(s) está endereçado nesta planta.`;
        this.mostrarBusca(todos, extra);
      },
      error: () => {
        this.buscando.set(false);
        this.mostrarBusca(achados, null);
      },
    });
  }

  private localizar(teste: (e: Endereco) => boolean): number[] {
    const r: number[] = [];
    this.enderecos().forEach((e, i) => teste(e) && r.push(i));
    return r;
  }

  private mostrarBusca(achados: number[], extra: string | null): void {
    if (!achados.length) {
      this.resultadoBusca.set(null);
      this.cena?.destacar([], false);
      this.feedback.set({ texto: extra ?? 'Nenhum endereço físico associado a esta pesquisa.', erro: !extra });
      return;
    }
    this.resultadoBusca.set(achados);
    this.cena?.destacar(achados);
    this.feedback.set({ texto: extra ?? `${achados.length} posição(ões) localizada(s).`, erro: false });
    if (achados.length === 1) this.selecionarIndice(achados[0], true);
  }

  limparBusca(): void {
    this.busca.set('');
    this.resultadoBusca.set(null);
    this.cena?.destacar([], false);
    this.aplicarFiltros(false);
  }

  // ─── seleção ─────────────────────────────────────────────────────────────

  selecionarIndice(i: number | null, enquadrar = false): void {
    this.selecionado.set(i == null ? null : this.enderecos()[i]);
    this.cena?.selecionar(i, enquadrar);
  }

  detalhar(e: Endereco): void {
    const i = this.enderecos().indexOf(e);
    this.vista.set('3d');
    // Espera o palco voltar a ter tamanho antes de enquadrar.
    setTimeout(() => this.selecionarIndice(i, true));
  }

  /** Item do feed: seleciona o destino (ou a origem) do movimento. */
  abrirDoFeed(m: Movimento): void {
    const codigo = m.destino ?? m.origem;
    const i = codigo ? this.enderecos().findIndex((e) => e.codigo === codigo) : -1;
    if (i < 0) return;
    if (this.vista() !== '3d') this.vista.set('3d');
    setTimeout(() => this.selecionarIndice(i, true));
  }

  // ─── movimentação ────────────────────────────────────────────────────────

  abrirModal(tipo: TipoModal, endereco: Endereco, saldo: SaldoEndereco | null = null): void {
    this.feedback.set(null);
    this.modal.set({ tipo, endereco, saldo });
  }

  aoConcluirMovimento(mensagem: string): void {
    this.modal.set(null);
    this.feedback.set({ texto: mensagem, erro: false });
    this.carregar(true);
  }

  alternarBloqueio(e: Endereco): void {
    this.bloqueando.set(true);
    this.feedback.set(null);
    this.service
      .movimentar(this.plantaId, { comandoId: novoId(), tipo: e.bloqueado ? 'DESBLOQUEAR' : 'BLOQUEAR', destinoId: e.id, quantidade: 0 })
      .subscribe({
        next: (r) => {
          this.bloqueando.set(false);
          this.feedback.set({ texto: r.mensagem, erro: false });
          this.carregar(true);
        },
        error: (err) => {
          this.bloqueando.set(false);
          this.feedback.set({ texto: mensagemErro(err, 'Não foi possível alterar o bloqueio.'), erro: true });
        },
      });
  }

  // ─── tabela ──────────────────────────────────────────────────────────────

  ordenar(c: ColunaOrdem): void {
    const o = this.ordem();
    this.ordem.set({ coluna: c, asc: o.coluna === c ? !o.asc : true });
    this.pagina.set(1);
  }

  setaOrdem(c: ColunaOrdem): string {
    const o = this.ordem();
    return o.coluna !== c ? '' : o.asc ? '▲' : '▼';
  }

  irPagina(p: number): void {
    this.pagina.set(Math.min(Math.max(1, p), this.totalPaginas()));
  }

  async exportar(formato: 'csv' | 'xlsx'): Promise<void> {
    const nome = `enderecos-${(this.visao()?.planta.nome ?? 'planta').replace(/[^\w-]+/g, '_')}`;
    const linhas = this.linhasTabela();
    if (formato === 'csv') return exportarEnderecosCsv(linhas, (s) => this.nomeSetor(s), nome);
    this.exportando.set(true);
    try {
      await exportarEnderecosExcel(linhas, (s) => this.nomeSetor(s), nome);
    } finally {
      this.exportando.set(false);
    }
  }

  // ─── formatação ──────────────────────────────────────────────────────────

  baseTexto(e: Endereco): string {
    return e.baseOcupacao === 'PESO' ? 'Peso' : e.baseOcupacao === 'VOLUME' ? 'Volume' : e.baseOcupacao === 'QUANTIDADE' ? 'Quantidade' : 'N/D';
  }

  ocupacaoTexto(e: Endereco): string {
    if (e.ocupacao == null) return e.quantidade > 0 ? 'N/D' : '0%';
    return `${(e.ocupacao * 100).toLocaleString('pt-BR', { maximumFractionDigits: 1 })}%`;
  }

  razaoTexto(v: number | null): string {
    return v == null ? 'N/D' : `${(v * 100).toLocaleString('pt-BR', { maximumFractionDigits: 1 })}%`;
  }

  corFaixa(v: number | null, quantidade: number): string {
    return faixaRazao(quantidade > 0 ? v : 0, this.corLivre);
  }

  larguraBarra(e: Endereco): number {
    return Math.max(0, Math.min(100, (e.ocupacao ?? 0) * 100));
  }

  nomeSetor(codigo: string): string {
    return this.setores().find((s) => s.codigo === codigo)?.nome ?? (codigo || 'Sem setor');
  }

  corSetor(codigo: string): string {
    return this.mapaSetores().get(codigo) ?? '#8b8398';
  }

  num(v: number | null | undefined, casas = 2): string {
    return v == null ? 'N/D' : v.toLocaleString('pt-BR', { maximumFractionDigits: casas });
  }

  hora(iso: string | null | undefined): string {
    if (!iso) return '—';
    const d = new Date(iso);
    const hoje = new Date();
    const mesmoDia = d.toDateString() === hoje.toDateString();
    return mesmoDia
      ? d.toLocaleTimeString('pt-BR', { hour: '2-digit', minute: '2-digit' })
      : d.toLocaleString('pt-BR', { day: '2-digit', month: '2-digit', hour: '2-digit', minute: '2-digit' });
  }

  private criarCena(): void {
    const el = this.palco?.nativeElement;
    if (!el) return;
    try {
      this.cena = new Cena3d(el, { interativa: true });
      this.cena.aoSelecionar = (i) => this.selecionado.set(i == null ? null : this.enderecos()[i]);
      this.cena.aoMudarVista = (c) => this.camera.set(c);
    } catch {
      // Sem WebGL (máquina/driver): a tabela continua funcionando.
      this.semWebgl.set(true);
      this.vista.set('tabela');
    }
  }
}
