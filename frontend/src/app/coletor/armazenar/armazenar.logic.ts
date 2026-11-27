/**
 * Armazenar no coletor (endereçamento, etapa 3b — 27/11/2026): regras puras da tela, sem Angular.
 * Fluxo: lista de tarefas → confere o produto → bipa a etiqueta do endereço → confirma a quantidade.
 */

export interface TarefaArmazenagem {
  nutar: number;
  situacao: 'AB' | 'EX' | 'CO' | 'CA' | string;
  nunota: number | null;
  numnota: number | null;
  fornecedor: string | null;
  codemp: number | null;
  codlocal: number | null;
  codprod: number;
  descricao: string;
  referencia: string | null;
  controle: string | null;
  unidade: string | null;
  qtd: number;
  qtdFeita: number;
  origem: string | null;
  sugerido: string | null;
  tipoSugerido: string | null;
  codusu: number | null;
  criada: string | null;
  barras: string[];
}

/** Motivos de troca do endereço sugerido (o addon exige motivo quando o destino não é o sugerido). */
export const MOTIVOS_TROCA = [
  'Endereço sugerido cheio',
  'Endereço sugerido com avaria ou obstruído',
  'Produto já está em outro endereço',
  'Orientação do líder',
] as const;

export function normalizar(codigo: string): string {
  return (codigo ?? '').trim().toUpperCase();
}

/** Quanto ainda falta guardar (nunca negativo; arredonda a 3 casas para não sobrar 0,0000001). */
export function falta(t: Pick<TarefaArmazenagem, 'qtd' | 'qtdFeita'>): number {
  return Math.max(0, Math.round((t.qtd - t.qtdFeita) * 1000) / 1000);
}

/** Código bipado é deste produto? Código de barras (TGFBAR), referência ou o próprio código do produto. */
export function ehDoProduto(codigo: string, t: Pick<TarefaArmazenagem, 'codprod' | 'referencia' | 'barras'>): boolean {
  const c = normalizar(codigo);
  if (!c) return false;
  if (t.barras.some((b) => normalizar(b) === c)) return true;
  if (t.referencia && normalizar(t.referencia) === c) return true;
  return /^\d+$/.test(c) && Number(c) === t.codprod;
}

/** Tarefa da lista para um código bipado na lista (produto). Mais antiga primeiro; em execução antes de aberta. */
export function tarefaPorProduto(codigo: string, tarefas: TarefaArmazenagem[]): TarefaArmazenagem[] {
  return tarefas.filter((t) => ehDoProduto(codigo, t));
}

/** Endereço bipado é o sugerido? */
export function ehOSugerido(codigo: string, t: Pick<TarefaArmazenagem, 'sugerido'>): boolean {
  return !!t.sugerido && normalizar(codigo) === normalizar(t.sugerido);
}

/** Lista: em execução primeiro (alguém já começou), depois a ordem de criação (o addon já manda mais antigas antes). */
export function ordenarTarefas(tarefas: TarefaArmazenagem[]): TarefaArmazenagem[] {
  return [...tarefas].sort((a, b) => (a.situacao === 'EX' ? 0 : 1) - (b.situacao === 'EX' ? 0 : 1) || a.nutar - b.nutar);
}

/** "1.100" / "12,5" — quantidade em pt-BR sem casas desnecessárias. */
export function qtdTexto(n: number): string {
  return n.toLocaleString('pt-BR', { maximumFractionDigits: 3 });
}

/** Quantidade digitada ("1.100", "12,5", "12.5") → número; inválida = null. */
export function lerQtd(texto: string): number | null {
  let s = (texto ?? '').trim();
  if (!s) return null;
  if (s.includes(',')) s = s.replace(/\./g, '').replace(',', '.');
  else if (/^\d{1,3}(\.\d{3})+$/.test(s)) s = s.replace(/\./g, '');
  const n = Number(s);
  return Number.isFinite(n) && n > 0 ? n : null;
}

/**
 * Endereço digitado/bipado → código da etiqueta. Aceita sem separador, com espaço ou sem as letras M/N:
 * "R01-01-02", "R010102", "r1 1 2" → "R01-M01-N02"; igual ao sugerido ignorando separadores → o sugerido.
 */
export function lerEndereco(codigo: string, sugerido: string | null): string {
  const c = normalizar(codigo);
  const semSep = (v: string) => v.replace(/[^A-Z0-9]/g, '');
  if (sugerido && semSep(c) === semSep(normalizar(sugerido))) return normalizar(sugerido);
  if (c.includes('-M') && c.includes('-N')) return c;
  const m = /^([A-Z]+)\s*-?\s*(\d{1,3})[^A-Z0-9]*M?[^A-Z0-9]*(\d{1,3})[^A-Z0-9]*N?[^A-Z0-9]*(\d{1,3})$/.exec(c);
  // Só reescreve com o prefixo da rua do sugerido (ou prefixo curto sem sugestão): DOCA-1-101 continua DOCA-1-101.
  const pref = /^[A-Z]+/.exec(normalizar(sugerido ?? ''))?.[0];
  if (m && (pref ? m[1] === pref : m[1].length <= 2) && (c.length > 6 || /[^A-Z0-9]/.test(c))) {
    const d2 = (v: string) => v.padStart(2, '0');
    return `${m[1]}${d2(m[2])}-M${d2(m[3])}-N${d2(m[4])}`;
  }
  return c;
}

/** Partes do endereço para o destino em blocos: "R07-M01-N02" → Rua R07 · Módulo 01 · Nível 02. */
export function segmentos(codigo: string | null): { rotulo: string; valor: string }[] {
  if (!codigo) return [];
  const partes = codigo.split('-').filter((p) => p);
  if (partes.length < 2) return [{ rotulo: 'Endereço', valor: codigo }];
  return partes.map((p, i) => {
    // Valor EXATO da etiqueta (M01, N02) — mostrar só "01" fazia o operador digitar um código que não existe.
    if (i > 0 && /^M\d+$/i.test(p)) return { rotulo: 'Módulo', valor: p };
    if (i > 0 && /^N\d+$/i.test(p)) return { rotulo: 'Nível', valor: p };
    return { rotulo: i === 0 ? 'Rua' : 'Posição', valor: p };
  });
}
