import { ProdutoEstoque } from './consulta-produtos.model';

/**
 * Exporta a lista JÁ filtrada/ordenada da tela pra .xlsx de verdade (exceljs, carregado só no clique
 * — não pesa na abertura do app). Duas abas: "Produtos" (totais) e "Por local" (empresa/local/lote).
 */
export async function exportarProdutosExcel(produtos: ProdutoEstoque[], nomeArquivo: string): Promise<void> {
  const mod: any = await import('exceljs');
  const ExcelJS = mod.default ?? mod;
  const wb = new ExcelJS.Workbook();
  wb.created = new Date();

  const FORMATO_QTD = '#,##0.###';

  const abaProdutos = wb.addWorksheet('Produtos', { views: [{ state: 'frozen', ySplit: 1 }] });
  abaProdutos.columns = [
    { header: 'Código', key: 'codprod', width: 10 },
    { header: 'Produto', key: 'descricao', width: 45 },
    { header: 'Complemento', key: 'complemento', width: 20 },
    { header: 'Marca', key: 'marca', width: 18 },
    { header: 'Referência', key: 'referencia', width: 16 },
    { header: 'Unid.', key: 'unidade', width: 8 },
    { header: 'Pesável', key: 'pesavel', width: 9 },
    { header: 'Estoque', key: 'estoque', width: 12, style: { numFmt: FORMATO_QTD } },
    { header: 'Reservado', key: 'reservado', width: 12, style: { numFmt: FORMATO_QTD } },
    { header: 'Disponível', key: 'disponivel', width: 12, style: { numFmt: FORMATO_QTD } },
  ];
  produtos.forEach((p) =>
    abaProdutos.addRow({
      codprod: p.codprod,
      descricao: p.descricao,
      complemento: p.complemento ?? '',
      marca: p.marca ?? '',
      referencia: p.referencia ?? '',
      unidade: p.unidade ?? '',
      pesavel: p.pesavel == null ? '' : p.pesavel ? 'Sim' : 'Não',
      estoque: p.estoque,
      reservado: p.reservado,
      disponivel: p.disponivel,
    }),
  );

  const abaLocais = wb.addWorksheet('Por local', { views: [{ state: 'frozen', ySplit: 1 }] });
  abaLocais.columns = [
    { header: 'Código', key: 'codprod', width: 10 },
    { header: 'Produto', key: 'descricao', width: 45 },
    { header: 'Unid.', key: 'unidade', width: 8 },
    { header: 'Cód. Empresa', key: 'codemp', width: 12 },
    { header: 'Empresa', key: 'empresa', width: 25 },
    { header: 'Cód. Local', key: 'codlocal', width: 11 },
    { header: 'Local', key: 'local', width: 25 },
    { header: 'Lote / Controle', key: 'controle', width: 16 },
    { header: 'Estoque', key: 'estoque', width: 12, style: { numFmt: FORMATO_QTD } },
    { header: 'Reservado', key: 'reservado', width: 12, style: { numFmt: FORMATO_QTD } },
    { header: 'Disponível', key: 'disponivel', width: 12, style: { numFmt: FORMATO_QTD } },
  ];
  produtos.forEach((p) =>
    p.locais.forEach((l) =>
      abaLocais.addRow({
        codprod: p.codprod,
        descricao: p.descricao,
        unidade: p.unidade ?? '',
        codemp: l.codemp,
        empresa: l.empresa ?? '',
        codlocal: l.codlocal,
        local: l.local ?? '',
        controle: l.controle?.trim() ?? '',
        estoque: l.estoque,
        reservado: l.reservado,
        disponivel: l.disponivel,
      }),
    ),
  );

  for (const aba of [abaProdutos, abaLocais]) {
    aba.getRow(1).font = { bold: true };
    aba.autoFilter = { from: { row: 1, column: 1 }, to: { row: 1, column: aba.columnCount } };
  }

  const buffer = await wb.xlsx.writeBuffer();
  const blob = new Blob([buffer], { type: 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet' });
  const url = URL.createObjectURL(blob);
  const a = document.createElement('a');
  a.href = url;
  a.download = nomeArquivo;
  document.body.appendChild(a);
  a.click();
  a.remove();
  setTimeout(() => URL.revokeObjectURL(url), 1000);
}
