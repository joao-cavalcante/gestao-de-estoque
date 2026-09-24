/**
 * PDF vetorial a partir dos <svg> já renderizados na tela (crachá unitário ou
 * folhas A4 do lote) — jsPDF + svg2pdf, carregados sob demanda (import
 * dinâmico) pra não pesar no bundle inicial da aplicação. Cada <svg> vira uma
 * página no tamanho físico exato dele (width/height em mm no próprio SVG).
 */
export async function baixarPdfDeSvgs(paginas: SVGSVGElement[], nomeArquivo: string): Promise<void> {
  if (paginas.length === 0) return;
  const [{ jsPDF }, { svg2pdf }] = await Promise.all([import('jspdf'), import('svg2pdf.js')]);

  const tamanho = (svg: SVGSVGElement): [number, number] => {
    const vb = svg.viewBox.baseVal;
    return [vb.width, vb.height];
  };

  const [w0, h0] = tamanho(paginas[0]);
  const doc = new jsPDF({ unit: 'mm', format: [w0, h0], orientation: w0 > h0 ? 'landscape' : 'portrait', compress: true });

  for (let i = 0; i < paginas.length; i++) {
    const [w, h] = tamanho(paginas[i]);
    if (i > 0) doc.addPage([w, h], w > h ? 'landscape' : 'portrait');
    await svg2pdf(paginas[i], doc, { x: 0, y: 0, width: w, height: h });
  }
  doc.save(nomeArquivo);
}
