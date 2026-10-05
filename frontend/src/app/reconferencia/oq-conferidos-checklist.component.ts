import { Component, Input, OnChanges, computed, inject, signal } from '@angular/core';
import { OqIconComponent, OqIconName } from '../shared/icons/oq-icon.component';
import { ReconferenciaDetalhe, ReconferenciaItem, ReconferenciaService } from './reconferencia.service';

interface Grupo {
  tipo: number;
  rotulo: string;
  icone: OqIconName | null;
  itens: ReconferenciaItem[];
}

const ETAPAS: Record<number, { rotulo: string; icone: OqIconName }> = {
  1: { rotulo: 'Secos', icone: 'seco' },
  2: { rotulo: 'Refrigerado', icone: 'refrigerado' },
  3: { rotulo: 'Congelado', icone: 'congelado' },
};

/**
 * Lista do que foi conferido numa sessão, por etapa, com CHECK de reconferência item a item (salvo no
 * servidor). Usada no pop-up "Ver conferidos" (conferência em andamento) e na tela Reconferência
 * (finalizadas). Pesável com selo no estilo da etiqueta de peso; divergente com alerta (pesável dentro
 * da tolerância da sessão não acusa).
 */
@Component({
  selector: 'oq-conferidos-checklist',
  standalone: true,
  imports: [OqIconComponent],
  template: `
    <div class="ck-topo">
      <span class="ck-prog">
        <strong>{{ checados() }}</strong> de {{ itens().length }} checados
      </span>
      <span class="ck-barra"><span class="ck-barra__fill" [style.width.%]="pct()"></span></span>
      @if (divergentesTotal() > 0) { <span class="ck-alerta">⚠ {{ divergentesTotal() }} divergente(s)</span> }
    </div>
    @if (erro()) { <span class="oq-form-hint oq-form-hint--erro">{{ erro() }}</span> }

    @for (g of grupos(); track g.tipo) {
      <section class="ck-etapa">
        <div class="ck-etapa__topo">
          @if (g.icone) { <oq-icon [name]="g.icone" [size]="15" /> }
          <strong>{{ g.rotulo }}</strong>
          @if (divergentesNo(g.itens); as nd) { <span class="ck-alerta">⚠ {{ nd }}</span> }
          <span class="ck-etapa__qtd">{{ checadosNo(g.itens) }}/{{ g.itens.length }}</span>
        </div>
        <ul class="ck-lista">
          @for (i of g.itens; track i.codprod + '|' + i.controle) {
            <li class="ck-item" [class.ck-item--ok]="i.checado" [class.ck-item--div]="motivo(i) && !i.checado">
              <label class="ck-check" [title]="i.checado ? 'Checado' + (i.checadoPor ? ' por ' + i.checadoPor : '') : 'Marcar como reconferido'">
                <input type="checkbox" [checked]="i.checado" [disabled]="salvando().has(chave(i))" (change)="alternar(i)" />
              </label>
              <span class="ck-cod">{{ i.codprod }}</span>
              <span class="ck-nome">{{ i.descricao }}{{ i.controle ? ' · ' + i.controle : '' }}</span>
              @if (motivo(i); as m) { <span class="ck-div">⚠ {{ m }}</span> }
              @if (i.pesavel) {
                @if (pedidoComercial(i); as pc) {
                  <span class="ck-qtd ck-qtd--pedido" title="Quantidade no pedido">{{ pc }}</span>
                }
                <span class="ck-peso" title="Pesável — peso conferido">
                  <oq-icon name="balanca" [size]="13" />
                  <strong>{{ fmt(i.qtdConferida, 3) }}</strong> {{ i.unidade ?? 'KG' }} <small>de {{ fmt(i.qtdPedido, 3) }}</small>
                </span>
              } @else if (usaUnidadePedido(i)) {
                <!-- Não pesável vendido em outra unidade (ex.: CX): mostra na unidade do pedido, base de referência. -->
                <span class="ck-qtd" title="Conferido / pedido na unidade do pedido">
                  {{ fmt(i.qtdConferidaComercial!, 0) }} / {{ fmt(i.qtdPedidoComercial!, 0) }} {{ i.unidadeComercial }}
                  <small class="ck-base">Base: {{ fmt(i.qtdConferida, 0) }} / {{ fmt(i.qtdPedido, 0) }} {{ i.unidade }}</small>
                </span>
              } @else {
                <span class="ck-qtd">{{ fmt(i.qtdConferida, 0) }} / {{ fmt(i.qtdPedido, 0) }} {{ i.unidade }}</span>
              }
            </li>
          }
        </ul>
      </section>
    } @empty {
      <div class="oq-empty-row">Nada conferido ainda nesta nota.</div>
    }
  `,
  styles: [
    `
      :host { display: flex; flex-direction: column; gap: 12px; }
      .ck-topo { display: flex; align-items: center; gap: 10px; }
      .ck-prog { font-family: var(--oq-font-mono); font-size: 12px; white-space: nowrap; }
      .ck-barra { flex: 1; height: 6px; border-radius: 99px; background: var(--oq-border); overflow: hidden; }
      .ck-barra__fill { display: block; height: 100%; background: var(--oq-concluido); transition: width .3s ease; }
      .ck-alerta { padding: 1px 6px; border-radius: var(--oq-radius-input); background: var(--oq-critical-soft); color: var(--oq-critical-foreground);
        font-family: var(--oq-font-display); font-size: 10px; font-weight: 700; white-space: nowrap; }
      .ck-etapa__topo { display: flex; align-items: center; gap: 8px; padding-bottom: 6px; border-bottom: 1px solid var(--oq-border);
        font-family: var(--oq-font-display); font-size: 12px; text-transform: uppercase; letter-spacing: .06em; }
      .ck-etapa__qtd { margin-left: auto; font-family: var(--oq-font-mono); font-size: 11px; color: var(--oq-text-secondary); }
      .ck-lista { list-style: none; margin: 4px 0 0; padding: 0; }
      .ck-item { display: flex; align-items: center; gap: 10px; padding: 7px 4px; border-bottom: 1px solid var(--oq-border); font-size: 12px; }
      .ck-item--ok { background: color-mix(in srgb, var(--oq-concluido) 8%, transparent); }
      .ck-item--ok .ck-nome { color: var(--oq-text-secondary); }
      .ck-item--div { background: var(--oq-critical-soft); box-shadow: inset 3px 0 0 var(--oq-critical); }
      .ck-check { flex: none; display: grid; place-items: center; cursor: pointer; }
      .ck-check input { width: 20px; height: 20px; margin: 0; accent-color: var(--oq-concluido); cursor: pointer; }
      .ck-cod { flex: none; width: 52px; font-family: var(--oq-font-mono); color: var(--oq-text-secondary); }
      .ck-nome { flex: 1; min-width: 0; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
      .ck-qtd { flex: none; font-family: var(--oq-font-mono); font-weight: 600; white-space: nowrap; }
      .ck-qtd--pedido { font-weight: 700; }
      .ck-base { display: block; font-weight: 400; font-size: 0.8em; opacity: 0.7; text-align: right; }
      .ck-div { flex: none; padding: 1px 6px; border-radius: var(--oq-radius-input); background: var(--oq-critical); color: #fff;
        font-family: var(--oq-font-display); font-size: 10px; font-weight: 700; }
      .ck-peso { flex: none; display: inline-flex; align-items: baseline; gap: 4px; padding: 2px 8px; border: 1px dashed var(--oq-text-primary);
        border-radius: var(--oq-radius-input); font-family: var(--oq-font-mono); font-size: 11px; white-space: nowrap; }
      .ck-peso oq-icon { align-self: center; }
      .ck-peso strong { font-size: 13px; }
      .ck-peso small { color: var(--oq-text-secondary); }
    `,
  ],
})
export class OqConferidosChecklistComponent implements OnChanges {
  private readonly service = inject(ReconferenciaService);
  @Input({ required: true }) detalhe!: ReconferenciaDetalhe;

  readonly itens = signal<ReconferenciaItem[]>([]);
  readonly salvando = signal<ReadonlySet<string>>(new Set());
  readonly erro = signal<string | null>(null);

  ngOnChanges(): void {
    this.itens.set(this.detalhe?.itens ?? []);
  }

  readonly grupos = computed<Grupo[]>(() =>
    [1, 2, 3, 0]
      .map((tipo) => ({
        tipo,
        rotulo: ETAPAS[tipo]?.rotulo ?? 'Sem etapa',
        icone: ETAPAS[tipo]?.icone ?? null,
        itens: this.itens().filter((i) => (ETAPAS[i.tipoSeparacao] ? i.tipoSeparacao : 0) === tipo),
      }))
      .filter((g) => g.itens.length > 0),
  );
  readonly checados = computed(() => this.itens().filter((i) => i.checado).length);
  readonly pct = computed(() => (this.itens().length ? (this.checados() / this.itens().length) * 100 : 0));
  readonly divergentesTotal = computed(() => this.itens().filter((i) => this.motivo(i)).length);

  chave(i: ReconferenciaItem): string {
    return `${i.codprod}|${i.controle}`;
  }

  checadosNo(itens: ReconferenciaItem[]): number {
    return itens.filter((i) => i.checado).length;
  }

  divergentesNo(itens: ReconferenciaItem[]): number {
    return itens.filter((i) => this.motivo(i)).length;
  }

  /** Divergência do item (mesma ideia da finalização): pesável respeita a tolerância da sessão. */
  motivo(i: ReconferenciaItem): string | null {
    const ped = Number(i.qtdPedido);
    const conf = Number(i.qtdConferida);
    const r3 = (n: number) => Math.round(n * 1000) / 1000;
    if (r3(conf) === r3(ped)) return null;
    const aMaior = conf > ped;
    if (i.pesavel) {
      const tol = aMaior ? this.detalhe.tolPesoAcimaPct : this.detalhe.tolPesoAbaixoPct;
      if (tol == null || ped <= 0) return null;
      if (Math.abs(conf - ped) / ped <= tol / 100) return null;
      return aMaior ? 'PESO A MAIOR' : 'PESO A MENOR';
    }
    return aMaior ? 'SOBRA' : 'FALTA';
  }

  /**
   * Pesável vendido em unidade comercial (ex.: CX): "3 CX" do pedido ao lado do peso. Sem unidade
   * comercial diferente da base (vendido em KG), não mostra — o peso já diz tudo.
   */
  pedidoComercial(i: ReconferenciaItem): string | null {
    if (!i.unidadeComercial || !i.qtdPedidoComercial || i.unidadeComercial === i.unidade) return null;
    return `${this.fmt(i.qtdPedidoComercial, 0)} ${i.unidadeComercial}`;
  }

  /** Item NÃO pesável negociado numa unidade diferente da base (ex.: CX com base KG) — mesma regra da Conferência. */
  usaUnidadePedido(i: ReconferenciaItem): boolean {
    return !!i.unidadeComercial && i.unidadeComercial !== i.unidade && !!i.qtdPedidoComercial && !!i.qtdConferidaComercial;
  }

  fmt(v: string, casas: number): string {
    return Number(v).toLocaleString('pt-BR', { minimumFractionDigits: casas, maximumFractionDigits: 3 });
  }

  /** Otimista: marca na hora e desfaz se o servidor recusar. */
  alternar(i: ReconferenciaItem): void {
    const chave = this.chave(i);
    const novo = !i.checado;
    this.erro.set(null);
    this.itens.update((l) => l.map((x) => (this.chave(x) === chave ? { ...x, checado: novo } : x)));
    this.salvando.update((s) => new Set([...s, chave]));
    const fim = () => this.salvando.update((s) => new Set([...s].filter((k) => k !== chave)));
    this.service.marcar(this.detalhe.sessaoId, i.codprod, i.controle, novo).subscribe({
      next: fim,
      error: () => {
        fim();
        this.itens.update((l) => l.map((x) => (this.chave(x) === chave ? { ...x, checado: !novo } : x)));
        this.erro.set('Não foi possível salvar o check — tente de novo.');
      },
    });
  }
}
