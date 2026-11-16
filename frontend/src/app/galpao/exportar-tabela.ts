/**
 * Exportação genérica das tabelas da planta (o que está na tela, já filtrado/ordenado): CSV com ";" e vírgula
 * decimal (abre direto no Excel pt-BR) e Excel com exceljs (carregado só no clique).
 */

export type ValorCelula = string | number | boolean | null | undefined;

export interface ColunaExportacao<T> {
  titulo: string;
  valor: (linha: T) => ValorCelula;
  /** Formato numérico do Excel (ex.: '#,##0.###'). */
  formato?: string;
  largura?: number;
}

function baixar(blob: Blob, nome: string): void {
  const url = URL.createObjectURL(blob);
  const a = document.createElement('a');
  a.href = url;
  a.download = nome;
  a.click();
  setTimeout(() => URL.revokeObjectURL(url), 1000);
}

function normalizar(v: ValorCelula): string | number | null {
  if (v == null) return null;
  if (typeof v === 'boolean') return v ? 'Sim' : 'Não';
  return v;
}

export function exportarCsv<T>(linhas: T[], colunas: ColunaExportacao<T>[], nome: string): void {
  const campo = (v: ValorCelula) => {
    const n = normalizar(v);
    if (n == null) return '';
    const s = typeof n === 'number' ? String(Math.round(n * 1000) / 1000).replace('.', ',') : n;
    return /[;"\n]/.test(s) ? `"${s.replace(/"/g, '""')}"` : s;
  };
  const corpo = linhas.map((l) => colunas.map((c) => campo(c.valor(l))).join(';'));
  const texto = '﻿' + [colunas.map((c) => c.titulo).join(';'), ...corpo].join('\r\n');
  baixar(new Blob([texto], { type: 'text/csv;charset=utf-8' }), `${nome}.csv`);
}

export async function exportarExcel<T>(linhas: T[], colunas: ColunaExportacao<T>[], nome: string, aba = 'Dados'): Promise<void> {
  const mod: any = await import('exceljs');
  const ExcelJS = mod.default ?? mod;
  const wb = new ExcelJS.Workbook();
  wb.created = new Date();
  const ws = wb.addWorksheet(aba, { views: [{ state: 'frozen', ySplit: 1 }] });
  ws.columns = colunas.map((c, i) => ({ header: c.titulo, key: 'c' + i, width: c.largura ?? 14, style: c.formato ? { numFmt: c.formato } : {} }));
  ws.getRow(1).font = { bold: true };
  linhas.forEach((l) => ws.addRow(Object.fromEntries(colunas.map((c, i) => ['c' + i, normalizar(c.valor(l))]))));
  ws.autoFilter = { from: 'A1', to: { row: 1, column: colunas.length } };
  const buf = await wb.xlsx.writeBuffer();
  baixar(new Blob([buf], { type: 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet' }), `${nome}.xlsx`);
}

/** Nome de arquivo seguro: "alertas-CD_Teste". */
export function nomeArquivo(prefixo: string, planta: string | null | undefined): string {
  return `${prefixo}-${(planta ?? 'planta').replace(/[^\w-]+/g, '_')}`;
}
