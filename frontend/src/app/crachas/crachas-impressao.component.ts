import { Component, ElementRef, OnInit, computed, inject, signal } from '@angular/core';
import { ActivatedRoute, Router } from '@angular/router';
import { UsuarioService } from '../usuarios/usuario.service';
import { Usuario } from '../usuarios/usuario.model';
import { CrachaComponent } from '../shared/cracha/cracha.component';
import { CrachaLogoService } from '../shared/cracha/cracha-logo.service';
import { FuroCracha, OrientacaoCracha, dimensoes, nomeArquivoCracha } from '../shared/cracha/cracha-layout';
import { baixarPdfDeSvgs } from '../shared/cracha/cracha-pdf';
import { OqSpinnerComponent } from '../shared/icons/oq-spinner.component';

const A4 = { w: 210, h: 297 };

interface Folha {
  cartoes: { u: Usuario; x: number; y: number }[];
  /** Marcas de corte: segmentos [x1,y1,x2,y2] nas margens da folha. */
  marcas: number[][];
}

/**
 * Impressão de crachás em aba própria (/crachas?ids=a,b&orientacao=horizontal&modo=unico|a4[&imprimir=1])
 * — mesmo padrão das etiquetas: só o crachá na página, `@page` no tamanho exato,
 * sem nada do app em volta. `modo=unico`: uma página CR80 por crachá.
 * `modo=a4`: folhas A4 com vários crachás e marcas de corte (lote).
 */
@Component({
  selector: 'app-crachas-impressao',
  standalone: true,
  imports: [CrachaComponent, OqSpinnerComponent],
  template: `
    @if (carregando()) {
      <p class="cr-msg"><oq-spinner [size]="13" /> Preparando crachás…</p>
    } @else if (erro()) {
      <p class="cr-msg cr-msg--erro">{{ erro() }}</p>
    } @else {
      <div class="cr-toolbar">
        <button type="button" class="cr-btn cr-btn--primario" (click)="imprimir()">Imprimir</button>
        <button type="button" class="cr-btn" [disabled]="gerandoPdf()" (click)="baixarPdf()">
          {{ gerandoPdf() ? 'Gerando PDF…' : 'Baixar PDF' }}
        </button>
        <span class="cr-sep"></span>
        <button type="button" class="cr-btn" [class.cr-btn--ativo]="orientacao() === 'horizontal'" (click)="mudar({ orientacao: 'horizontal' })">Horizontal</button>
        <button type="button" class="cr-btn" [class.cr-btn--ativo]="orientacao() === 'vertical'" (click)="mudar({ orientacao: 'vertical' })">Vertical</button>
        <span class="cr-sep"></span>
        <button type="button" class="cr-btn" [class.cr-btn--ativo]="modo() === 'unico'" (click)="mudar({ modo: 'unico' })">1 por página (CR80)</button>
        <button type="button" class="cr-btn" [class.cr-btn--ativo]="modo() === 'a4'" (click)="mudar({ modo: 'a4' })">Folha A4 (lote)</button>
        <span class="cr-sep"></span>
        <span class="cr-rotulo">Furo:</span>
        <button type="button" class="cr-btn" [class.cr-btn--ativo]="furo() === 'retangular'" (click)="mudar({ furo: 'retangular' })">Retangular</button>
        <button type="button" class="cr-btn" [class.cr-btn--ativo]="furo() === 'redondo'" (click)="mudar({ furo: 'redondo' })">Redondo</button>
        <button type="button" class="cr-btn" [class.cr-btn--ativo]="furo() === 'nenhum'" (click)="mudar({ furo: 'nenhum' })">Sem marca</button>
        <span class="cr-info">{{ usuarios().length }} crachá(s){{ ignorados() ? ' · ' + ignorados() + ' sem código ignorado(s)' : '' }}</span>
      </div>

      @if (modo() === 'unico') {
        @for (u of usuarios(); track u.id) {
          <svg class="cr-pagina" [class.cr-pagina--h]="orientacao() === 'horizontal'" [class.cr-pagina--v]="orientacao() === 'vertical'"
               xmlns="http://www.w3.org/2000/svg" [attr.width]="dim().w + 'mm'" [attr.height]="dim().h + 'mm'"
               [attr.viewBox]="'0 0 ' + dim().w + ' ' + dim().h">
            <g appCracha [usuario]="u" [orientacao]="orientacao()" [furo]="furo()" [logo]="logo()"></g>
          </svg>
        }
      } @else {
        @for (f of folhas(); track $index) {
          <svg class="cr-pagina cr-pagina--a4" xmlns="http://www.w3.org/2000/svg" width="210mm" height="297mm" viewBox="0 0 210 297">
            <rect x="0" y="0" width="210" height="297" fill="#fff" />
            @for (m of f.marcas; track $index) {
              <line [attr.x1]="m[0]" [attr.y1]="m[1]" [attr.x2]="m[2]" [attr.y2]="m[3]" stroke="#000" stroke-width="0.2" />
            }
            @for (c of f.cartoes; track c.u.id) {
              <g appCracha [attr.transform]="'translate(' + c.x + ' ' + c.y + ')'" [usuario]="c.u" [orientacao]="orientacao()" [furo]="furo()" [logo]="logo()"></g>
            }
          </svg>
        }
      }
    }
  `,
  styles: `
    :host { display: block; background: #e9e9e9; min-height: 100vh; font-family: Arial, sans-serif; }
    .cr-msg { padding: 24px; font-size: 14px; display: flex; gap: 8px; align-items: center; }
    .cr-msg--erro { color: #c0392b; }
    .cr-toolbar { display: flex; flex-wrap: wrap; align-items: center; gap: 8px; padding: 12px 16px; background: #fff; border-bottom: 1px solid #ccc; }
    .cr-btn { border: 1px solid #1f6feb; background: #fff; color: #1f6feb; border-radius: 6px; padding: 7px 14px; font-size: 13px; cursor: pointer; }
    .cr-btn--primario, .cr-btn--ativo { background: #1f6feb; color: #fff; }
    .cr-btn:disabled { opacity: .6; cursor: default; }
    .cr-sep { width: 1px; height: 24px; background: #ccc; margin: 0 4px; }
    .cr-rotulo { color: #555; font-size: 13px; }
    .cr-info { margin-left: auto; color: #555; font-size: 13px; }
    .cr-pagina { display: block; margin: 16px auto; background: #fff; box-shadow: 0 2px 8px rgba(0,0,0,.2); }

    /* Página no tamanho exato de cada modo — named pages: cada <svg> diz em que tipo de página cai. */
    @page cracha-h { size: 85.6mm 54mm; margin: 0; }
    @page cracha-v { size: 54mm 85.6mm; margin: 0; }
    @page folha-a4 { size: 210mm 297mm; margin: 0; }

    @media print {
      :host { background: #fff; min-height: 0; }
      .cr-toolbar { display: none; }
      .cr-pagina { margin: 0; box-shadow: none; break-after: page; page-break-after: always; }
      .cr-pagina:last-child { break-after: auto; page-break-after: auto; }
      .cr-pagina--h { page: cracha-h; }
      .cr-pagina--v { page: cracha-v; }
      .cr-pagina--a4 { page: folha-a4; }
    }
  `,
})
export class CrachasImpressaoComponent implements OnInit {
  private readonly route = inject(ActivatedRoute);
  private readonly router = inject(Router);
  private readonly service = inject(UsuarioService);
  private readonly logoService = inject(CrachaLogoService);
  private readonly el = inject(ElementRef<HTMLElement>);

  readonly usuarios = signal<Usuario[]>([]);
  readonly ignorados = signal(0);
  readonly logo = signal<string | null>(null);
  readonly carregando = signal(true);
  readonly erro = signal<string | null>(null);
  readonly gerandoPdf = signal(false);
  readonly orientacao = signal<OrientacaoCracha>('horizontal');
  readonly modo = signal<'unico' | 'a4'>('unico');
  readonly furo = signal<FuroCracha>('retangular');

  readonly dim = computed(() => dimensoes(this.orientacao()));

  /** Grade A4: horizontal = 2 x 5 (10 por folha), vertical = 3 x 3 (9), centralizada, sem espaço entre cartões. */
  readonly folhas = computed<Folha[]>(() => {
    const { w, h } = this.dim();
    const cols = Math.floor(A4.w / w);
    const linhas = Math.floor((A4.h - 16) / h);
    const porFolha = cols * linhas;
    const ox = (A4.w - cols * w) / 2;
    const oy = (A4.h - linhas * h) / 2;
    const folhas: Folha[] = [];
    const lista = this.usuarios();
    for (let i = 0; i < lista.length; i += porFolha) {
      const cartoes = lista.slice(i, i + porFolha).map((u, k) => ({
        u,
        x: ox + (k % cols) * w,
        y: oy + Math.floor(k / cols) * h,
      }));
      // Marcas de corte nas margens, alinhadas a cada linha de corte da grade.
      const marcas: number[][] = [];
      const recuo = 1.5;
      const comprimento = 5;
      for (let c = 0; c <= cols; c++) {
        const x = ox + c * w;
        marcas.push([x, oy - recuo - comprimento, x, oy - recuo]);
        marcas.push([x, oy + linhas * h + recuo, x, oy + linhas * h + recuo + comprimento]);
      }
      for (let r = 0; r <= linhas; r++) {
        const y = oy + r * h;
        marcas.push([ox - recuo - comprimento, y, ox - recuo, y]);
        marcas.push([ox + cols * w + recuo, y, ox + cols * w + recuo + comprimento, y]);
      }
      folhas.push({ cartoes, marcas });
    }
    return folhas;
  });

  ngOnInit(): void {
    const q = this.route.snapshot.queryParamMap;
    const ids = (q.get('ids') ?? '').split(',').filter(Boolean);
    this.orientacao.set(q.get('orientacao') === 'vertical' ? 'vertical' : 'horizontal');
    this.modo.set(q.get('modo') === 'a4' ? 'a4' : 'unico');
    const furoQ = q.get('furo');
    this.furo.set(furoQ === 'redondo' || furoQ === 'nenhum' ? furoQ : 'retangular');
    const imprimirAoAbrir = q.get('imprimir') === '1';

    if (ids.length === 0) {
      this.erro.set('Nenhum usuário selecionado.');
      this.carregando.set(false);
      return;
    }

    Promise.all([this.logoService.obter(), new Promise<Usuario[]>((ok, falha) => this.service.listar().subscribe({ next: ok, error: falha }))])
      .then(([logo, todos]) => {
        const escolhidos = todos.filter((u) => ids.includes(u.id));
        const comCodigo = escolhidos.filter((u) => !!u.crachaoCodigo?.trim());
        this.logo.set(logo);
        this.usuarios.set(comCodigo);
        this.ignorados.set(escolhidos.length - comCodigo.length);
        if (comCodigo.length === 0) this.erro.set('Nenhum dos usuários selecionados tem código de crachá cadastrado.');
        this.carregando.set(false);
        if (imprimirAoAbrir && comCodigo.length > 0) setTimeout(() => window.print(), 300);
      })
      .catch(() => {
        this.erro.set('Falha ao carregar os usuários.');
        this.carregando.set(false);
      });
  }

  mudar(p: { orientacao?: OrientacaoCracha; modo?: 'unico' | 'a4'; furo?: FuroCracha }): void {
    if (p.orientacao) this.orientacao.set(p.orientacao);
    if (p.modo) this.modo.set(p.modo);
    if (p.furo) this.furo.set(p.furo);
    this.router.navigate([], {
      queryParams: { orientacao: this.orientacao(), modo: this.modo(), furo: this.furo(), imprimir: null },
      queryParamsHandling: 'merge',
      replaceUrl: true,
    });
  }

  imprimir(): void {
    window.print();
  }

  async baixarPdf(): Promise<void> {
    const svgs = Array.from((this.el.nativeElement as HTMLElement).querySelectorAll<SVGSVGElement>('svg.cr-pagina'));
    const lista = this.usuarios();
    const nome =
      lista.length === 1 && this.modo() === 'unico'
        ? nomeArquivoCracha(lista[0].crachaoCodigo ?? '', lista[0].nome)
        : `crachas_${lista.length}.pdf`;
    this.gerandoPdf.set(true);
    try {
      await baixarPdfDeSvgs(svgs, nome);
    } finally {
      this.gerandoPdf.set(false);
    }
  }
}
