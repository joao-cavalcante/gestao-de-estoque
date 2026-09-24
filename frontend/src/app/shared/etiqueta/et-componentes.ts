import { Component, Input, OnChanges, inject, signal } from '@angular/core';
import { AuthService } from '../../auth/auth.service';
import { code128B, larguraModulos } from '../code128';

/**
 * Peças comuns das etiquetas térmicas 100 x 100 mm (volume e peso). Estilo
 * vem de _etiqueta.scss — mudar lá (ou aqui) muda as duas etiquetas juntas.
 */

/** Texto longo → classe que reduz a fonte (ver et-texto-medio). */
export function classeTamanhoTexto(texto: string | null | undefined): string {
  const n = (texto ?? '').length;
  return n > 48 ? 'muito-longo' : n > 32 ? 'longo' : '';
}

// ─── Cabeçalho: logo mono à esquerda + (selo) + data/hora à direita ─────────

@Component({
  selector: 'oq-et-cabecalho',
  standalone: true,
  host: { class: 'et-cab' },
  template: `
    @if (logoSrc()) {
      <img class="et-cab__logo" [src]="logoSrc()!" alt="" (error)="onLogoErro()" />
    }
    @if (selo) {
      <span class="et-cab__selo">{{ selo }}</span>
    }
    <span class="et-cab__data">{{ data }}</span>
  `,
  styles: `
    @use '../etiqueta/etiqueta' as *;
    :host {
      display: flex;
      align-items: center;
      gap: 3mm;
      padding-bottom: 1mm;
      min-height: 0;
    }
    .et-cab__logo {
      max-height: 9.5mm;
      max-width: 45mm;
      object-fit: contain;
      /* térmica: logo em preto sólido, sem cor/gradiente */
      filter: grayscale(1) contrast(1.8) brightness(0.9);
    }
    .et-cab__selo {
      background: #000;
      color: #fff;
      font-size: 7.5pt;
      font-weight: 800;
      letter-spacing: 0.04em;
      padding: 0.8mm 1.5mm;
      border-radius: 1mm;
      -webkit-print-color-adjust: exact;
      print-color-adjust: exact;
    }
    .et-cab__data {
      margin-left: auto;
      font-size: 7.5pt;
      font-weight: 700;
      white-space: nowrap;
    }
  `,
})
export class EtCabecalhoComponent {
  private readonly auth = inject(AuthService);
  @Input() data = new Date().toLocaleString('pt-BR');
  /** Selo opcional (ex.: "CORREÇÃO · RECONTAGEM"). */
  @Input() selo: string | null = null;

  /** Logo do cliente — assets/logos/<slug>.png, fallback .jpg; sem arquivo → some. */
  readonly logoSrc = signal<string | null>(null);
  private tentouJpg = false;

  constructor() {
    const slug = this.auth.obterTenantSlug();
    if (slug) this.logoSrc.set(`/assets/logos/${slug}.png`);
  }

  onLogoErro(): void {
    const slug = this.auth.obterTenantSlug();
    if (!this.tentouJpg && slug) {
      this.tentouJpg = true;
      this.logoSrc.set(`/assets/logos/${slug}.jpg`);
    } else {
      this.logoSrc.set(null);
    }
  }
}

// ─── Bloco CLIENTE: código + nome (até 2-3 linhas) + UF em bloco preto ──────

@Component({
  selector: 'oq-et-cliente',
  standalone: true,
  host: { class: 'et-cliente' },
  template: `
    <div class="et-cliente__txt">
      <span class="et-rotulo">CLIENTE</span>
      @if (codigo) {
        <span class="et-cliente__cod">{{ codigo }}</span>
      }
      <span class="et-cliente__nome" [class]="'et-cliente__nome ' + classeNome">{{ nome || '—' }}</span>
    </div>
    <div class="et-cliente__uf">
      <span class="et-cliente__uf-rotulo">UF</span>
      <span class="et-cliente__uf-valor">{{ uf || '—' }}</span>
    </div>
  `,
  styles: `
    @use '../etiqueta/etiqueta' as *;
    :host {
      @include et-secao;
      display: flex;
      align-items: stretch;
      gap: 2mm;
      padding: 1.5mm 0;
    }
    .et-rotulo { @include et-rotulo; }
    .et-cliente__txt { flex: 1; min-width: 0; display: flex; flex-direction: column; gap: 0.6mm; }
    .et-cliente__cod { font-size: 8pt; font-weight: 700; line-height: 1; }
    .et-cliente__nome { @include et-texto-medio; }
    .et-cliente__uf {
      flex: none;
      width: 20mm;
      background: #000;
      color: #fff;
      border-radius: 1.5mm;
      display: flex;
      flex-direction: column;
      align-items: center;
      justify-content: center;
      -webkit-print-color-adjust: exact;
      print-color-adjust: exact;
    }
    .et-cliente__uf-rotulo { font-size: 7pt; font-weight: 700; letter-spacing: 0.08em; line-height: 1; }
    .et-cliente__uf-valor { font-size: 22pt; font-weight: 900; line-height: 1; }
  `,
})
export class EtClienteComponent implements OnChanges {
  @Input() codigo: string | number | null | undefined = null;
  @Input() nome: string | null | undefined = null;
  @Input() uf: string | null | undefined = null;
  classeNome = '';

  ngOnChanges(): void {
    this.classeNome = classeTamanhoTexto(this.nome);
  }
}

// ─── Caixas de dígito (bordas arredondadas; "-" vira traço fora das caixas) ─

@Component({
  selector: 'oq-et-caixas',
  standalone: true,
  host: { class: 'et-caixas', '[style.height.mm]': 'altura' },
  template: `
    @for (c of caracteres; track $index) {
      @if (c === '-' || c === '/') {
        <span [class]="c === '-' ? 'et-caixas__traco' : 'et-caixas__barra'" [style.font-size.mm]="fonte">{{ c === '/' ? '/' : '' }}</span>
      } @else {
        <span class="et-caixas__caixa" [style.font-size.mm]="fonte" [style.max-width.mm]="larguraMax"
              [style.flex]="larguraFixa ? 'none' : '1 1 0'" [style.width.mm]="larguraFixa">{{ c }}</span>
      }
    }
  `,
  styles: `
    @use '../etiqueta/etiqueta' as *;
    :host {
      display: flex;
      align-items: stretch;
      justify-content: center;
      gap: $et-caixa-gap;
    }
    .et-caixas__caixa {
      box-sizing: border-box;
      min-width: 0;
      border: $et-caixa-borda;
      border-radius: $et-caixa-raio;
      display: flex;
      align-items: center;
      justify-content: center;
      font-weight: 900;
      line-height: 1;
    }
    .et-caixas__traco { flex: none; align-self: center; width: 3.5mm; height: 1.4mm; background: #000; }
    .et-caixas__barra { flex: none; align-self: center; font-weight: 900; line-height: 1; }
  `,
})
export class EtCaixasComponent implements OnChanges {
  /** Texto a desenhar, um caractere por caixa ("57797-56", "03/05"). */
  @Input({ required: true }) texto = '';
  /** Altura das caixas em mm. */
  @Input() altura = 19;
  /** Largura FIXA de cada caixa (mm) — sem ela, as caixas dividem a largura disponível. */
  @Input() larguraFixa: number | null = null;
  /** Largura útil da linha (mm) pra calcular a fonte. */
  @Input() larguraDisponivel = 94;

  caracteres: string[] = [];
  fonte = 12;
  larguraMax = 12;

  ngOnChanges(): void {
    this.caracteres = this.texto.split('');
    const n = this.caracteres.length || 1;
    this.larguraMax = this.larguraFixa ?? this.altura * 0.63;
    const larguraCaixa = this.larguraFixa ?? Math.min(this.larguraMax, this.larguraDisponivel / n);
    // Fonte cabe na caixa pelos dois lados: altura e largura.
    this.fonte = Math.min(this.altura * 0.68, larguraCaixa * 1.35);
  }
}

// ─── Code 128 com texto legível embaixo ─────────────────────────────────────

@Component({
  selector: 'oq-et-barras',
  standalone: true,
  host: { class: 'et-barras' },
  template: `
    <svg [attr.viewBox]="'0 0 ' + total + ' 1'" preserveAspectRatio="none" [style.height.mm]="altura" aria-hidden="true">
      @for (b of retangulos; track $index) {
        <rect [attr.x]="b.x" y="0" [attr.width]="b.w" height="1" />
      }
    </svg>
    <span class="et-barras__txt">{{ texto }}</span>
  `,
  styles: `
    :host {
      display: flex;
      flex-direction: column;
      justify-content: flex-end;
      gap: 0.8mm;
      min-width: 0;
      /* zona de silêncio do Code 128 (≥ 10 módulos) */
      padding: 0 2.5mm;
    }
    svg { width: 100%; fill: #000; shape-rendering: crispEdges; }
    .et-barras__txt { font-size: 7.5pt; font-weight: 700; text-align: center; letter-spacing: 0.05em; }
  `,
})
export class EtBarrasComponent implements OnChanges {
  @Input({ required: true }) texto = '';
  @Input() altura = 14;

  total = 0;
  retangulos: { x: number; w: number }[] = [];

  ngOnChanges(): void {
    const larguras = code128B(this.texto);
    const r: { x: number; w: number }[] = [];
    let x = 0;
    larguras.forEach((w, i) => {
      if (i % 2 === 0) r.push({ x, w }); // par = barra, ímpar = espaço
      x += w;
    });
    this.retangulos = r;
    this.total = larguraModulos(larguras);
  }
}

export const ET_COMPONENTES = [EtCabecalhoComponent, EtClienteComponent, EtCaixasComponent, EtBarrasComponent] as const;
