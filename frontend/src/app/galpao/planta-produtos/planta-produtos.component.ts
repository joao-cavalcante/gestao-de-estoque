import { Component, OnInit, computed, inject, signal } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { ActivatedRoute, Router } from '@angular/router';
import { OqIconComponent } from '../../shared/icons/oq-icon.component';
import { GalpaoService } from '../galpao.service';
import {
  AnalisesPlanta,
  ClasseAbc,
  MetricaAbc,
  Planta,
  ProdutoAnalise,
  ROTULO_COMPONENTE,
  ROTULO_SETOR_PRODUTO,
  ROTULO_VALIDADE,
  abcDa,
} from '../galpao.model';
import { mensagemErro } from '../plantas-lista/plantas-lista.component';
import { exportarProdutosCsv, exportarProdutosExcel } from './produtos-exportacao';

type Coluna =
  | 'codprod' | 'descricao' | 'grupo' | 'setor' | 'abc' | 'participacao' | 'acumulado' | 'vendido'
  | 'estoque' | 'enderecado' | 'cobertura' | 'validade' | 'parado' | 'idade' | 'saude';

const POR_PAGINA = 50;
const CLASSES: ClasseAbc[] = ['A', 'B', 'C', 'N/D'];
const ORDEM_VALIDADE = { VENCIDO: 0, ATE_30: 1, '31_90': 2, ACIMA_90: 3, 'N/D': 4 } as const;

export const ROTULO_METRICA: Record<MetricaAbc, string> = {
  VALOR: 'valor vendido',
  QUANTIDADE: 'quantidade vendida',
  FREQUENCIA: 'frequência (nº de vendas)',
};

/**
 * Produtos do empresa/local da planta com as análises do Planta Log: curva ABC (valor, quantidade ou
 * frequência — o total é calculado antes do filtro/paginação), cobertura, validade, estoque parado, idade e
 * saúde do estoque com componentes e fórmula. Só consulta; nada grava no Sankhya.
 */
@Component({
  selector: 'app-planta-produtos',
  standalone: true,
  imports: [CommonModule, FormsModule, OqIconComponent],
  templateUrl: './planta-produtos.component.html',
  styleUrl: './planta-produtos.component.scss',
})
export class PlantaProdutosComponent implements OnInit {
  private readonly service = inject(GalpaoService);
  private readonly route = inject(ActivatedRoute);
  private readonly router = inject(Router);

  readonly plantaId = this.route.snapshot.paramMap.get('id') ?? '';
  readonly classes = CLASSES;
  readonly rotuloMetrica = ROTULO_METRICA;
  readonly rotuloSetor = ROTULO_SETOR_PRODUTO;
  readonly rotuloValidade = ROTULO_VALIDADE;
  readonly rotuloComponente = ROTULO_COMPONENTE;
  readonly metricas: MetricaAbc[] = ['VALOR', 'QUANTIDADE', 'FREQUENCIA'];

  readonly planta = signal<Planta | null>(null);
  readonly analises = signal<AnalisesPlanta | null>(null);
  readonly carregando = signal(true);
  readonly erro = signal<string | null>(null);
  readonly exportando = signal(false);
  readonly selecionado = signal<ProdutoAnalise | null>(null);

  readonly metrica = signal<MetricaAbc>('VALOR');
  readonly busca = signal('');
  readonly filtroSetor = signal('');
  readonly filtroClasse = signal('');
  readonly somenteComEstoque = signal(true);
  readonly pagina = signal(1);
  readonly ordem = signal<{ coluna: Coluna; asc: boolean }>({ coluna: 'acumulado', asc: true });

  /** Distribuição ABC sobre TODOS os produtos com venda (antes dos filtros da tabela). */
  readonly distribuicao = computed(() => {
    const lista = this.analises()?.produtos ?? [];
    const m = this.metrica();
    const total = lista.length;
    return CLASSES.map((c) => {
      const itens = lista.filter((p) => abcDa(p, m).classe === c);
      const participacao = itens.reduce((s, p) => s + (abcDa(p, m).participacao ?? 0), 0);
      return { classe: c, produtos: itens.length, pctProdutos: total ? (itens.length / total) * 100 : 0, participacao };
    });
  });
  readonly maiorBarra = computed(() => Math.max(1, ...this.distribuicao().map((d) => d.produtos)));

  readonly linhas = computed(() => {
    const lista = this.analises()?.produtos ?? [];
    const m = this.metrica();
    const termo = this.busca().trim().toLowerCase();
    const numero = /^\d+$/.test(termo) ? Number(termo) : null;
    const setor = this.filtroSetor();
    const classe = this.filtroClasse();
    const comEstoque = this.somenteComEstoque();
    const filtradas = lista.filter(
      (p) =>
        (!termo || p.codprod === numero || p.descricao.toLowerCase().includes(termo) || (p.grupo ?? '').toLowerCase().includes(termo)) &&
        (!setor || (setor === 'ND' ? p.setor == null : String(p.setor) === setor)) &&
        (!classe || abcDa(p, m).classe === classe) &&
        (!comEstoque || p.estoque !== 0 || p.enderecado !== 0 || abcDa(p, m).classe !== 'N/D'),
    );
    const { coluna, asc } = this.ordem();
    const v = (p: ProdutoAnalise): number | string => {
      const a = abcDa(p, m);
      switch (coluna) {
        case 'codprod': return p.codprod;
        case 'descricao': return p.descricao;
        case 'grupo': return p.grupo ?? '';
        case 'setor': return p.setor ?? 9;
        case 'abc': return a.classe === 'N/D' ? 'Z' : a.classe;
        case 'participacao': return a.participacao ?? -1;
        case 'acumulado': return a.acumulado ?? 1e9;
        case 'vendido': return m === 'VALOR' ? p.valorVendido : m === 'QUANTIDADE' ? p.quantidadeVendida : p.frequencia;
        case 'estoque': return p.estoque;
        case 'enderecado': return p.enderecado;
        case 'cobertura': return p.cobertura ?? 1e9;
        case 'validade': return ORDEM_VALIDADE[p.validade];
        case 'parado': return p.parado ? 0 : 1;
        case 'idade': return p.idadeDias ?? -1;
        case 'saude': return p.saude.score ?? 1e9;
      }
    };
    return [...filtradas].sort((x, y) => {
      const a = v(x);
      const b = v(y);
      const r = typeof a === 'number' && typeof b === 'number' ? a - b : String(a).localeCompare(String(b), 'pt-BR', { numeric: true });
      return (asc ? r : -r) || x.codprod - y.codprod;
    });
  });
  readonly totalPaginas = computed(() => Math.max(1, Math.ceil(this.linhas().length / POR_PAGINA)));
  readonly paginaAtual = computed(() => {
    const p = Math.min(this.pagina(), this.totalPaginas());
    return this.linhas().slice((p - 1) * POR_PAGINA, p * POR_PAGINA);
  });

  /** Indicadores do topo (sobre todos os produtos). */
  readonly resumo = computed(() => {
    const lista = this.analises()?.produtos ?? [];
    const comEstoque = lista.filter((p) => p.estoque > 0);
    return {
      comEstoque: comEstoque.length,
      parados: lista.filter((p) => p.parado).length,
      semCobertura: comEstoque.filter((p) => p.cobertura == null).length,
      vencendo: lista.filter((p) => p.validade === 'VENCIDO' || p.validade === 'ATE_30').length,
      criticos: lista.filter((p) => p.saude.faixa === 'CRÍTICO').length,
      semValidade: comEstoque.filter((p) => p.validade === 'N/D').length,
    };
  });

  ngOnInit(): void {
    this.service.plantas().subscribe({
      next: (l) => this.planta.set(l.find((p) => p.id === this.plantaId) ?? null),
      error: () => undefined,
    });
    this.carregar(false);
  }

  carregar(atualizar: boolean): void {
    this.carregando.set(true);
    this.service.analises(this.plantaId, atualizar).subscribe({
      next: (a) => {
        this.analises.set(a);
        this.erro.set(null);
        this.carregando.set(false);
        const sel = this.selecionado();
        if (sel) this.selecionado.set(a.produtos.find((p) => p.codprod === sel.codprod) ?? null);
      },
      error: (e) => {
        this.erro.set(mensagemErro(e, 'Não foi possível ler as análises do Sankhya.'));
        this.carregando.set(false);
      },
    });
  }

  voltar(): void {
    this.router.navigate(['/galpao', this.plantaId]);
  }

  trocarMetrica(m: MetricaAbc): void {
    this.metrica.set(m);
    this.pagina.set(1);
  }

  filtrarClasse(c: string): void {
    this.filtroClasse.set(this.filtroClasse() === c ? '' : c);
    this.pagina.set(1);
  }

  ordenar(c: Coluna): void {
    const o = this.ordem();
    this.ordem.set({ coluna: c, asc: o.coluna === c ? !o.asc : true });
  }

  seta(c: Coluna): string {
    const o = this.ordem();
    return o.coluna !== c ? '' : o.asc ? '▲' : '▼';
  }

  abc(p: ProdutoAnalise) {
    return abcDa(p, this.metrica());
  }

  vendido(p: ProdutoAnalise): string {
    const m = this.metrica();
    if (m === 'VALOR') return this.moeda(p.valorVendido);
    if (m === 'QUANTIDADE') return this.num(p.quantidadeVendida, 3);
    return String(p.frequencia);
  }

  async exportar(tipo: 'csv' | 'xlsx'): Promise<void> {
    const nome = `produtos-${(this.planta()?.nome ?? 'planta').replace(/[^\w-]+/g, '_')}`;
    if (tipo === 'csv') return exportarProdutosCsv(this.linhas(), this.metrica(), nome);
    this.exportando.set(true);
    try {
      await exportarProdutosExcel(this.linhas(), this.metrica(), nome);
    } finally {
      this.exportando.set(false);
    }
  }

  classeCss(c: ClasseAbc): string {
    return c === 'N/D' ? 'nd' : c.toLowerCase();
  }

  faixaCss(f: string): string {
    return { 'CRÍTICO': 'critico', 'ATENÇÃO': 'atencao', 'SAUDÁVEL': 'saudavel', EXCELENTE: 'excelente' }[f] ?? 'nd';
  }

  num(v: number | null | undefined, casas = 2): string {
    return v == null ? 'N/D' : v.toLocaleString('pt-BR', { maximumFractionDigits: casas });
  }

  pct(v: number | null | undefined): string {
    return v == null ? 'N/D' : v.toLocaleString('pt-BR', { minimumFractionDigits: 1, maximumFractionDigits: 1 }) + '%';
  }

  moeda(v: number): string {
    return v.toLocaleString('pt-BR', { style: 'currency', currency: 'BRL' });
  }

  data(v: string | null): string {
    return v ? v.split('-').reverse().join('/') : 'N/D';
  }
}
