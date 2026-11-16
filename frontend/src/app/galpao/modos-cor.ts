import { Endereco, FaixaValidade, ProdutoAnalise, SetorPlanta } from './galpao.model';

/**
 * Modos de "Colorir posições por" — mesmos nomes, faixas e ordem do Planta Log (metrics.js: legend/colorFor).
 * Os modos de análise (ABC, validade, saúde, cobertura, parado) usam o produto do endereço — como no original,
 * o primeiro da lista — e a fotografia de GET /analises. Alertas fica desabilitado até a etapa de alertas.
 */
export type ModoCor =
  | 'setor' | 'ocupacao' | 'peso' | 'volume' | 'movimento' | 'separacao' | 'tipo'
  | 'abc' | 'abc_qtd' | 'abc_freq' | 'validade' | 'saude' | 'cobertura' | 'parado';

/** Modos que precisam da fotografia analítica. */
export const MODOS_ANALISE = new Set<string>(['abc', 'abc_qtd', 'abc_freq', 'validade', 'saude', 'cobertura', 'parado']);

/** O que os modos de análise precisam: análise por produto, validade por lote e os limites de cobertura. */
export interface ContextoAnalise {
  porProduto: Map<number, ProdutoAnalise>;
  /** `${codprod}|${controle}` → 'AAAA-MM-DD'. */
  validadeLote: Map<string, string>;
  /** 'AAAA-MM-DD' (Brasília). */
  hoje: string;
  coberturaMin: number;
  coberturaMax: number;
}

export interface OpcaoModo {
  valor: ModoCor | string;
  rotulo: string;
  disponivel: boolean;
}

export const OPCOES_MODO: OpcaoModo[] = [
  { valor: 'setor', rotulo: 'Setor logístico', disponivel: true },
  { valor: 'ocupacao', rotulo: 'Ocupação · maior razão válida', disponivel: true },
  { valor: 'abc', rotulo: 'Curva ABC por valor', disponivel: true },
  { valor: 'abc_qtd', rotulo: 'Curva ABC por quantidade', disponivel: true },
  { valor: 'abc_freq', rotulo: 'Curva ABC por frequência', disponivel: true },
  { valor: 'validade', rotulo: 'Validade', disponivel: true },
  { valor: 'saude', rotulo: 'Saúde do estoque', disponivel: true },
  { valor: 'cobertura', rotulo: 'Cobertura em dias', disponivel: true },
  { valor: 'parado', rotulo: 'Estoque parado', disponivel: true },
  { valor: 'separacao', rotulo: 'Separação em andamento', disponivel: true },
  { valor: 'movimento', rotulo: 'Movimentação física recente', disponivel: true },
  { valor: 'alertas', rotulo: 'Alertas e divergências', disponivel: false },
  { valor: 'peso', rotulo: 'Capacidade por peso', disponivel: true },
  { valor: 'volume', rotulo: 'Capacidade por volume', disponivel: true },
  { valor: 'tipo', rotulo: 'Picking × pulmão', disponivel: true },
];

const VERDE = '#2e9e6b';
const AMBAR = '#e0a40c';
const VERMELHO = '#d64545';
const ND = '#8b8398';
const AZUL = '#3d6fb6';
const LILAS = '#8c7fd6';
const LARANJA = '#e8833a';
const AMARELO = '#d9c23a';
export const COR_BLOQUEADO = '#4b4b55';
/** "Livre" — o tom vem do tema (--gp-vazio); este é o padrão. */
export const COR_LIVRE_PADRAO = '#d9d4e4';

/** Janela de "movimentação física recente". */
const HORAS_RECENTE = 24;
const DIAS_SEMANA = 7;

export function faixaRazao(v: number | null | undefined, livre: string): string {
  if (v == null) return ND;
  if (v <= 0) return livre;
  if (v > 1 + 1e-9) return VERMELHO;
  if (v > 0.7) return AMBAR;
  return VERDE;
}

export function horasDesde(iso: string | null, agora = Date.now()): number | null {
  if (!iso) return null;
  const t = Date.parse(iso);
  return Number.isNaN(t) ? null : (agora - t) / 3_600_000;
}

const COR_ABC: Record<string, string> = { A: VERDE, B: AMBAR, C: LILAS };
const COR_VALIDADE: Record<string, string> = { VENCIDO: VERMELHO, ATE_30: LARANJA, '31_90': AMARELO, ACIMA_90: VERDE };

/** Faixa de validade pela data (mesmas faixas do backend). */
export function faixaValidade(dtval: string | null | undefined, hoje: string): FaixaValidade {
  if (!dtval) return 'N/D';
  const dias = Math.round((Date.parse(dtval + 'T12:00:00Z') - Date.parse(hoje + 'T12:00:00Z')) / 86_400_000);
  return dias < 0 ? 'VENCIDO' : dias <= 30 ? 'ATE_30' : dias <= 90 ? '31_90' : 'ACIMA_90';
}

/** Validade do endereço: a menor data entre os lotes com saldo; sem lote com data, a do produto. */
export function validadeEndereco(e: Endereco, ctx: ContextoAnalise): FaixaValidade {
  const datas = e.saldos
    .filter((s) => s.quantidade > 0)
    .map((s) => ctx.validadeLote.get(`${s.codprod}|${s.controle}`))
    .filter((d): d is string => !!d)
    .sort();
  if (datas.length) return faixaValidade(datas[0], ctx.hoje);
  const p = e.saldos[0] ? ctx.porProduto.get(e.saldos[0].codprod) : undefined;
  return p?.validade ?? 'N/D';
}

function corAnalise(e: Endereco, modo: ModoCor, livre: string, ctx: ContextoAnalise | null): string {
  if (!ctx) return ND;
  if (e.quantidade <= 0 || !e.saldos.length) return livre;
  const p = ctx.porProduto.get(e.saldos[0].codprod);
  if (!p) return ND;
  switch (modo) {
    case 'abc': return COR_ABC[p.abc] ?? ND;
    case 'abc_qtd': return COR_ABC[p.abcQtd] ?? ND;
    case 'abc_freq': return COR_ABC[p.abcFreq] ?? ND;
    case 'validade': return COR_VALIDADE[validadeEndereco(e, ctx)] ?? ND;
    case 'saude': {
      const n = p.saude.score;
      return n == null ? ND : n < 40 ? VERMELHO : n < 70 ? AMBAR : n < 90 ? VERDE : AZUL;
    }
    case 'cobertura':
      return p.cobertura == null ? ND : p.cobertura < ctx.coberturaMin ? VERMELHO : p.cobertura > ctx.coberturaMax ? AMBAR : VERDE;
    case 'parado': return p.parado ? AMBAR : VERDE;
    default: return ND;
  }
}

export function corEndereco(
  e: Endereco,
  modo: ModoCor,
  setores: Map<string, string>,
  livre: string,
  agora = Date.now(),
  analise: ContextoAnalise | null = null,
): string {
  if (e.bloqueado && modo !== 'setor') return COR_BLOQUEADO;
  if (MODOS_ANALISE.has(modo)) return corAnalise(e, modo, livre, analise);
  switch (modo) {
    case 'setor':
      return setores.get(e.setor) ?? ND;
    case 'ocupacao':
      return e.quantidade > 0 && e.ocupacao == null ? ND : faixaRazao(e.quantidade > 0 ? e.ocupacao : 0, livre);
    case 'peso':
      return e.quantidade > 0 ? faixaRazao(e.ocupacaoPeso, livre) : livre;
    case 'volume':
      return e.quantidade > 0 ? faixaRazao(e.ocupacaoVolume, livre) : livre;
    case 'separacao':
      return e.emSeparacao ? VERDE : livre;
    case 'movimento': {
      const h = horasDesde(e.ultimoMovimentoEm, agora);
      if (h == null) return livre;
      return h <= HORAS_RECENTE ? AMBAR : h <= DIAS_SEMANA * 24 ? AZUL : livre;
    }
    case 'tipo':
      return e.tipo === 'PICKING' ? '#5b47a0' : '#9a93a8';
    default:
      return ND;
  }
}

export function legendaModo(
  modo: ModoCor,
  setores: SetorPlanta[],
  livre: string,
  limites: { a: number; b: number; coberturaMin: number; coberturaMax: number } = { a: 80, b: 95, coberturaMin: 7, coberturaMax: 90 },
): [string, string][] {
  const fim: [string, string][] = [['Vazio', livre], ['N/D', ND], ['Bloqueado', COR_BLOQUEADO]];
  const capacidade: [string, string][] = [
    ['Livre', livre],
    ['Até 70%', VERDE],
    ['70–100%', AMBAR],
    ['Acima de 100%', VERMELHO],
    ['N/D', ND],
  ];
  switch (modo) {
    case 'setor':
      return [...setores.map((s): [string, string] => [s.nome, s.cor]), ['N/D', ND]];
    case 'ocupacao':
    case 'peso':
    case 'volume':
      return [...capacidade, ['Bloqueado', COR_BLOQUEADO]];
    case 'separacao':
      return [['Em separação', VERDE], ['Demais posições', livre], ['Bloqueado', COR_BLOQUEADO]];
    case 'movimento':
      return [['Últimas 24 h', AMBAR], ['Últimos 7 dias', AZUL], ['Sem movimento recente', livre], ['Bloqueado', COR_BLOQUEADO]];
    case 'tipo':
      return [['Picking', '#5b47a0'], ['Pulmão', '#9a93a8'], ['Bloqueado', COR_BLOQUEADO]];
    case 'abc':
    case 'abc_qtd':
    case 'abc_freq':
      return [[`A · até ${limites.a}%`, VERDE], [`B · até ${limites.b}%`, AMBAR], ['C · restante', LILAS], ...fim];
    case 'validade':
      return [['Vencido', VERMELHO], ['Até 30 dias', LARANJA], ['31–90 dias', AMARELO], ['Acima de 90 dias', VERDE], ...fim];
    case 'saude':
      return [['Crítico', VERMELHO], ['Atenção', AMBAR], ['Saudável', VERDE], ['Excelente', AZUL], ...fim];
    case 'cobertura':
      return [
        [`Até ${limites.coberturaMin} dias`, VERMELHO],
        [`${limites.coberturaMin}–${limites.coberturaMax} dias`, VERDE],
        [`Acima de ${limites.coberturaMax} dias`, AMBAR],
        ...fim,
      ];
    case 'parado':
      return [['Sem venda na janela', AMBAR], ['Com venda', VERDE], ...fim];
  }
}

/** Movimento nas últimas 24 h — vira rota na camada "Rotas de eventos físicos". */
export function recente(e: Endereco, agora = Date.now()): boolean {
  const h = horasDesde(e.ultimoMovimentoEm, agora);
  return h != null && h <= HORAS_RECENTE;
}
