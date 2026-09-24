import { code128B, larguraModulos } from '../code128';

/**
 * Layout do crachá de operador (cartão CR80) — tudo em MILÍMETROS, calculado
 * uma vez e desenhado em SVG pelo CrachaComponent. O mesmo SVG vai pra
 * impressão e pro PDF (svg2pdf), então não existe um segundo desenho pra
 * divergir.
 */
export type OrientacaoCracha = 'horizontal' | 'vertical';

export const CR80 = { largura: 85.6, altura: 54 } as const;

export function dimensoes(o: OrientacaoCracha): { w: number; h: number } {
  return o === 'horizontal' ? { w: CR80.largura, h: CR80.altura } : { w: CR80.altura, h: CR80.largura };
}

const MARGEM = 3;
const ZONA_SILENCIO = 3;
/** 0,5 mm por módulo = 4 pontos numa térmica de 203 dpi — leitura folgada. */
const MODULO_ALVO = 0.5;
/** 7 pt ≈ 2,47 mm: nada menor que isso. */
const FONTE_MIN = 2.5;

export interface LayoutCracha {
  w: number;
  h: number;
  margem: number;
  cabecalho: { logoX: number; logoY: number; logoW: number; logoH: number; operadorX: number; operadorY: number; operadorFonte: number; linhaY: number };
  nome: { texto: string; x: number; y: number; fonte: number; larguraMax: number; comprimir: boolean };
  numero: { texto: string; x: number; y: number; fonte: number };
  barras: { x: number; y: number; w: number; h: number; modulo: number; retangulos: { x: number; w: number }[]; textoY: number; textoFonte: number };
  /** Código tem caractere fora do Code 128B (vira "?") — a prévia avisa. */
  codigoInvalido: boolean;
}

let ctxMedida: CanvasRenderingContext2D | null = null;

/** Largura do texto em mm numa fonte Arial bold de `fonteMm` (Helvetica no PDF tem as mesmas métricas). */
function larguraTexto(texto: string, fonteMm: number): number {
  if (!ctxMedida) {
    ctxMedida = document.createElement('canvas').getContext('2d');
  }
  if (!ctxMedida) return texto.length * fonteMm * 0.62; // fallback grosseiro
  ctxMedida.font = 'bold 100px Arial, Helvetica, sans-serif';
  return (ctxMedida.measureText(texto).width / 100) * fonteMm;
}

/** Maior fonte (passo 0,1 mm) em que o texto cabe; null se nem a mínima couber. */
function maiorFonteQueCabe(texto: string, larguraMax: number, max: number, min: number): number | null {
  for (let f = max; f >= min - 1e-9; f = Math.round((f - 0.1) * 10) / 10) {
    if (larguraTexto(texto, f) <= larguraMax) return f;
  }
  return null;
}

/**
 * Nome completo se couber numa fonte legível; senão "PRIMEIRO ÚLTIMO"; em
 * último caso encolhe o espaçamento (textLength) na fonte mínima — nunca corta.
 */
function ajustarNome(nome: string, larguraMax: number, max: number): { texto: string; fonte: number; comprimir: boolean } {
  const completo = nome.trim().replace(/\s+/g, ' ').toUpperCase() || '—';
  const minLegivel = 3.4; // ~9,6 pt: abaixo disso prefere encurtar o nome
  const f1 = maiorFonteQueCabe(completo, larguraMax, max, minLegivel);
  if (f1 != null) return { texto: completo, fonte: f1, comprimir: false };

  const partes = completo.split(' ');
  const curto = partes.length > 2 ? `${partes[0]} ${partes[partes.length - 1]}` : completo;
  const f2 = maiorFonteQueCabe(curto, larguraMax, max, FONTE_MIN);
  if (f2 != null) return { texto: curto, fonte: f2, comprimir: false };
  return { texto: curto, fonte: FONTE_MIN, comprimir: true };
}

export function calcularLayout(nome: string, codigo: string, o: OrientacaoCracha): LayoutCracha {
  const { w, h } = dimensoes(o);
  const util = w - 2 * MARGEM;
  const horizontal = o === 'horizontal';
  const cx = w / 2;

  const cabecalho = {
    logoX: MARGEM,
    logoY: MARGEM,
    logoW: horizontal ? 32 : 26,
    logoH: 8,
    operadorX: w - MARGEM,
    operadorY: MARGEM + 5.6,
    operadorFonte: 3.2,
    linhaY: MARGEM + 10,
  };

  const nomeFit = ajustarNome(nome, util, horizontal ? 6.2 : 5.4);
  const nomeY = horizontal ? 22.8 : 33;

  const numero = { texto: `Nº ${codigo}`, x: cx, y: horizontal ? 30.4 : 44, fonte: horizontal ? 4.4 : 5.2 };

  const larguras = code128B(codigo);
  const modulos = larguraModulos(larguras);
  const areaBarras = util - 2 * ZONA_SILENCIO;
  // Horizontal: mínimo ~50 mm pedido; vertical (48 mm úteis) usa o que couber.
  const larguraBarras = horizontal
    ? Math.min(areaBarras, Math.max(50, modulos * MODULO_ALVO))
    : Math.min(areaBarras, modulos * MODULO_ALVO);
  const modulo = larguraBarras / modulos;
  const retangulos: { x: number; w: number }[] = [];
  let x = 0;
  larguras.forEach((lw, i) => {
    if (i % 2 === 0) retangulos.push({ x: x * modulo, w: lw * modulo });
    x += lw;
  });
  const barrasY = horizontal ? 33.2 : 55;
  const barrasH = horizontal ? 12 : 16;

  return {
    w,
    h,
    margem: MARGEM,
    cabecalho,
    nome: { texto: nomeFit.texto, x: cx, y: nomeY, fonte: nomeFit.fonte, larguraMax: util, comprimir: nomeFit.comprimir },
    numero,
    barras: {
      x: cx - larguraBarras / 2,
      y: barrasY,
      w: larguraBarras,
      h: barrasH,
      modulo,
      retangulos,
      textoY: barrasY + barrasH + 3.1,
      textoFonte: 2.8,
    },
    codigoInvalido: [...codigo].some((c) => c.charCodeAt(0) < 32 || c.charCodeAt(0) > 126),
  };
}

/** "cracha_000123_yuri.pdf" — primeiro nome sem acento, minúsculo. */
export function nomeArquivoCracha(codigo: string, nome: string): string {
  const primeiro = (nome.trim().split(/\s+/)[0] || 'operador')
    .normalize('NFD')
    .replace(/[̀-ͯ]/g, '')
    .replace(/[^a-zA-Z0-9]/g, '')
    .toLowerCase();
  const cod = codigo.replace(/[^a-zA-Z0-9_-]/g, '');
  return `cracha_${cod}_${primeiro || 'operador'}.pdf`;
}
