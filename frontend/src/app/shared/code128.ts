/**
 * Code 128 (conjunto B — ASCII 32..126), sem dependência externa. Devolve as
 * larguras em MÓDULOS de cada barra/espaço, começando por barra, pra quem
 * desenha (SVG) escolher o tamanho do módulo — na térmica de 203 dpi, 0,25 mm
 * = 2 pontos por módulo.
 *
 * Tabela: largura de barra/espaço de cada valor 0..106 (106 = stop, 13 módulos).
 * Conferida contra python-barcode (charsets.code128) em 2026-09-23.
 */
const PADROES = [
  '212222', '222122', '222221', '121223', '121322', '131222', '122213', '122312', '132212', '221213',
  '221312', '231212', '112232', '122132', '122231', '113222', '123122', '123221', '223211', '221132',
  '221231', '213212', '223112', '312131', '311222', '321122', '321221', '312212', '322112', '322211',
  '212123', '212321', '232121', '111323', '131123', '131321', '112313', '132113', '132311', '211313',
  '231113', '231311', '112133', '112331', '132131', '113123', '113321', '133121', '313121', '211331',
  '231131', '213113', '213311', '213131', '311123', '311321', '331121', '312113', '312311', '332111',
  '314111', '221411', '431111', '111224', '111422', '121124', '121421', '141122', '141221', '112214',
  '112412', '122114', '122411', '142112', '142211', '241211', '221114', '413111', '241112', '134111',
  '111242', '121142', '121241', '114212', '124112', '124211', '411212', '421112', '421211', '212141',
  '214121', '412121', '111143', '111341', '131141', '114113', '114311', '411113', '411311', '113141',
  '114131', '311141', '411131', '211412', '211214', '211232', '2331112',
];

const START_B = 104;
const STOP = 106;

/** Larguras (em módulos) de barras/espaços alternados, começando por barra. Caracteres fora do conjunto B viram '?'. */
export function code128B(texto: string): number[] {
  const valores = [...texto].map((c) => {
    const code = c.charCodeAt(0);
    return code >= 32 && code <= 126 ? code - 32 : '?'.charCodeAt(0) - 32;
  });
  const soma = valores.reduce((acc, v, i) => acc + v * (i + 1), START_B);
  const simbolos = [START_B, ...valores, soma % 103, STOP];
  return simbolos.flatMap((s) => [...PADROES[s]].map(Number));
}

/** Total de módulos do código (sem zona de silêncio). */
export function larguraModulos(larguras: number[]): number {
  return larguras.reduce((a, b) => a + b, 0);
}

export const _PADROES_CODE128_PARA_TESTE = PADROES;
