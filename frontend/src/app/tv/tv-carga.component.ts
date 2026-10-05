import { Component, Input, OnDestroy, OnInit, computed, signal } from '@angular/core';
import { TvOrdemCarga, TvResumo } from './tv.model';

const OC_POR_PAGINA = 8;
const INTERVALO_PAGINA_MS = 10_000;

/**
 * Tela "Ordens de carga" da TV de saídas — alterna com a tela de conferência (ver TvComponent.visao).
 * Só exibe o que já vem no resumo: totais a fazer, e por OC pedidos prontos/total, progresso e peso
 * que falta. Peso aguardando liberação/corte conta no total, mas aparece destacado.
 */
@Component({
  selector: 'oq-tv-carga',
  standalone: true,
  host: { class: 'tv-carga' },
  template: `
    <section class="tv-carga__totais">
      <div class="tv-carga__total">
        <span class="tv-carga__val">{{ numero(dados.resumo.ordensCargaPendentes) }}</span>
        <span class="tv-carga__rot">ordens de carga a fazer</span>
      </div>
      <div class="tv-carga__total">
        <span class="tv-carga__val">{{ peso(dados.resumo.pesoPendenteKg) }}</span>
        <span class="tv-carga__rot">peso a separar</span>
      </div>
      @if (dados.resumo.pesoAguardandoLiberacaoKg > 0) {
        <div class="tv-carga__total tv-carga__total--lib">
          <span class="tv-carga__val">{{ peso(dados.resumo.pesoAguardandoLiberacaoKg) }}</span>
          <span class="tv-carga__rot">aguardando liberação</span>
        </div>
      }
    </section>

    <section class="tv-carga__lista">
      @for (o of visiveis(); track o.ordemCarga) {
        <article class="tv-carga__oc" [class.tv-carga__oc--sem]="o.ordemCarga == null">
          <div class="tv-carga__linha">
            <span class="tv-carga__num">{{ o.ordemCarga != null ? 'OC ' + o.ordemCarga : 'Sem OC' }}</span>
            <span class="tv-carga__peso">{{ peso(o.pesoPendenteKg) }}</span>
          </div>
          <span class="tv-carga__barra"><span [style.width.%]="pct(o)"></span></span>
          <div class="tv-carga__linha tv-carga__linha--sec">
            <span><strong>{{ o.pedidosProntos }}</strong>/{{ o.pedidos }} pedidos prontos</span>
            @if (o.pesoAguardandoLiberacaoKg > 0) {
              <span class="tv-carga__lib">{{ peso(o.pesoAguardandoLiberacaoKg) }} aguard. liberação</span>
            } @else if (o.emConferencia > 0) {
              <span class="tv-carga__conf">{{ o.emConferencia }} em conferência</span>
            }
          </div>
        </article>
      }
    </section>

    @if (totalPaginas() > 1) {
      <span class="tv-carga__pags">página {{ (pagina() % totalPaginas()) + 1 }} de {{ totalPaginas() }}</span>
    }
  `,
  styles: `
    :host {
      grid-row: 2 / -1;
      display: flex;
      flex-direction: column;
      gap: 2vh;
      min-height: 0;
    }
    .tv-carga__totais { display: flex; gap: calc(1 * var(--u)); }
    .tv-carga__total {
      flex: 1;
      display: flex;
      flex-direction: column;
      gap: 0.8vh;
      padding: 2vh calc(1.6 * var(--u));
      border-radius: var(--oq-radius-block);
      background: var(--oq-surface);
    }
    .tv-carga__total--lib {
      background: color-mix(in srgb, var(--oq-attention) 12%, var(--oq-surface));
      box-shadow: inset 0 0.3vh 0 var(--oq-attention);
      .tv-carga__val { color: var(--oq-warning-foreground); }
    }
    .tv-carga__val {
      font-family: var(--oq-font-mono);
      font-size: calc(5.6 * var(--u));
      font-weight: 700;
      line-height: 0.95;
      font-variant-numeric: tabular-nums;
    }
    .tv-carga__rot, .tv-carga__pags {
      font-family: var(--oq-font-display);
      font-size: calc(1 * var(--u));
      font-weight: 700;
      letter-spacing: 0.1em;
      text-transform: uppercase;
      color: var(--oq-text-secondary);
    }
    .tv-carga__pags { align-self: center; }
    .tv-carga__lista {
      flex: 1;
      min-height: 0;
      display: grid;
      grid-template-columns: repeat(2, minmax(0, 1fr));
      grid-auto-rows: min-content;
      gap: 1.4vh calc(1 * var(--u));
      overflow: hidden;
    }
    .tv-carga__oc {
      display: flex;
      flex-direction: column;
      gap: 1vh;
      padding: 1.6vh calc(1.4 * var(--u));
      border-radius: var(--oq-radius-block);
      background: var(--oq-surface);
    }
    .tv-carga__oc--sem .tv-carga__num { color: var(--oq-text-secondary); }
    .tv-carga__linha {
      display: flex;
      justify-content: space-between;
      align-items: baseline;
      gap: calc(1 * var(--u));
    }
    .tv-carga__num, .tv-carga__peso {
      font-family: var(--oq-font-mono);
      font-size: calc(2.4 * var(--u));
      font-weight: 700;
      font-variant-numeric: tabular-nums;
      white-space: nowrap;
    }
    .tv-carga__linha--sec {
      font-size: calc(1.2 * var(--u));
      color: var(--oq-text-secondary);
      strong { color: var(--oq-text-primary); }
    }
    .tv-carga__lib { font-weight: 700; color: var(--oq-warning-foreground); }
    .tv-carga__conf { font-weight: 600; color: var(--oq-brand-accent); }
    .tv-carga__barra {
      height: 1vh;
      border-radius: 99px;
      background: var(--oq-surface-2);
      overflow: hidden;
      span { display: block; height: 100%; background: var(--oq-concluido); transition: width 0.6s ease; }
    }
  `,
})
export class TvCargaComponent implements OnInit, OnDestroy {
  @Input({ required: true }) set dados(v: TvResumo) {
    this._dados = v;
    this.lista.set(v.ordensCarga ?? []);
  }
  get dados(): TvResumo {
    return this._dados;
  }
  private _dados!: TvResumo;

  private readonly lista = signal<TvOrdemCarga[]>([]);
  readonly pagina = signal(0);
  readonly totalPaginas = computed(() => Math.max(1, Math.ceil(this.lista().length / OC_POR_PAGINA)));
  readonly visiveis = computed(() => {
    const p = this.pagina() % this.totalPaginas();
    return this.lista().slice(p * OC_POR_PAGINA, (p + 1) * OC_POR_PAGINA);
  });
  private rotacao?: ReturnType<typeof setInterval>;

  ngOnInit(): void {
    this.rotacao = setInterval(() => this.pagina.update((p) => (p + 1) % this.totalPaginas()), INTERVALO_PAGINA_MS);
  }

  ngOnDestroy(): void {
    if (this.rotacao) clearInterval(this.rotacao);
  }

  pct(o: TvOrdemCarga): number {
    return o.pedidos ? (o.pedidosProntos / o.pedidos) * 100 : 0;
  }

  numero(n: number): string {
    return String(n).padStart(2, '0');
  }

  /** Até 999 kg em kg inteiros ("845 kg"); daí pra cima em toneladas com 1 casa ("12,3 t"). */
  peso(kg: number): string {
    if (kg < 1000) return Math.round(kg).toLocaleString('pt-BR') + ' kg';
    return (kg / 1000).toLocaleString('pt-BR', { minimumFractionDigits: 1, maximumFractionDigits: 1 }) + ' t';
  }
}
