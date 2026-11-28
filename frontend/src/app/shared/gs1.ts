/**
 * Leitor de GS1-128 / GS1 DataBar (27/11/2026, recebimento na doca). O leitor de código de barras entrega a string
 * com os Identificadores de Aplicação (IA) colados; campos de tamanho variável terminam no separador FNC1, que chega
 * como GS (\x1D) — alguns leitores mandam "]C1" no início e "(" ")" quando configurados para texto legível.
 *
 * IAs tratados: (01)/(02) GTIN, (10) lote, (11) fabricação, (13) embalagem, (15) consumir até, (17) validade,
 * (21) série, (310n) peso líquido kg, (320n) peso líquido lb (convertido para kg), (37) quantidade.
 */
export interface Gs1 {
  gtin?: string;
  lote?: string;
  /** ISO yyyy-MM-dd. */
  fabricacao?: string;
  embalagem?: string;
  validade?: string;
  serie?: string;
  pesoKg?: number;
  quantidade?: number;
}

const GS = '\x1D';
const FIXOS: Record<string, number> = { '00': 18, '01': 14, '02': 14, '11': 6, '13': 6, '15': 6, '17': 6 };
const VARIAVEIS = new Set(['10', '21', '37']);

/** AAMMDD → yyyy-MM-dd (dia 00 = último dia do mês, regra GS1). */
export function dataGs1(v: string): string | undefined {
  if (!/^\d{6}$/.test(v)) return undefined;
  const ano = 2000 + Number(v.slice(0, 2));
  const mes = Number(v.slice(2, 4));
  let dia = Number(v.slice(4, 6));
  if (mes < 1 || mes > 12) return undefined;
  if (dia === 0) dia = new Date(ano, mes, 0).getDate();
  return `${ano}-${String(mes).padStart(2, '0')}-${String(dia).padStart(2, '0')}`;
}

/** Parece GS1 (começa com um IA conhecido e tem tamanho de GS1)? EAN-13/código interno curto não é. */
export function pareceGs1(bruto: string): boolean {
  const s = normalizar(bruto);
  return s.length >= 16 && (/^(01|02)\d{14}/.test(s) || /^(10|11|13|15|17|21|31|32)/.test(s) && s.includes(GS));
}

function normalizar(bruto: string): string {
  let s = (bruto ?? '').trim();
  if (s.startsWith(']C1') || s.startsWith(']e0') || s.startsWith(']d2')) s = s.slice(3);
  // Formato legível "(01)0789...(10)L123": troca os parênteses por separador.
  if (s.startsWith('(')) s = s.replace(/\((\d{2,4})\)/g, (_m, ia, off) => (off === 0 ? '' : GS) + ia);
  return s;
}

export function lerGs1(bruto: string): Gs1 | null {
  const s = normalizar(bruto);
  if (!s) return null;
  const r: Gs1 = {};
  let i = 0;
  let algum = false;
  while (i < s.length) {
    if (s[i] === GS) {
      i++;
      continue;
    }
    const ia2 = s.slice(i, i + 2);
    const ia4 = s.slice(i, i + 4);
    if (ia2 in FIXOS) {
      const v = s.slice(i + 2, i + 2 + FIXOS[ia2]);
      if (v.length < FIXOS[ia2]) return algum ? r : null;
      if (ia2 === '01' || ia2 === '02') r.gtin = v;
      if (ia2 === '11') r.fabricacao = dataGs1(v);
      if (ia2 === '13') r.embalagem = dataGs1(v);
      if (ia2 === '15' && !r.validade) r.validade = dataGs1(v);
      if (ia2 === '17') r.validade = dataGs1(v);
      i += 2 + FIXOS[ia2];
      algum = true;
      continue;
    }
    if (/^3[12]0\d$/.test(ia4)) {
      const v = s.slice(i + 4, i + 10);
      if (!/^\d{6}$/.test(v)) return algum ? r : null;
      const kg = Number(v) / 10 ** Number(ia4[3]);
      r.pesoKg = ia4.startsWith('320') ? Math.round(kg * 0.45359237 * 1000) / 1000 : kg;
      i += 10;
      algum = true;
      continue;
    }
    if (VARIAVEIS.has(ia2)) {
      const fim = s.indexOf(GS, i + 2);
      const v = s.slice(i + 2, fim < 0 ? s.length : fim);
      if (ia2 === '10') r.lote = v.slice(0, 20);
      if (ia2 === '21') r.serie = v;
      if (ia2 === '37') r.quantidade = Number(v) || undefined;
      i = fim < 0 ? s.length : fim + 1;
      algum = true;
      continue;
    }
    return algum ? r : null; // IA não tratado: para aqui com o que já leu
  }
  return algum ? r : null;
}

/** Validade digitada pelo operador: DD/MM/AA, DD/MM/AAAA ou MM/AA (último dia do mês). → yyyy-MM-dd. */
export function lerValidadeDigitada(texto: string): string | undefined {
  const t = (texto ?? '').trim();
  let m = /^(\d{1,2})[/.-](\d{1,2})[/.-](\d{2}|\d{4})$/.exec(t);
  if (m) {
    const ano = m[3].length === 2 ? 2000 + Number(m[3]) : Number(m[3]);
    const mes = Number(m[2]);
    const dia = Number(m[1]);
    const d = new Date(ano, mes - 1, dia);
    if (d.getMonth() !== mes - 1 || d.getDate() !== dia) return undefined;
    return `${ano}-${String(mes).padStart(2, '0')}-${String(dia).padStart(2, '0')}`;
  }
  m = /^(\d{1,2})[/.-](\d{2}|\d{4})$/.exec(t);
  if (m) {
    const ano = m[2].length === 2 ? 2000 + Number(m[2]) : Number(m[2]);
    const mes = Number(m[1]);
    if (mes < 1 || mes > 12) return undefined;
    const dia = new Date(ano, mes, 0).getDate();
    return `${ano}-${String(mes).padStart(2, '0')}-${String(dia).padStart(2, '0')}`;
  }
  return undefined;
}

/** yyyy-MM-dd → dd/MM/aaaa. */
export function dataBr(iso: string | null | undefined): string {
  const m = /^(\d{4})-(\d{2})-(\d{2})/.exec(iso ?? '');
  return m ? `${m[3]}/${m[2]}/${m[1]}` : '';
}

/** Dias até a validade (negativo = vencido). */
export function diasAte(iso: string, hoje = new Date()): number {
  const [a, m, d] = iso.split('-').map(Number);
  const alvo = Date.UTC(a, m - 1, d);
  const h = Date.UTC(hoje.getFullYear(), hoje.getMonth(), hoje.getDate());
  return Math.round((alvo - h) / 86_400_000);
}
