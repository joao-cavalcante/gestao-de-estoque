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
import {
  ALTURA_LINHA,
  duracaoTexto,
  echarts,
  lerCores,
  minutosDesde,
  nomeProprio,
  opcaoAndamento,
  opcaoSaidas,
  placaTexto,
} from './cargas-graficos';
import { Carga, CargasService, PedidoCarga, ROTULO_PEDIDO, pesoTexto, rotuloImpressao } from './cargas.service';

type Periodo = 'todos' | 'D' | 'N';

interface Ocorrencia {
  texto: string;
  ocs: number[];
  grave: boolean;
}

interface Janela {
  inicio: Date;
  fim: Date;
  horas: number;
  titulo: string;
}

/** Caminhão pronto parado acima disso vira ocorrência. */
const MIN_PRONTO_PARADO = 30;
/** Sync com o Sankhya sem sucesso há mais disso: aviso de dados velhos. */
const MIN_SYNC_ATRASADO = 5;

function plural(n: number, um: string, varios: string): string {
  return `${n} ${n === 1 ? um : varios}`;
}

function inicioDoDia(d: Date): Date {
  return new Date(d.getFullYear(), d.getMonth(), d.getDate());
}

/**
 * Cargas (24/11/2026) — painel de acompanhamento da expedição para quem não opera (aprovado pelo usuário: "era disso
 * que eu tava falando"). Só leitura, numa tela só:
 *  - faixa de indicadores do dia (ou da noite): saíram X de Y, prontos, carregando, peso expedido, caminhões com
 *    ocorrência;
 *  - andamento dos caminhões no galpão (ECharts, barra empilhada por etapa; OC + placa + região à esquerda, o que
 *    falta / há quanto tempo está pronto à direita);
 *  - ocorrências com as OCs, saídas acumuladas × programados na janela, últimas saídas;
 *  - detalhe do caminhão em painel lateral.
 * Revisão crítica de 24/11: total do dia sem as OCs de amanhã; noturno = 22h–07h; atraso em dias (o Sankhya só tem a
 * data); ocorrências contadas por caminhão; "pronto há X min"; região e placa; OC fechada direto no Sankhya; aviso de
 * dado velho. Saiu = Fechar OC (DECISÃO DE PRODUTO).
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
  private relogio?: ReturnType<typeof setInterval>;
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
  readonly placa = placaTexto;
  readonly duracao = duracaoTexto;
  readonly alturaLinha = ALTURA_LINHA;
  readonly plural = plural;

  private readonly todas = signal<Carga[]>([]);
  readonly agora = signal(new Date());
  readonly atualizadoEm = signal<Date | null>(null);
  readonly sincronizadoEm = signal<Date | null>(null);
  readonly falhaDesde = signal<Date | null>(null);
  readonly carregando = signal(true);
  readonly erro = signal<string | null>(null);
  readonly periodo = signal<Periodo>('todos');
  readonly detalhe = signal<Carga | null>(null);

  /** Janela das saídas: o dia de hoje, ou a noite (22h–07h) em andamento / que acabou de terminar. */
  readonly janela = computed<Janela>(() => {
    const a = this.agora();
    if (this.periodo() === 'N') {
      const ini = inicioDoDia(a);
      if (a.getHours() < 22) ini.setDate(ini.getDate() - 1);
      ini.setHours(22);
      const fim = new Date(ini.getTime() + 9 * 3_600_000);
      const fmt = (d: Date) => d.toLocaleDateString('pt-BR', { day: '2-digit', month: '2-digit' });
      return { inicio: ini, fim, horas: 9, titulo: `Noite de ${fmt(ini)} para ${fmt(fim)} · 22h às 7h` };
    }
    const ini = inicioDoDia(a);
    const fim = new Date(ini.getTime() + 24 * 3_600_000);
    const inicioGrafico = new Date(ini.getTime() + 5 * 3_600_000);
    return { inicio: inicioGrafico, fim, horas: 18, titulo: a.toLocaleDateString('pt-BR', { weekday: 'long', day: 'numeric', month: 'long' }) };
  });

  private readonly doPeriodo = computed(() => {
    const p = this.periodo();
    return p === 'todos' ? this.todas() : this.todas().filter((c) => (c.turnos ?? []).includes(p));
  });

  /** Saíram dentro da janela (dia ou noite). */
  readonly saidas = computed(() => {
    const j = this.janela();
    const ini = this.periodo() === 'N' ? j.inicio : inicioDoDia(this.agora());
    return this.doPeriodo()
      .filter((c) => c.situacao === 'saiu' && c.saiuEm)
      .filter((c) => {
        const t = new Date(c.saiuEm!).getTime();
        return t >= ini.getTime() && t < j.fim.getTime();
      })
      .sort((a, b) => (b.saiuEm ?? '').localeCompare(a.saiuEm ?? ''));
  });

  private readonly abertas = computed(() => this.doPeriodo().filter((c) => c.situacao !== 'saiu'));
  /** Abertas para os próximos dias — aparecem no gráfico, mas fora do total do dia. */
  readonly futuras = computed(() => this.abertas().filter((c) => this.diasParaSair(c) > 0));
  private readonly doDia = computed(() => this.abertas().filter((c) => this.diasParaSair(c) <= 0));
  readonly prontas = computed(() =>
    this.doDia()
      .filter((c) => c.situacao === 'pronta')
      .sort((a, b) => (a.prontaDesde ?? '').localeCompare(b.prontaDesde ?? '')),
  );
  readonly carregandoOcs = computed(() =>
    this.doDia()
      .filter((c) => c.situacao === 'pendente')
      .sort((a, b) => a.prontos / Math.max(1, a.pedidos.length) - b.prontos / Math.max(1, b.pedidos.length)),
  );
  /** Ordem do gráfico: prontos (mais tempo parado primeiro), carregando (menos adiantado primeiro), próximos dias. */
  readonly noGalpao = computed(() => [...this.prontas(), ...this.carregandoOcs(), ...this.futuras()]);
  readonly total = computed(() => this.doDia().length + this.saidas().length);
  readonly pctSaiu = computed(() => (this.total() ? Math.round((this.saidas().length * 100) / this.total()) : 0));
  readonly pesoTotal = computed(() => [...this.doDia(), ...this.saidas()].reduce((s, c) => s + c.pesoKg, 0));
  readonly pesoSaiu = computed(() => this.saidas().reduce((s, c) => s + c.pesoKg, 0));
  readonly pedidosFaltando = computed(() => this.carregandoOcs().reduce((s, c) => s + c.pedidos.length - c.prontos, 0));
  readonly porTurno = computed(() => ({
    D: this.todas().filter((c) => (c.turnos ?? []).includes('D')).length,
    N: this.todas().filter((c) => (c.turnos ?? []).includes('N')).length,
  }));
  readonly alturaGrafico = computed(() => Math.max(1, this.noGalpao().length) * ALTURA_LINHA + 56);

  readonly ocorrencias = computed<Ocorrencia[]>(() => {
    const dia = this.doDia();
    const r: Ocorrencia[] = [];
    const add = (lista: Carga[], texto: string, grave: boolean) => {
      if (lista.length) r.push({ texto, ocs: lista.map((c) => c.ordemCarga), grave });
    };
    const parados = this.prontas().filter((c) => (this.minutosPronta(c) ?? 0) >= MIN_PRONTO_PARADO);
    add(parados, `${plural(parados.length, 'caminhão pronto parado', 'caminhões prontos parados')} há mais de ${MIN_PRONTO_PARADO} min`, true);
    const cortes = dia.filter((c) => c.faltas.corte > 0);
    const nCortes = cortes.reduce((s, c) => s + c.faltas.corte, 0);
    add(cortes, `${plural(nCortes, 'pedido parado', 'pedidos parados')} esperando aprovação de falta (corte)`, true);
    const atrasadas = dia.filter((c) => this.diasParaSair(c) < 0);
    add(atrasadas, `${plural(atrasadas.length, 'caminhão de dia anterior', 'caminhões de dias anteriores')} ainda no galpão`, true);
    const notas = this.saidas().filter((c) => c.notas.problema > 0);
    add(notas, `${plural(notas.length, 'caminhão saiu', 'caminhões saíram')} com nota recusada pela SEFAZ`, true);
    const pesados = [...dia, ...this.saidas()].filter((c) => (this.ocupacao(c) ?? 0) > 100);
    add(pesados, `${plural(pesados.length, 'caminhão pode passar', 'caminhões podem passar')} da capacidade (peso dos pedidos, antes de cortes)`, false);
    const imp = this.saidas().filter((c) => c.impressao?.status === 'erro');
    add(imp, `Erro na impressão das notas (${plural(imp.length, 'caminhão', 'caminhões')})`, false);
    const semMot = dia.filter((c) => !c.motorista);
    add(semMot, `${plural(semMot.length, 'caminhão', 'caminhões')} sem motorista definido`, false);
    return r;
  });

  /** Caminhões distintos com alguma ocorrência (é o número da faixa de indicadores). */
  readonly caminhoesComOcorrencia = computed(() => new Set(this.ocorrencias().flatMap((o) => o.ocs)).size);

  /** Minutos desde o último sync com o Sankhya que deu certo (null = não informado). */
  readonly minutosSemSync = computed(() => {
    const s = this.sincronizadoEm();
    return s ? Math.floor((this.agora().getTime() - s.getTime()) / 60_000) : null;
  });
  readonly dadosVelhos = computed(() => (this.minutosSemSync() ?? 0) >= MIN_SYNC_ATRASADO || !!this.falhaDesde());

  constructor() {
    // Redesenha os gráficos quando os dados, o filtro ou o relógio mudam.
    effect(() => this.desenhar(this.noGalpao(), this.saidas(), this.total(), this.janela()));
  }

  ngOnInit(): void {
    this.carregar();
    this.syncSub = this.syncTick.onTick.subscribe(() => this.carregar());
    this.relogio = setInterval(() => this.agora.set(new Date()), 60_000);
  }

  ngAfterViewInit(): void {
    this.temaObs = new MutationObserver(() => this.desenhar(this.noGalpao(), this.saidas(), this.total(), this.janela()));
    this.temaObs.observe(document.documentElement, { attributes: true, attributeFilter: ['data-theme'] });
  }

  ngOnDestroy(): void {
    this.syncSub?.unsubscribe();
    if (this.relogio) clearInterval(this.relogio);
    this.redim?.disconnect();
    this.temaObs?.disconnect();
    this.grafAndamento?.dispose();
    this.grafSaidas?.dispose();
  }

  carregar(): void {
    if (!this.carregada) this.carregando.set(true);
    this.service.painel().subscribe({
      next: (p) => {
        this.todas.set(p.cargas);
        this.atualizadoEm.set(new Date(p.atualizadoEm));
        this.sincronizadoEm.set(p.sincronizadoEm ? new Date(p.sincronizadoEm) : null);
        this.agora.set(new Date());
        this.falhaDesde.set(null);
        this.carregada = true;
        this.erro.set(null);
        this.carregando.set(false);
        const d = this.detalhe();
        if (d) this.detalhe.set(p.cargas.find((c) => c.ordemCarga === d.ordemCarga) ?? null);
        // Os contêineres dos gráficos só existem depois do 1º carregamento.
        setTimeout(() => this.desenhar(this.noGalpao(), this.saidas(), this.total(), this.janela()));
      },
      error: (e) => {
        // Com dados na tela, a falha não apaga nada — só avisa desde quando está sem atualizar.
        if (!this.carregada) this.erro.set(e?.error?.erro ?? 'Não foi possível carregar as cargas.');
        else if (!this.falhaDesde()) this.falhaDesde.set(new Date());
        this.carregando.set(false);
      },
    });
  }

  private desenhar(galpao: Carga[], saidas: Carga[], total: number, j: Janela): void {
    const elA = this.grafAndamentoEl?.nativeElement;
    const elS = this.grafSaidasEl?.nativeElement;
    if (!elA || !elS) return;
    this.zone.runOutsideAngular(() => {
      if (!this.grafAndamento || this.grafAndamento.getDom() !== elA) {
        this.grafAndamento?.dispose();
        this.grafAndamento = echarts.init(elA);
        this.grafAndamento.on('click', (ev: any) => {
          const c = [...this.noGalpao()].reverse()[ev.dataIndex]; // mesma ordem do eixo (1ª categoria embaixo)
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
      this.grafAndamento.setOption(opcaoAndamento(galpao, cores, (c) => this.ladoDireito(c)), true);
      this.grafAndamento.resize();
      this.grafSaidas.setOption(opcaoSaidas(saidas, total, j.inicio, j.horas, cores, this.agora()), true);
    });
  }

  /** Texto à direita da barra. */
  private ladoDireito(c: Carga): { texto: string; tom: 'ok' | 'falta' | 'alerta' | 'futuro' } {
    const dias = this.diasParaSair(c);
    if (dias > 0) return { texto: `sai ${this.dataCurta(c.saidaPrevista)}`, tom: 'futuro' };
    if (c.situacao === 'pronta') {
      const m = this.minutosPronta(c);
      if (m == null) return { texto: '✓ pronto', tom: 'ok' };
      return { texto: `✓ pronto há ${duracaoTexto(m)}`, tom: m >= MIN_PRONTO_PARADO ? 'alerta' : 'ok' };
    }
    const n = c.pedidos.length - c.prontos;
    const txt = `faltam ${n}`;
    return dias < 0 ? { texto: `${txt} · ${this.atrasoTexto(c)}`, tom: 'alerta' } : { texto: txt, tom: 'falta' };
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

  minutosPronta(c: Carga): number | null {
    return minutosDesde(c.prontaDesde, this.agora().getTime());
  }

  private dataLocal(iso: string | null | undefined): Date | null {
    const m = /^(\d{4})-(\d{2})-(\d{2})/.exec(iso ?? '');
    return m ? new Date(Number(m[1]), Number(m[2]) - 1, Number(m[3])) : null;
  }

  /** Dias entre hoje e a saída prevista (0 = hoje, 1 = amanhã, -1 = era ontem). Sem data conta como hoje. */
  diasParaSair(c: Carga): number {
    const d = this.dataLocal(c.saidaPrevista);
    if (!d) return 0;
    return Math.round((d.getTime() - inicioDoDia(this.agora()).getTime()) / 86_400_000);
  }

  /** O Sankhya da Negri só tem a data da saída (a hora vem 00:00): atraso em dias. */
  atrasoTexto(c: Carga): string {
    const d = -this.diasParaSair(c);
    return d === 1 ? 'era de ontem' : `era de ${d} dias atrás`;
  }

  dataCurta(iso: string | null | undefined): string {
    const d = this.dataLocal(iso);
    if (!d) return '—';
    const dias = Math.round((d.getTime() - inicioDoDia(this.agora()).getTime()) / 86_400_000);
    if (dias === 0) return 'hoje';
    if (dias === 1) return 'amanhã';
    if (dias === -1) return 'ontem';
    return d.toLocaleDateString('pt-BR', { day: '2-digit', month: '2-digit' });
  }

  textoNotas(c: Carga): string {
    const n = c.notas;
    if (!n.total) return 'notas emitidas';
    if (n.problema) return plural(n.problema, 'nota recusada', 'notas recusadas');
    if (n.aguardando) return plural(n.aguardando, 'nota na SEFAZ', 'notas na SEFAZ');
    return n.total === 1 ? 'nota ok' : `${n.total} notas ok`;
  }

  situacaoTexto(c: Carga): string {
    if (c.situacao === 'saiu') return c.fechadaNoSankhya ? 'Saiu (OC fechada direto no Sankhya)' : 'Saiu para entrega';
    if (c.situacao === 'pronta') {
      const m = this.minutosPronta(c);
      return `Pronto${m != null ? ' há ' + duracaoTexto(m) : ''} — falta emitir as notas (Fechar OC)`;
    }
    const f = c.faltas;
    const p: string[] = [];
    if (f.corte) p.push(`${f.corte} esperando aprovação de falta`);
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
