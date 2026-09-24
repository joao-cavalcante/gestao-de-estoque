import { Component, Input, OnChanges } from '@angular/core';
import { FuroCracha, LayoutCracha, OrientacaoCracha, calcularLayout } from './cracha-layout';

export interface DadosCracha {
  nome: string;
  crachaoCodigo: string | null;
}

/**
 * Crachá de operador (CR80) desenhado em SVG, em milímetros. É um grupo SVG
 * (`<g appCracha>`), não um elemento HTML: assim o MESMO desenho entra num
 * <svg> de 85,6 x 54 mm (prévia/impressão unitária) ou várias vezes numa folha
 * A4 (lote) — e o PDF sai do próprio SVG via svg2pdf, vetorial, sem rasterizar.
 *
 *   <svg ...><g appCracha [usuario]="u" [orientacao]="'horizontal'" [logo]="dataUrl"></g></svg>
 *
 * Só preto e branco: térmica/laser de crachá, alto contraste.
 */
@Component({
  selector: 'g[appCracha]',
  standalone: true,
  template: `
    @if (l) {
      <svg:rect x="0.15" y="0.15" [attr.width]="l.w - 0.3" [attr.height]="l.h - 0.3" rx="3" ry="3"
                fill="#fff" stroke="#000" stroke-width="0.3" />

      <!-- Guia do furo do cordão (faixa de 10 mm livre no topo): tracejado cinza, sem preenchimento -->
      @if (l.furo; as f) {
        @if (f.tipo === 'retangular') {
          <svg:rect [attr.x]="f.x" [attr.y]="f.y" [attr.width]="f.w" [attr.height]="f.h" [attr.rx]="f.r" [attr.ry]="f.r"
                    fill="none" stroke="#6b6b6b" stroke-width="0.25" stroke-dasharray="0.8 0.5" />
        } @else {
          <svg:circle [attr.cx]="f.cx" [attr.cy]="f.cy" [attr.r]="f.r"
                      fill="none" stroke="#6b6b6b" stroke-width="0.25" stroke-dasharray="0.8 0.5" />
        }
      }

      <!-- Cabeçalho (abaixo da faixa do furo): logo (monocromático) + OPERADOR -->
      @if (logo) {
        <svg:image [attr.href]="logo" [attr.x]="l.cabecalho.logoX" [attr.y]="l.cabecalho.logoY"
                   [attr.width]="l.cabecalho.logoW" [attr.height]="l.cabecalho.logoH"
                   preserveAspectRatio="xMinYMid meet" />
      }
      <svg:text [attr.x]="l.cabecalho.operadorX" [attr.y]="l.cabecalho.operadorY" text-anchor="end"
                font-family="Arial, Helvetica, sans-serif" font-weight="bold" [attr.font-size]="l.cabecalho.operadorFonte"
                letter-spacing="0.35" fill="#000">OPERADOR</svg:text>
      <svg:line [attr.x1]="l.margem" [attr.x2]="l.w - l.margem" [attr.y1]="l.cabecalho.linhaY" [attr.y2]="l.cabecalho.linhaY"
                stroke="#000" stroke-width="0.3" />

      <!-- Nome: maior destaque; nunca cortado (encurta pra primeiro+último / encolhe) -->
      <svg:text [attr.x]="l.nome.x" [attr.y]="l.nome.y" text-anchor="middle"
                font-family="Arial, Helvetica, sans-serif" font-weight="bold" [attr.font-size]="l.nome.fonte" fill="#000"
                [attr.textLength]="l.nome.comprimir ? l.nome.larguraMax : null"
                [attr.lengthAdjust]="l.nome.comprimir ? 'spacingAndGlyphs' : null">{{ l.nome.texto }}</svg:text>

      <svg:text [attr.x]="l.numero.x" [attr.y]="l.numero.y" text-anchor="middle"
                font-family="Arial, Helvetica, sans-serif" font-weight="bold" [attr.font-size]="l.numero.fonte"
                fill="#000">{{ l.numero.texto }}</svg:text>

      <!-- Code 128 do código do crachá — exatamente o que a tela de conferência espera do leitor -->
      <svg:g [attr.transform]="'translate(' + l.barras.x + ' ' + l.barras.y + ')'" fill="#000">
        @for (r of l.barras.retangulos; track $index) {
          <svg:rect [attr.x]="r.x" y="0" [attr.width]="r.w" [attr.height]="l.barras.h" />
        }
      </svg:g>
      <svg:text [attr.x]="l.w / 2" [attr.y]="l.barras.textoY" text-anchor="middle"
                font-family="Arial, Helvetica, sans-serif" font-weight="bold" [attr.font-size]="l.barras.textoFonte"
                letter-spacing="0.3" fill="#000">{{ codigo }}</svg:text>
    }
  `,
})
export class CrachaComponent implements OnChanges {
  @Input({ required: true }) usuario!: DadosCracha;
  @Input() orientacao: OrientacaoCracha = 'horizontal';
  @Input() furo: FuroCracha = 'retangular';
  /** data URL do logo já em preto e branco (ver CrachaLogoService) — null = sem logo. */
  @Input() logo: string | null = null;

  l: LayoutCracha | null = null;

  get codigo(): string {
    return (this.usuario?.crachaoCodigo ?? '').trim();
  }

  ngOnChanges(): void {
    this.l = this.usuario ? calcularLayout(this.usuario.nome, this.codigo, this.orientacao, this.furo) : null;
  }
}
