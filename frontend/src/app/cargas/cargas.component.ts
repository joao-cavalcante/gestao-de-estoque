import { DatePipe } from '@angular/common';
import {
  AfterViewInit,
  Component,
  ElementRef,
  NgZone,
  OnDestroy,
  OnInit,
  ViewChild,
  computed,
  effect,
  inject,
  signal,
} from '@angular/core';
import { Subscription } from 'rxjs';
import { SyncTickService } from '../shared/app-header/sync-tick.service';
import { OqIconComponent } from '../shared/icons/oq-icon.component';
import { OqSkeletonComponent } from '../shared/oq-skeleton/oq-skeleton.component';
import { echarts, lerCores, nomeProprio, opcaoAndamento, opcaoSaidas } from './cargas-graficos';
import { Carga, CargasService, PedidoCarga, ROTULO_PEDIDO, pesoTexto, rotuloImpressao } from './cargas.service';

type Periodo = 'todos' | 'D' | 'N';

interface Ocorrencia {
  texto: string;
  ocs: number[];
  grave: boolean;
}

function plural(n: number, um: string, varios: string): string {
  return `${n} ${n === 1 ? um : varios}`;
}

/**
 * Cargas (24/11/2026) — painel de acompanhamento da expedição para quem não opera (pedido do usuário: dashboard fácil
 * de entender e completo; referências de painéis de doca/pátio e de expedição). Só leitura, numa tela só:
 *  - faixa de indicadores (caminhões do dia, saíram, prontos, carregando, peso expedido, ocorrências);
 *  - "Andamento dos caminhões": barra empilhada por OC ainda no galpão, pedidos por etapa (ECharts);
 *  - ocorrências em frases, saídas acumuladas ao longo do dia × programados, últimas saídas;
 *  - clique num caminhão abre os pedidos dele.
 * Saiu = Fechar OC (DECISÃO DE PRODUTO, 24/11/2026). Filtro por período de entrega (Diurno/Noturno).
 */
@Component({
  selector: 'app-cargas',
  standalone: true,
  imports: [DatePipe, OqIconComponent, OqSkeletonComponent],
  templateUrl: './cargas.component.html',
  styleUrl: './cargas.component.scss',
})
export class CargasComponent implements OnInit, AfterViewInit, OnDestroy {
  private readonly service = inject(CargasService);
  private readonly syncTick = inject(SyncTickService);
  private readonly zone = inject(NgZone);
  private readonly host: ElementRef<HTMLElement> = inject(ElementRef);
  private syncSub?: Subscription;
  private carregada = false;

  @ViewChild('grafAndamento') private grafAndamentoEl?: ElementRef<HTMLDivElement>;
  @ViewChild('grafSaidas') private grafSaidasEl?: ElementRef<HTMLDivElement>;
  private grafAndamento?: echarts.ECharts;
  private grafSaidas?: echarts.ECharts;
  private redim?: ResizeObserver;
  private temaObs?: MutationObserver;

  readonly pesoTexto = pesoTexto;
  readonly rotuloImpressao = rotuloImpressao;
  readonly nome = nomeProprio;
  readonly hoje = new Date();

  private readonly todas = signal<Carga[]>([]);
  readonly atualizadoEm = signal<Date | null>(null);
  readonly carregando = signal(true);
  readonly erro = signal<string | null>(null);
  readonly periodo = signal<Periodo>('todos');
  readonly detalhe = signal<Carga | null>(null);

  readonly cargas = computed(() => {
    const p = this.periodo();
    return p === 'todos' ? this.todas() : this.todas().filter((c) => (c.turnos ?? []).includes(p));
  });

  /** Caminhões ainda no galpão: prontos primeiro (esperando o Fechar OC), depois os mais atrasados no carregamento. */
  readonly noGalpao = computed(() =>
    this.cargas()
      .filter((c) => c.situacao !== 'saiu')
      .sort((a, b) =>
        a.situacao === b.situacao
          ? a.prontos / Math.max(1, a.pedidos.length) - b.prontos / Math.max(1, b.pedidos.length)
          : a.situacao === 'pronta' ? -1 : 1,
      ),
  );
  readonly saidas = computed(() => this.cargas().filter((c) => c.situacao === 'saiu'));
  readonly prontas = computed(() => this.cargas().filter((c) => c.situacao === 'pronta'));
  readonly carregandoOcs = computed(() => this.cargas().filter((c) => c.situacao === 'pendente'));
  readonly total = computed(() => this.cargas().length);
  readonly pctSaiu = computed(() => (this.total() ? Math.round((this.saidas().length * 100) / this.total()) : 0));
  readonly pesoTotal = computed(() => this.cargas().reduce((s, c) => s + c.pesoKg, 0));
  readonly pesoSaiu = computed(() => this.saidas().reduce((s, c) => s + c.pesoKg, 0));
  readonly pedidosFaltando = computed(() => this.carregandoOcs().reduce((s, c) => s + c.pedidos.length - c.prontos, 0));
  readonly porTurno = computed(() => ({
    D: this.todas().filter((c) => (c.turnos ?? []).includes('D')).length,
    N: this.todas().filter((c) => (c.turnos ?? []).includes('N')).length,
  }));

  readonly ocorrencias = computed<Ocorrencia[]>(() => {
    const abertas = this.cargas().filter((c) => c.situacao !== 'saiu');
    const r: Ocorrencia[] = [];
    const add = (lista: Carga[], texto: string, grave: boolean) => {
      if (lista.length) r.push({ texto, ocs: lista.map((c) => c.ordemCarga), grave });
    };
    const cortes = abertas.filter((c) => c.faltas.corte > 0);
    const nCortes = cortes.reduce((s, c) => s + c.faltas.corte, 0);
    add(cortes, `${plural(nCortes, 'pedido parado', 'pedidos parados')} esperando liberação de corte`, true);
    const atrasadas = abertas.filter((c) => this.atrasada(c));
    add(atrasadas, plural(atrasadas.length, 'caminhão com saída atrasada', 'caminhões com saída atrasada'), true);
    const pesados = this.cargas().filter((c) => (this.ocupacao(c) ?? 0) > 100);
    add(pesados, `${plural(pesados.length, 'caminhão acima', 'caminhões acima')} da capacidade de peso`, true);
    const notas = this.saidas().filter((c) => c.notas.problema > 0);
    add(notas, `${plural(notas.length, 'caminhão saiu', 'caminhões saíram')} com nota recusada pela SEFAZ`, true);
    const imp = this.saidas().filter((c) => c.impressao?.status === 'erro');
    add(imp, `Erro na impressão das notas (${plural(imp.length, 'caminhão', 'caminhões')})`, false);
    const semMot = abertas.filter((c) => !c.motorista);
    add(semMot, `${plural(semMot.length, 'caminhão', 'caminhões')} sem motorista definido`, false);
    return r;
  });

  readonly ultimasSaidas = computed(() => [...this.saidas()].sort((a, b) => (b.saiuEm ?? '').localeCompare(a.saiuEm ?? '')));

  constructor() {
    // Redesenha os gráficos quando os dados ou o filtro mudam.
    effect(() => this.desenhar(this.cargas(), this.noGalpao()));
  }

  ngOnInit(): void {
    this.carregar();
    this.syncSub = this.syncTick.onTick.subscribe(() => this.carregar());
  }

  ngAfterViewInit(): void {
    this.temaObs = new MutationObserver(() => this.desenhar(this.cargas(), this.noGalpao()));
    this.temaObs.observe(document.documentElement, { attributes: true, attributeFilter: ['data-theme'] });
  }

  ngOnDestroy(): void {
    this.syncSub?.unsubscribe();
    this.redim?.disconnect();
    this.temaObs?.disconnect();
    this.grafAndamento?.dispose();
    this.grafSaidas?.dispose();
  }

  carregar(): void {
    if (!this.carregada) this.carregando.set(true);
    this.service.painel('hoje').subscribe({
      next: (p) => {
        this.todas.set(p.cargas);
        this.atualizadoEm.set(new Date(p.atualizadoEm));
        this.carregada = true;
        this.erro.set(null);
        this.carregando.set(false);
        const d = this.detalhe();
        if (d) this.detalhe.set(p.cargas.find((c) => c.ordemCarga === d.ordemCarga) ?? null);
        // Os contêineres dos gráficos só existem depois do 1º carregamento.
        setTimeout(() => this.desenhar(this.cargas(), this.noGalpao()));
      },
      error: (e) => {
        if (!this.carregada) this.erro.set(e?.error?.erro ?? 'Não foi possível carregar as cargas.');
        this.carregando.set(false);
      },
    });
  }

  private desenhar(cargas: Carga[], galpao: Carga[]): void {
    const elA = this.grafAndamentoEl?.nativeElement;
    const elS = this.grafSaidasEl?.nativeElement;
    if (!elA || !elS) return;
    this.zone.runOutsideAngular(() => {
      if (!this.grafAndamento || this.grafAndamento.getDom() !== elA) {
        this.grafAndamento?.dispose();
        this.grafAndamento = echarts.init(elA);
        this.grafAndamento.on('click', (ev: any) => {
          const lista = [...this.noGalpao()].reverse(); // mesma ordem do eixo (1ª categoria embaixo)
          const c = lista[ev.dataIndex];
          if (c) this.zone.run(() => this.detalhe.set(c));
        });
      }
      if (!this.grafSaidas || this.grafSaidas.getDom() !== elS) {
        this.grafSaidas?.dispose();
        this.grafSaidas = echarts.init(elS);
      }
      this.redim ??= new ResizeObserver(() => {
        this.grafAndamento?.resize();
        this.grafSaidas?.resize();
      });
      this.redim.disconnect();
      this.redim.observe(elA);
      this.redim.observe(elS);
      const cores = lerCores(this.host.nativeElement);
      this.grafAndamento.setOption(opcaoAndamento(galpao, cores), true);
      this.grafSaidas.setOption(opcaoSaidas(cargas, cores), true);
    });
  }

  fechar(): void {
    this.detalhe.set(null);
  }

  abrirOc(oc: number): void {
    const c = this.todas().find((x) => x.ordemCarga === oc);
    if (c) this.detalhe.set(c);
  }

  ocupacao(c: Carga): number | null {
    return c.pesoMaxKg ? Math.round((c.pesoKg * 100) / c.pesoMaxKg) : null;
  }

  private dataLocal(iso: string | null): Date | null {
    const m = /^(\d{4})-(\d{2})-(\d{2})/.exec(iso ?? '');
    return m ? new Date(Number(m[1]), Number(m[2]) - 1, Number(m[3])) : null;
  }

  atrasada(c: Carga): boolean {
    const d = this.dataLocal(c.saidaPrevista);
    if (!d || c.situacao === 'saiu') return false;
    return d.getTime() < new Date(this.hoje.getFullYear(), this.hoje.getMonth(), this.hoje.getDate()).getTime();
  }

  textoNotas(c: Carga): string {
    const n = c.notas;
    if (!n.total) return 'notas emitidas';
    if (n.problema) return plural(n.problema, 'nota recusada', 'notas recusadas');
    if (n.aguardando) return plural(n.aguardando, 'nota na SEFAZ', 'notas na SEFAZ');
    return n.total === 1 ? 'nota ok' : `${n.total} notas ok`;
  }

  situacaoTexto(c: Carga): string {
    if (c.situacao === 'saiu') return 'Saiu para entrega';
    if (c.situacao === 'pronta') return 'Pronto — falta fechar a OC';
    const f = c.faltas;
    const p: string[] = [];
    if (f.corte) p.push(`${f.corte} no corte`);
    if (f.aConferir) p.push(`${f.aConferir} a conferir`);
    if (f.emConferencia) p.push(`${f.emConferencia} conferindo`);
    if (f.aCarregar) p.push(`${f.aCarregar} a carregar`);
    return `Carregando — falta ${p.join(', ')}`;
  }

  rotuloPedido(p: PedidoCarga): string {
    if (p.situacao === 'com_nota' && p.notaNumero) return `NF ${p.notaNumero} · ${p.notaSituacao ?? ''}`.trim();
    return ROTULO_PEDIDO[p.situacao];
  }
}
