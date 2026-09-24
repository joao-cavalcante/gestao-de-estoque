import { Component } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { CrachaComponent, DadosCracha } from './cracha.component';
import { OrientacaoCracha, calcularLayout, dimensoes, nomeArquivoCracha } from './cracha-layout';

@Component({
  standalone: true,
  imports: [CrachaComponent],
  template: `
    @for (c of casos; track $index) {
      <svg class="t" xmlns="http://www.w3.org/2000/svg" [attr.width]="dim(c.o).w + 'mm'" [attr.height]="dim(c.o).h + 'mm'"
           [attr.viewBox]="'0 0 ' + dim(c.o).w + ' ' + dim(c.o).h">
        <g appCracha [usuario]="c.u" [orientacao]="c.o"></g>
      </svg>
    }
  `,
})
class HostComponent {
  casos: { u: DadosCracha; o: OrientacaoCracha }[] = [
    { u: { nome: 'João Pereira', crachaoCodigo: '000123' }, o: 'horizontal' },
    { u: { nome: 'YURI ANTÔNIO DA SILVA XAVIER', crachaoCodigo: '000123' }, o: 'horizontal' },
    { u: { nome: 'MARIA DAS GRAÇAS CONCEIÇÃO DE ALBUQUERQUE MONTENEGRO', crachaoCodigo: 'OP-4521' }, o: 'horizontal' },
    { u: { nome: 'YURI ANTÔNIO DA SILVA XAVIER', crachaoCodigo: '000123' }, o: 'vertical' },
  ];
  dim = dimensoes;
}

describe('CrachaComponent', () => {
  let svgs: SVGSVGElement[];

  beforeEach(async () => {
    const f = TestBed.createComponent(HostComponent);
    f.detectChanges();
    await f.whenStable();
    svgs = Array.from(f.nativeElement.querySelectorAll('svg.t'));
  });

  it('nome nunca passa da largura útil (nome longo com acento incluso)', () => {
    svgs.forEach((svg, i) => {
      const texto = svg.querySelectorAll('text')[1] as SVGTextElement; // [0]=OPERADOR, [1]=nome
      const largura = texto.getComputedTextLength();
      const util = svg.viewBox.baseVal.width - 6;
      expect(largura).withContext(`caso ${i}: ${texto.textContent}`).toBeLessThanOrEqual(util + 0.2);
    });
  });

  it('acentos preservados; nome longo vira primeiro + último só quando não cabe', () => {
    const nomes = svgs.map((s) => (s.querySelectorAll('text')[1] as SVGTextElement).textContent?.trim());
    expect(nomes[0]).toBe('JOÃO PEREIRA');
    expect(nomes[1]).toBe('YURI ANTÔNIO DA SILVA XAVIER');
    expect(nomes[2]).toBe('MARIA MONTENEGRO');
  });

  it('código de barras horizontal com ≥ 50 mm e zona de silêncio ≥ 3 mm', () => {
    const l = calcularLayout('X', '000123', 'horizontal');
    expect(l.barras.w).toBeGreaterThanOrEqual(50);
    expect(l.barras.x).toBeGreaterThanOrEqual(3 + 3);
    expect(l.barras.x + l.barras.w).toBeLessThanOrEqual(85.6 - 6 + 1e-9);
    expect(l.barras.h).toBeCloseTo(12, 1);
  });

  it('texto sob as barras é exatamente o código (o que o leitor devolve)', () => {
    const textos = Array.from(svgs[0].querySelectorAll('text')).map((t) => t.textContent?.trim());
    expect(textos).toContain('000123');
    expect(textos).toContain('Nº 000123');
  });

  it('nome do arquivo', () => {
    expect(nomeArquivoCracha('000123', 'Yuri Antônio da Silva Xavier')).toBe('cracha_000123_yuri.pdf');
    expect(nomeArquivoCracha('000123', 'Ângela Souza')).toBe('cracha_000123_angela.pdf');
  });

  it('PDF vetorial no tamanho exato do CR80', async () => {
    const [{ jsPDF }, { svg2pdf }] = await Promise.all([import('jspdf'), import('svg2pdf.js')]);
    const doc = new jsPDF({ unit: 'mm', format: [85.6, 54], orientation: 'landscape' });
    await svg2pdf(svgs[1], doc, { x: 0, y: 0, width: 85.6, height: 54 });
    doc.addPage([54, 85.6], 'portrait');
    await svg2pdf(svgs[3], doc, { x: 0, y: 0, width: 54, height: 85.6 });
    expect(doc.internal.pageSize.getWidth()).toBeCloseTo(54, 1);
    doc.setPage(1);
    expect(doc.internal.pageSize.getWidth()).toBeCloseTo(85.6, 1);
    expect(doc.internal.pageSize.getHeight()).toBeCloseTo(54, 1);
    // Dump pra inspeção visual fora do teste (lido do log do Karma).
    console.log('PDF_DUMP::' + doc.output('datauristring'));
    console.log('SVG_DUMP::' + svgs.map((s) => s.outerHTML).join('\n'));
  });
});
