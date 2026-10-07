import { OrdemCargaResumoDto, PedidoSemOrdemCargaDto } from './mapa-separacao.model';

/**
 * Exporta o painel do Mapa de Separação JÁ filtrado (busca, situação, modalidade) pra .xlsx (exceljs, carregado
 * só no clique — mesmo padrão da Consulta de Produtos). Abas: "Ordens de Carga", "Pedidos complementares"
 * (só quando houver) e "Pedidos sem OC".
 */
export async function exportarMapaExcel(ocs: OrdemCargaResumoDto[], pedidosSemOc: PedidoSemOrdemCargaDto[], nomeArquivo: string): Promise<void> {
  const mod: any = await import('exceljs');
  const ExcelJS = mod.default ?? mod;
  const wb = new ExcelJS.Workbook();
  wb.created = new Date();

  const FORMATO_KG = '#,##0.0';
  const dataHora = (iso: string | null | undefined) => (iso ? new Date(iso) : null);
  const situacaoOc = (oc: OrdemCargaResumoDto) =>
    (oc.pedidosNovos?.length ?? 0) > 0 ? 'Com pedido complementar' : oc.impressoEm ? 'Impressa' : 'Não impressa';
  const modalidade = (p: PedidoSemOrdemCargaDto) =>
    [p.modalidade?.express && 'Express', p.modalidade?.retira && 'Cliente retira', p.modalidade?.entrega && 'Entrega'].filter(Boolean).join(', ');

  const abas = [];

  if (ocs.length) {
    const aba = wb.addWorksheet('Ordens de Carga', { views: [{ state: 'frozen', ySplit: 1 }] });
    aba.columns = [
      { header: 'Situação', key: 'situacao', width: 24 },
      { header: 'Ordem de Carga', key: 'oc', width: 15 },
      { header: 'Peso a separar (kg)', key: 'pesoPendente', width: 18, style: { numFmt: FORMATO_KG } },
      { header: 'Peso total (kg)', key: 'pesoTotal', width: 15, style: { numFmt: FORMATO_KG } },
      { header: 'Data prev. saída', key: 'saida', width: 15 },
      { header: 'Placa', key: 'placa', width: 11 },
      { header: 'Motorista', key: 'motorista', width: 30 },
      { header: 'Pedidos', key: 'pedidos', width: 9 },
      { header: 'Conferidos', key: 'conferidos', width: 11 },
      { header: 'Express', key: 'express', width: 9 },
      { header: 'Cliente retira', key: 'retira', width: 13 },
      { header: 'Entrega', key: 'entrega', width: 9 },
      { header: 'Impresso em', key: 'impressoEm', width: 17, style: { numFmt: 'dd/mm/yyyy hh:mm' } },
      { header: 'Impresso por', key: 'impressoPor', width: 22 },
      { header: 'Fechada no Sankhya', key: 'fechada', width: 18 },
      { header: 'Última alteração da OC', key: 'alteracao', width: 21 },
      { header: 'Pedidos complementares', key: 'complementares', width: 28 },
    ];
    ocs.forEach((oc) =>
      aba.addRow({
        situacao: situacaoOc(oc),
        oc: oc.ordemCarga,
        pesoPendente: oc.pesoPendenteKg ?? 0,
        pesoTotal: oc.pesoTotalKg ?? 0,
        saida: oc.dataPrevSaida,
        placa: oc.placa ?? '',
        motorista: oc.nomeMotorista ?? '',
        pedidos: oc.totalNotas,
        conferidos: oc.notasConferidas,
        express: oc.qtdExpress ?? 0,
        retira: oc.qtdRetira ?? 0,
        entrega: oc.qtdEntrega ?? 0,
        impressoEm: dataHora(oc.impressoEm),
        impressoPor: oc.impressoPor ?? '',
        fechada: oc.situacao === 'F' ? 'Sim' : 'Não',
        alteracao: oc.ultimaAlteracao ?? '',
        complementares: (oc.pedidosNovos ?? []).map((p) => p.nunota).join(', '),
      }),
    );
    abas.push(aba);

    const complementares = ocs.flatMap((oc) => (oc.pedidosNovos ?? []).map((p) => ({ oc, p })));
    if (complementares.length) {
      const abaC = wb.addWorksheet('Pedidos complementares', { views: [{ state: 'frozen', ySplit: 1 }] });
      abaC.columns = [
        { header: 'Ordem de Carga', key: 'oc', width: 15 },
        { header: 'OC impressa em', key: 'impressoEm', width: 17, style: { numFmt: 'dd/mm/yyyy hh:mm' } },
        { header: 'Nº único', key: 'nunota', width: 11 },
        { header: 'Nº pedido', key: 'numNota', width: 11 },
        { header: 'Parceiro', key: 'parceiro', width: 40 },
        { header: 'Peso (kg)', key: 'peso', width: 11, style: { numFmt: FORMATO_KG } },
      ];
      complementares.forEach(({ oc, p }) =>
        abaC.addRow({
          oc: oc.ordemCarga,
          impressoEm: dataHora(oc.impressoEm),
          nunota: p.nunota,
          numNota: p.numNota ?? '',
          parceiro: p.nomeParceiro ?? '',
          peso: p.pesoKg ?? 0,
        }),
      );
      abas.push(abaC);
    }
  }

  if (pedidosSemOc.length) {
    const abaP = wb.addWorksheet('Pedidos sem OC', { views: [{ state: 'frozen', ySplit: 1 }] });
    abaP.columns = [
      { header: 'Situação', key: 'situacao', width: 14 },
      { header: 'Modalidade', key: 'modalidade', width: 18 },
      { header: 'Nº único', key: 'nunota', width: 11 },
      { header: 'Nota', key: 'numNota', width: 11 },
      { header: 'Data', key: 'data', width: 12 },
      { header: 'Cód. parceiro', key: 'codParc', width: 13 },
      { header: 'Parceiro', key: 'parceiro', width: 40 },
      { header: 'Peso (kg)', key: 'peso', width: 11, style: { numFmt: FORMATO_KG } },
      { header: 'Impresso em', key: 'impressoEm', width: 17, style: { numFmt: 'dd/mm/yyyy hh:mm' } },
      { header: 'Impresso por', key: 'impressoPor', width: 22 },
    ];
    pedidosSemOc.forEach((p) =>
      abaP.addRow({
        situacao: p.impressoEm ? 'Impresso' : 'Não impresso',
        modalidade: modalidade(p),
        nunota: p.nunota,
        numNota: p.numNota ?? '',
        data: p.dataMovimento ?? '',
        codParc: p.codParc ?? '',
        parceiro: p.nomeParceiro ?? '',
        peso: p.pesoKg ?? 0,
        impressoEm: dataHora(p.impressoEm),
        impressoPor: p.impressoPor ?? '',
      }),
    );
    abas.push(abaP);
  }

  for (const aba of abas) {
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
