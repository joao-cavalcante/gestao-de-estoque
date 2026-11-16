import { Endereco, SetorPlanta } from './galpao.model';

/**
 * Modos de "Colorir posições por" — mesmos nomes, faixas e ordem do Planta Log (metrics.js: legend/colorFor).
 * Os da fase 5 (ABC, validade, saúde, cobertura, parado, alertas) aparecem na lista, desabilitados.
 */
export type ModoCor = 'setor' | 'ocupacao' | 'peso' | 'volume' | 'movimento' | 'separacao' | 'tipo';

export interface OpcaoModo {
  valor: ModoCor | string;
  rotulo: string;
  disponivel: boolean;
}

export const OPCOES_MODO: OpcaoModo[] = [
  { valor: 'setor', rotulo: 'Setor logístico', disponivel: true },
  { valor: 'ocupacao', rotulo: 'Ocupação · maior razão válida', disponivel: true },
  { valor: 'abc', rotulo: 'Curva ABC por valor', disponivel: false },
  { valor: 'abc_qtd', rotulo: 'Curva ABC por quantidade', disponivel: false },
  { valor: 'abc_freq', rotulo: 'Curva ABC por frequência', disponivel: false },
  { valor: 'validade', rotulo: 'Validade', disponivel: false },
  { valor: 'saude', rotulo: 'Saúde do estoque', disponivel: false },
  { valor: 'cobertura', rotulo: 'Cobertura em dias', disponivel: false },
  { valor: 'parado', rotulo: 'Estoque parado', disponivel: false },
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

export function corEndereco(e: Endereco, modo: ModoCor, setores: Map<string, string>, livre: string, agora = Date.now()): string {
  if (e.bloqueado && modo !== 'setor') return COR_BLOQUEADO;
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
  }
}

export function legendaModo(modo: ModoCor, setores: SetorPlanta[], livre: string): [string, string][] {
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
  }
}

/** Movimento nas últimas 24 h — vira rota na camada "Rotas de eventos físicos". */
export function recente(e: Endereco, agora = Date.now()): boolean {
  const h = horasDesde(e.ultimoMovimentoEm, agora);
  return h != null && h <= HORAS_RECENTE;
}
