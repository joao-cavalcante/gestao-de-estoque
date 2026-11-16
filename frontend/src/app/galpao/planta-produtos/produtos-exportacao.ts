import { MetricaAbc, ProdutoAnalise, ROTULO_SETOR_PRODUTO, ROTULO_VALIDADE, abcDa } from '../galpao.model';

/** Exporta a tabela de produtos JÁ filtrada/ordenada (CSV direto; Excel com exceljs, carregado só no clique). */

type Valor = string | number | null;

const COLUNAS: [string, (p: ProdutoAnalise, m: MetricaAbc) => Valor, string?][] = [
  ['Código', (p) => p.codprod],
  ['Produto', (p) => p.descricao],
  ['Ativo', (p) => (p.ativo ? 'Sim' : 'Não')],
  ['Grupo', (p) => p.grupo],
  ['Setor', (p) => (p.setor ? ROTULO_SETOR_PRODUTO[p.setor] : 'N/D')],
  ['ABC', (p, m) => abcDa(p, m).classe],
  ['Participação (%)', (p, m) => abcDa(p, m).participacao, '0.00'],
  ['Acumulado (%)', (p, m) => abcDa(p, m).acumulado, '0.00'],
  ['ABC valor', (p) => p.abc],
  ['ABC quantidade', (p) => p.abcQtd],
  ['ABC frequência', (p) => p.abcFreq],
  ['Valor vendido', (p) => p.valorVendido, '#,##0.00'],
  ['Quantidade vendida', (p) => p.quantidadeVendida, '#,##0.###'],
  ['Nº de vendas', (p) => p.frequencia],
  ['Última venda', (p) => p.ultimaVenda],
  ['Estoque', (p) => p.estoque, '#,##0.###'],
  ['Reservado', (p) => p.reservado, '#,##0.###'],
  ['Disponível', (p) => p.disponivel, '#,##0.###'],
  ['Endereçado', (p) => p.enderecado, '#,##0.###'],
  ['Demanda/dia', (p) => p.demandaDia, '#,##0.###'],
  ['Cobertura (dias)', (p) => p.cobertura, '#,##0.0'],
  ['Validade', (p) => p.dtval],
  ['Faixa de validade', (p) => ROTULO_VALIDADE[p.validade]],
  ['Parado', (p) => (p.parado ? 'Sim' : 'Não')],
  ['Idade (dias)', (p) => p.idadeDias],
  ['Origem da idade', (p) => p.idadeOrigem],
  ['Saúde', (p) => p.saude.score],
  ['Faixa de saúde', (p) => p.saude.faixa],
  ['Confiança da saúde (%)', (p) => Math.round(p.saude.confianca * 100)],
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
export function exportarProdutosCsv(produtos: ProdutoAnalise[], metrica: MetricaAbc, nome: string): void {
  const campo = (v: Valor) => {
    if (v == null) return '';
    const s = typeof v === 'number' ? String(Math.round(v * 1000) / 1000).replace('.', ',') : String(v);
    return /[;"\n]/.test(s) ? `"${s.replace(/"/g, '""')}"` : s;
  };
  const corpo = produtos.map((p) => COLUNAS.map(([, f]) => campo(f(p, metrica))).join(';'));
  const texto = '﻿' + [COLUNAS.map(([t]) => t).join(';'), ...corpo].join('\r\n');
  baixar(new Blob([texto], { type: 'text/csv;charset=utf-8' }), `${nome}.csv`);
}

export async function exportarProdutosExcel(produtos: ProdutoAnalise[], metrica: MetricaAbc, nome: string): Promise<void> {
  const mod: any = await import('exceljs');
  const ExcelJS = mod.default ?? mod;
  const wb = new ExcelJS.Workbook();
  wb.created = new Date();
  const aba = wb.addWorksheet('Produtos', { views: [{ state: 'frozen', ySplit: 1 }] });
  aba.columns = COLUNAS.map(([t, , fmt], i) => ({
    header: t,
    key: 'c' + i,
    width: t === 'Produto' ? 50 : t === 'Grupo' ? 24 : 13,
    style: fmt ? { numFmt: fmt } : {},
  }));
  aba.getRow(1).font = { bold: true };
  produtos.forEach((p) => aba.addRow(Object.fromEntries(COLUNAS.map(([, f], i) => ['c' + i, f(p, metrica)]))));
  aba.autoFilter = { from: 'A1', to: { row: 1, column: COLUNAS.length } };
  const buf = await wb.xlsx.writeBuffer();
  baixar(new Blob([buf], { type: 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet' }), `${nome}.xlsx`);
}
