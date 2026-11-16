import { Endereco } from './galpao.model';

/** Exporta a tabela de posições JÁ filtrada/ordenada (CSV direto; Excel com exceljs, carregado só no clique). */

interface Linha {
  endereco: string;
  estrutura: string;
  modulo: number;
  nivel: number;
  setor: string;
  tipo: string;
  fisico: number;
  ocupacao: number | null;
  base: string;
  capQtd: number | null;
  capPeso: number | null;
  capVolume: number | null;
  bloqueado: string;
  produtos: string;
}

function linhas(enderecos: Endereco[], nomeSetor: (c: string) => string): Linha[] {
  return enderecos.map((e) => ({
    endereco: e.codigo,
    estrutura: e.estrutura,
    modulo: e.modulo,
    nivel: e.nivel,
    setor: nomeSetor(e.setor),
    tipo: e.tipo === 'PICKING' ? 'Picking' : 'Pulmão',
    fisico: e.quantidade,
    ocupacao: e.ocupacao,
    base: e.baseOcupacao === 'PESO' ? 'Peso' : e.baseOcupacao === 'VOLUME' ? 'Volume' : e.baseOcupacao === 'QUANTIDADE' ? 'Quantidade' : 'N/D',
    capQtd: e.capQtd,
    capPeso: e.capPeso,
    capVolume: e.capVolume,
    bloqueado: e.bloqueado ? 'Sim' : 'Não',
    produtos: e.saldos.map((s) => `${s.codprod} - ${s.produto}${s.controle ? ' (' + s.controle + ')' : ''}: ${s.quantidade}`).join(' | '),
  }));
}

const CABECALHO: [keyof Linha, string][] = [
  ['endereco', 'Endereço'],
  ['estrutura', 'Estrutura'],
  ['modulo', 'Módulo'],
  ['nivel', 'Nível'],
  ['setor', 'Setor'],
  ['tipo', 'Tipo'],
  ['fisico', 'Físico'],
  ['ocupacao', 'Ocupação'],
  ['base', 'Base'],
  ['capQtd', 'Capacidade (un)'],
  ['capPeso', 'Capacidade (kg)'],
  ['capVolume', 'Capacidade (m³)'],
  ['bloqueado', 'Bloqueado'],
  ['produtos', 'Produtos'],
];

function baixar(blob: Blob, nome: string): void {
  const url = URL.createObjectURL(blob);
  const a = document.createElement('a');
  a.href = url;
  a.download = nome;
  a.click();
  setTimeout(() => URL.revokeObjectURL(url), 1000);
}

/** CSV com ";" e vírgula decimal (abre direto no Excel em pt-BR). */
export function exportarEnderecosCsv(enderecos: Endereco[], nomeSetor: (c: string) => string, nome: string): void {
  const campo = (v: unknown) => {
    if (v == null) return '';
    const s = typeof v === 'number' ? String(v).replace('.', ',') : String(v);
    return /[;"\n]/.test(s) ? `"${s.replace(/"/g, '""')}"` : s;
  };
  const corpo = linhas(enderecos, nomeSetor).map((l) =>
    CABECALHO.map(([k]) => campo(k === 'ocupacao' && l.ocupacao != null ? Math.round(l.ocupacao * 1000) / 10 : l[k])).join(';'),
  );
  const texto = '﻿' + [CABECALHO.map(([, t]) => (t === 'Ocupação' ? 'Ocupação (%)' : t)).join(';'), ...corpo].join('\r\n');
  baixar(new Blob([texto], { type: 'text/csv;charset=utf-8' }), `${nome}.csv`);
}

export async function exportarEnderecosExcel(enderecos: Endereco[], nomeSetor: (c: string) => string, nome: string): Promise<void> {
  const mod: any = await import('exceljs');
  const ExcelJS = mod.default ?? mod;
  const wb = new ExcelJS.Workbook();
  wb.created = new Date();
  const aba = wb.addWorksheet('Endereços', { views: [{ state: 'frozen', ySplit: 1 }] });
  const largura: Partial<Record<keyof Linha, number>> = { endereco: 18, setor: 14, produtos: 70 };
  const formato: Partial<Record<keyof Linha, string>> = {
    fisico: '#,##0.###',
    ocupacao: '0.0%',
    capQtd: '#,##0.##',
    capPeso: '#,##0.##',
    capVolume: '#,##0.###',
  };
  aba.columns = CABECALHO.map(([k, t]) => ({ header: t, key: k, width: largura[k] ?? 12, style: formato[k] ? { numFmt: formato[k] } : {} }));
  aba.getRow(1).font = { bold: true };
  linhas(enderecos, nomeSetor).forEach((l) => aba.addRow(l));
  aba.autoFilter = { from: 'A1', to: { row: 1, column: CABECALHO.length } };
  const buf = await wb.xlsx.writeBuffer();
  baixar(new Blob([buf], { type: 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet' }), `${nome}.xlsx`);
}
