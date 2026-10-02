import { Component, OnInit, computed, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { ConsultaProdutosService } from './consulta-produtos.service';
import {
  CampoOrdenacaoProduto,
  FiltroPesavel,
  FiltroSaldo,
  OrdenacaoProduto,
  ProdutoEstoque,
} from './consulta-produtos.model';
import { exportarProdutosExcel } from './consulta-produtos.excel';
import { OqSkeletonComponent } from '../shared/oq-skeleton/oq-skeleton.component';
import { OqIconComponent } from '../shared/icons/oq-icon.component';
import { OqPaginacaoComponent } from '../shared/lista-layout/oq-paginacao.component';
import { ITENS_POR_PAGINA } from '../shared/lista-layout/view-mode';
import {
  OqSearchableSelectComponent,
  OqSearchableSelectOpcao,
} from '../shared/oq-searchable-select/oq-searchable-select.component';

const FORMATO_QTD = new Intl.NumberFormat('pt-BR', { maximumFractionDigits: 3 });

interface Coluna {
  titulo: string;
  campo: CampoOrdenacaoProduto;
  classe?: string;
}

/**
 * Consulta de Produtos — o catálogo inteiro com o saldo da instância Estoque do Sankhya (total e
 * por empresa / local / lote). Filtro, ordenação e paginação em memória (a lista já vem inteira);
 * exporta pra Excel exatamente o que está filtrado/ordenado na tela.
 */
@Component({
  selector: 'app-consulta-produtos',
  standalone: true,
  imports: [FormsModule, OqSkeletonComponent, OqIconComponent, OqPaginacaoComponent, OqSearchableSelectComponent],
  templateUrl: './consulta-produtos.component.html',
  styleUrl: './consulta-produtos.component.scss',
})
export class ConsultaProdutosComponent implements OnInit {
  private readonly service = inject(ConsultaProdutosService);

  readonly produtos = signal<ProdutoEstoque[]>([]);
  readonly estoqueLidoEm = signal<string | null>(null);
  readonly erroEstoque = signal<string | null>(null);
  readonly carregando = signal(true);
  readonly exportando = signal(false);
  readonly erro = signal<string | null>(null);

  // ── filtros ──
  readonly busca = signal('');
  readonly filtroSaldo = signal<FiltroSaldo>('todos');
  readonly filtroEmpresa = signal<string | null>(null);
  readonly filtroLocal = signal<string | null>(null);
  readonly filtroPesavel = signal<FiltroPesavel>('todos');

  readonly opcoesPesavel: { id: FiltroPesavel; label: string }[] = [
    { id: 'todos', label: 'Todos' },
    { id: 'sim', label: 'Pesável' },
    { id: 'nao', label: 'Não pesável' },
  ];

  readonly opcoesSaldo: { id: FiltroSaldo; label: string }[] = [
    { id: 'todos', label: 'Todos' },
    { id: 'com-estoque', label: 'Com estoque' },
    { id: 'sem-estoque', label: 'Sem estoque' },
    { id: 'com-reservado', label: 'Com reservado' },
    { id: 'disp-negativo', label: 'Disp. negativo' },
  ];

  readonly colunas: Coluna[] = [
    { titulo: 'Código', campo: 'codprod' },
    { titulo: 'Produto', campo: 'descricao', classe: 'oq-lista__principal' },
    { titulo: 'Referência', campo: 'referencia' },
    { titulo: 'Marca', campo: 'marca' },
    { titulo: 'Unid.', campo: 'unidade' },
    { titulo: 'Pesável', campo: 'pesavel' },
    { titulo: 'Estoque', campo: 'estoque', classe: 'oq-lista__direita' },
    { titulo: 'Reservado', campo: 'reservado', classe: 'oq-lista__direita' },
    { titulo: 'Disponível', campo: 'disponivel', classe: 'oq-lista__direita' },
  ];
  readonly ordenacao = signal<OrdenacaoProduto>({ campo: 'descricao', direcao: 'asc' });

  // ── paginação ──
  readonly opcoesItensPorPagina = ITENS_POR_PAGINA.list;
  readonly itensPorPagina = signal(50);
  readonly page = signal(0);

  /** Produtos com o detalhe por local aberto. */
  readonly abertos = signal<ReadonlySet<number>>(new Set());

  readonly opcoesEmpresa = computed<OqSearchableSelectOpcao[]>(() => {
    const mapa = new Map<number, string>();
    this.produtos().forEach((p) => p.locais.forEach((l) => l.codemp != null && mapa.set(l.codemp, l.empresa ?? '')));
    return [...mapa].sort((a, b) => a[0] - b[0]).map(([cod, nome]) => ({ codigo: String(cod), label: nome || `Empresa ${cod}` }));
  });

  readonly opcoesLocal = computed<OqSearchableSelectOpcao[]>(() => {
    const mapa = new Map<number, string>();
    this.produtos().forEach((p) => p.locais.forEach((l) => l.codlocal != null && mapa.set(l.codlocal, l.local ?? '')));
    return [...mapa].sort((a, b) => a[0] - b[0]).map(([cod, nome]) => ({ codigo: String(cod), label: nome || `Local ${cod}` }));
  });

  /**
   * Filtro de empresa/local recorta os LOCAIS de cada produto e recalcula os totais só com eles —
   * "estoque do produto no local X". Produto sem nenhuma linha no recorte sai da lista.
   */
  private readonly recortados = computed<ProdutoEstoque[]>(() => {
    const emp = this.filtroEmpresa();
    const loc = this.filtroLocal();
    if (emp == null && loc == null) return this.produtos();
    return this.produtos()
      .map((p) => {
        const locais = p.locais.filter(
          (l) => (emp == null || String(l.codemp) === emp) && (loc == null || String(l.codlocal) === loc),
        );
        return {
          ...p,
          locais,
          estoque: soma(locais.map((l) => l.estoque)),
          reservado: soma(locais.map((l) => l.reservado)),
          disponivel: soma(locais.map((l) => l.disponivel)),
        };
      })
      .filter((p) => p.locais.length > 0);
  });

  readonly filtrados = computed<ProdutoEstoque[]>(() => {
    const palavras = normalizar(this.busca()).split(/\s+/).filter((p) => p);
    const termoInteiro = this.busca().trim();
    const saldo = this.filtroSaldo();
    const pesavel = this.filtroPesavel();
    const lista = this.recortados().filter((p) => {
      if (pesavel === 'sim' && p.pesavel !== true) return false;
      if (pesavel === 'nao' && p.pesavel !== false) return false;
      switch (saldo) {
        case 'com-estoque': if (!(p.estoque > 0)) return false; break;
        case 'sem-estoque': if (p.estoque > 0) return false; break;
        case 'com-reservado': if (!(p.reservado > 0)) return false; break;
        case 'disp-negativo': if (!(p.disponivel < 0)) return false; break;
      }
      if (!palavras.length) return true;
      if (String(p.codprod) === termoInteiro || p.codigosBarra.includes(termoInteiro)) return true;
      const texto = normalizar([p.codprod, p.descricao, p.complemento, p.marca, p.referencia].join(' '));
      return palavras.every((w) => texto.includes(w));
    });

    const { campo, direcao } = this.ordenacao();
    const sinal = direcao === 'asc' ? 1 : -1;
    const valor = (p: ProdutoEstoque) => (campo === 'pesavel' ? (p.pesavel == null ? null : p.pesavel ? 'Sim' : 'Não') : p[campo]);
    return [...lista].sort((a, b) => sinal * comparar(valor(a), valor(b)) || a.codprod - b.codprod);
  });

  readonly totalPaginas = computed(() => Math.max(Math.ceil(this.filtrados().length / this.itensPorPagina()), 1));
  /** Página sempre válida — filtro pode encolher a lista. */
  readonly paginaIdx = computed(() => Math.min(this.page(), this.totalPaginas() - 1));
  readonly paginaAtual = computed(() => {
    const n = this.itensPorPagina();
    const p = this.paginaIdx();
    return this.filtrados().slice(p * n, (p + 1) * n);
  });

  readonly temFiltro = computed(
    () =>
      !!this.busca().trim() || this.filtroSaldo() !== 'todos' || this.filtroPesavel() !== 'todos' ||
      this.filtroEmpresa() != null || this.filtroLocal() != null,
  );

  ngOnInit(): void {
    this.carregar(false);
  }

  carregar(atualizar: boolean): void {
    this.carregando.set(true);
    this.erro.set(null);
    this.service.listar(atualizar).subscribe({
      next: (res) => {
        this.produtos.set(res.produtos);
        this.estoqueLidoEm.set(res.estoqueLidoEm);
        this.erroEstoque.set(res.erroEstoque);
        this.carregando.set(false);
      },
      error: (e) => {
        this.erro.set(e?.error?.erro ?? 'Falha ao carregar os produtos.');
        this.carregando.set(false);
      },
    });
  }

  // ── handlers ──
  onBusca(valor: string): void {
    this.busca.set(valor);
    this.page.set(0);
  }

  setSaldo(f: FiltroSaldo): void {
    this.filtroSaldo.set(f);
    this.page.set(0);
  }

  setPesavel(f: FiltroPesavel): void {
    this.filtroPesavel.set(f);
    this.page.set(0);
  }

  setEmpresa(v: string | null): void {
    this.filtroEmpresa.set(v);
    this.page.set(0);
  }

  setLocal(v: string | null): void {
    this.filtroLocal.set(v);
    this.page.set(0);
  }

  limparFiltros(): void {
    this.busca.set('');
    this.filtroSaldo.set('todos');
    this.filtroPesavel.set('todos');
    this.filtroEmpresa.set(null);
    this.filtroLocal.set(null);
    this.page.set(0);
  }

  onItensPorPagina(n: number): void {
    this.itensPorPagina.set(n);
    this.page.set(0);
  }

  /** 1º clique: crescente (quantidades: maior primeiro); clique de novo inverte. */
  ordenar(campo: CampoOrdenacaoProduto): void {
    const atual = this.ordenacao();
    const numerico = campo === 'estoque' || campo === 'reservado' || campo === 'disponivel';
    this.ordenacao.set(
      atual.campo === campo
        ? { campo, direcao: atual.direcao === 'asc' ? 'desc' : 'asc' }
        : { campo, direcao: numerico ? 'desc' : 'asc' },
    );
    this.page.set(0);
  }

  indicador(campo: CampoOrdenacaoProduto): string {
    const o = this.ordenacao();
    if (o.campo !== campo) return '↕';
    return o.direcao === 'asc' ? '▲' : '▼';
  }

  ariaSort(campo: CampoOrdenacaoProduto): 'ascending' | 'descending' | 'none' {
    const o = this.ordenacao();
    if (o.campo !== campo) return 'none';
    return o.direcao === 'asc' ? 'ascending' : 'descending';
  }

  async exportar(): Promise<void> {
    if (this.exportando() || !this.filtrados().length) return;
    this.exportando.set(true);
    try {
      const d = new Date();
      const p2 = (n: number) => String(n).padStart(2, '0');
      const carimbo = `${d.getFullYear()}-${p2(d.getMonth() + 1)}-${p2(d.getDate())}_${p2(d.getHours())}${p2(d.getMinutes())}`;
      await exportarProdutosExcel(this.filtrados(), `consulta-produtos_${carimbo}.xlsx`);
    } catch (e) {
      console.error(e);
      this.erro.set('Falha ao gerar o arquivo Excel.');
    } finally {
      this.exportando.set(false);
    }
  }

  aberto(codprod: number): boolean {
    return this.abertos().has(codprod);
  }

  alternar(codprod: number): void {
    const proximo = new Set(this.abertos());
    if (proximo.has(codprod)) proximo.delete(codprod);
    else proximo.add(codprod);
    this.abertos.set(proximo);
  }

  qtd(valor: number): string {
    return FORMATO_QTD.format(valor);
  }

  controle(valor: string | null): string {
    return valor?.trim() || '—';
  }

  /** Complemento abaixo da descrição. */
  subtitulo(p: ProdutoEstoque): string {
    return p.complemento?.trim() ?? '';
  }

  lidoEmTexto(): string {
    const iso = this.estoqueLidoEm();
    if (!iso) return '';
    const d = new Date(iso);
    return d.toLocaleTimeString('pt-BR', { hour: '2-digit', minute: '2-digit' });
  }
}

function normalizar(s: string): string {
  return s.normalize('NFD').replace(/[̀-ͯ]/g, '').toLowerCase();
}

function soma(valores: number[]): number {
  return valores.reduce((acc, v) => acc + v, 0);
}

function comparar(a: unknown, b: unknown): number {
  if (typeof a === 'number' && typeof b === 'number') return a - b;
  const sa = (a ?? '').toString().trim();
  const sb = (b ?? '').toString().trim();
  // Vazio sempre por último no crescente.
  if (!sa && sb) return 1;
  if (sa && !sb) return -1;
  return sa.localeCompare(sb, 'pt-BR', { numeric: true, sensitivity: 'base' });
}
