import { DatePipe } from '@angular/common';
import { Component, OnDestroy, OnInit, computed, inject, signal } from '@angular/core';
import { Subscription } from 'rxjs';
import { SyncTickService } from '../shared/app-header/sync-tick.service';
import { OqIconComponent, OqIconName } from '../shared/icons/oq-icon.component';
import { OqModalidadePinsComponent } from '../shared/oq-modalidade-pins/oq-modalidade-pins.component';
import { OqSkeletonComponent } from '../shared/oq-skeleton/oq-skeleton.component';
import { Carga, CargasService, PedidoCarga, ROTULO_PEDIDO, SituacaoCarga, SituacaoPedido, pesoTexto, rotuloImpressao } from './cargas.service';

type Periodo = 'todos' | 'D' | 'N';

interface Alerta {
  icone: OqIconName;
  texto: string;
  ocs: number[];
  grave: boolean;
}

interface BarraHora {
  hora: number;
  qtd: number;
  pct: number;
}

const ICONE_PEDIDO: Record<SituacaoPedido, OqIconName> = {
  corte: 'circle-alert',
  a_conferir: 'circle',
  em_conferencia: 'gear',
  a_carregar: 'entrega',
  carregado: 'entrega',
  com_nota: 'receipt',
};

const COLUNAS: { chave: SituacaoCarga; titulo: string; sub: string; icone: OqIconName }[] = [
  { chave: 'pendente', titulo: 'Carregando', sub: 'ainda no galpão', icone: 'box' },
  { chave: 'pronta', titulo: 'Prontos para sair', sub: 'só falta fechar a OC', icone: 'entrega' },
  { chave: 'saiu', titulo: 'Já saíram hoje', sub: 'foram para a entrega', icone: 'check' },
];

function plural(n: number, um: string, varios: string): string {
  return `${n} ${n === 1 ? um : varios}`;
}

/**
 * Cargas (24/11/2026) — DASHBOARD de acompanhamento das Ordens de Carga, para quem não opera a expedição (pedido do
 * usuário: "como um dashboard e não uma tela operacional… fácil entendimento porém bem completo"). Só leitura: três
 * números grandes, andamento do dia, "Precisa de atenção" em frases, quadro com um cartão por caminhão (Carregando ·
 * Prontos para sair · Já saíram hoje) e saídas por hora. Filtro por período de entrega (Diurno/Noturno). Saiu =
 * Fechar OC (DECISÃO DE PRODUTO, 24/11/2026). Atualiza junto com o sync (60 s).
 */
@Component({
  selector: 'app-cargas',
  standalone: true,
  imports: [DatePipe, OqIconComponent, OqModalidadePinsComponent, OqSkeletonComponent],
  templateUrl: './cargas.component.html',
  styleUrl: './cargas.component.scss',
})
export class CargasComponent implements OnInit, OnDestroy {
  private readonly service = inject(CargasService);
  private readonly syncTick = inject(SyncTickService);
  private syncSub?: Subscription;
  private carregada = false;

  readonly colunas = COLUNAS;
  readonly pesoTexto = pesoTexto;
  readonly rotuloImpressao = rotuloImpressao;
  readonly hoje = new Date();

  private readonly todas = signal<Carga[]>([]);
  readonly atualizadoEm = signal<Date | null>(null);
  readonly carregando = signal(true);
  readonly erro = signal<string | null>(null);
  readonly periodo = signal<Periodo>('todos');
  readonly aberta = signal<number | null>(null);

  readonly cargas = computed(() => {
    const p = this.periodo();
    return p === 'todos' ? this.todas() : this.todas().filter((c) => (c.turnos ?? []).includes(p));
  });

  readonly porColuna = computed(() => {
    const r: Record<SituacaoCarga, Carga[]> = { pendente: [], pronta: [], saiu: [] };
    for (const c of this.cargas()) r[c.situacao].push(c);
    return r;
  });

  readonly pesoPorColuna = computed(() => {
    const r: Record<SituacaoCarga, number> = { pendente: 0, pronta: 0, saiu: 0 };
    for (const c of this.cargas()) r[c.situacao] += c.pesoKg;
    return r;
  });

  readonly total = computed(() => this.cargas().length);
  readonly saidas = computed(() => this.porColuna().saiu.length);
  readonly pctDia = computed(() => (this.total() ? Math.round((this.saidas() * 100) / this.total()) : 0));

  readonly alertas = computed<Alerta[]>(() => {
    const abertas = this.cargas().filter((c) => c.situacao !== 'saiu');
    const saiu = this.porColuna().saiu;
    const lista: Alerta[] = [];
    const atrasadas = abertas.filter((c) => this.atrasada(c));
    if (atrasadas.length) {
      lista.push({ icone: 'clock', grave: true, ocs: atrasadas.map((c) => c.ordemCarga),
        texto: `${plural(atrasadas.length, 'caminhão atrasado', 'caminhões atrasados')} — a saída prevista já passou` });
    }
    const pesados = this.cargas().filter((c) => (this.ocupacao(c) ?? 0) > 100);
    if (pesados.length) {
      lista.push({ icone: 'scale', grave: true, ocs: pesados.map((c) => c.ordemCarga),
        texto: `${plural(pesados.length, 'caminhão', 'caminhões')} com mais peso do que cabe` });
    }
    const cortes = abertas.filter((c) => c.faltas.corte > 0);
    if (cortes.length) {
      const n = cortes.reduce((s, c) => s + c.faltas.corte, 0);
      lista.push({ icone: 'circle-alert', grave: true, ocs: cortes.map((c) => c.ordemCarga),
        texto: `${plural(n, 'pedido esperando', 'pedidos esperando')} liberação de corte (alguém precisa liberar)` });
    }
    const notasRuins = saiu.filter((c) => c.notas.problema > 0);
    if (notasRuins.length) {
      lista.push({ icone: 'receipt', grave: true, ocs: notasRuins.map((c) => c.ordemCarga),
        texto: `${plural(notasRuins.length, 'caminhão saiu', 'caminhões saíram')} com nota recusada pela SEFAZ` });
    }
    const impErro = saiu.filter((c) => c.impressao?.status === 'erro');
    if (impErro.length) {
      lista.push({ icone: 'impressora', grave: false, ocs: impErro.map((c) => c.ordemCarga),
        texto: `Impressão das notas deu erro em ${plural(impErro.length, 'caminhão', 'caminhões')}` });
    }
    const semMotorista = abertas.filter((c) => !c.motorista);
    if (semMotorista.length) {
      lista.push({ icone: 'user', grave: false, ocs: semMotorista.map((c) => c.ordemCarga),
        texto: `${plural(semMotorista.length, 'caminhão', 'caminhões')} sem motorista definido` });
    }
    return lista;
  });

  /** Caminhões que saíram por hora (hora do Fechar OC). Eixo fixo 06h–22h, ampliado se houver saída fora disso. */
  readonly saidasPorHora = computed<BarraHora[]>(() => {
    const conta = new Map<number, number>();
    for (const c of this.porColuna().saiu) {
      if (!c.saiuEm) continue;
      const h = new Date(c.saiuEm).getHours();
      conta.set(h, (conta.get(h) ?? 0) + 1);
    }
    const horas = [...conta.keys()];
    const ini = Math.min(6, ...horas);
    const fim = Math.max(22, ...horas);
    const max = Math.max(1, ...conta.values());
    const barras: BarraHora[] = [];
    for (let h = ini; h <= fim; h++) {
      const qtd = conta.get(h) ?? 0;
      barras.push({ hora: h, qtd, pct: (qtd * 100) / max });
    }
    return barras;
  });

  readonly picoHora = computed(() => {
    const b = this.saidasPorHora();
    const max = Math.max(0, ...b.map((x) => x.qtd));
    return max > 0 ? b.find((x) => x.qtd === max)!.hora : null;
  });

  ngOnInit(): void {
    this.carregar();
    this.syncSub = this.syncTick.onTick.subscribe(() => this.carregar());
  }

  ngOnDestroy(): void {
    this.syncSub?.unsubscribe();
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
      },
      error: (e) => {
        if (!this.carregada) this.erro.set(e?.error?.erro ?? 'Não foi possível carregar as cargas.');
        this.carregando.set(false);
      },
    });
  }

  alternar(oc: number): void {
    this.aberta.set(this.aberta() === oc ? null : oc);
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

  saidaPrevistaTexto(c: Carga): string {
    const d = this.dataLocal(c.saidaPrevista);
    if (!d) return 'Sem data de saída';
    if (d.toDateString() === new Date().toDateString()) return 'Sai hoje';
    const txt = d.toLocaleDateString('pt-BR', { day: '2-digit', month: '2-digit' });
    return this.atrasada(c) ? `Era para sair ${txt}` : `Sai ${txt}`;
  }

  /** Frases do que falta, em português simples. */
  frasesFalta(c: Carga): string[] {
    const f = c.faltas;
    const r: string[] = [];
    if (f.corte) r.push(`${plural(f.corte, 'pedido esperando', 'pedidos esperando')} liberação de corte`);
    if (f.aConferir) r.push(`${plural(f.aConferir, 'pedido', 'pedidos')} para conferir`);
    if (f.emConferencia) r.push(`${plural(f.emConferencia, 'pedido sendo conferido', 'pedidos sendo conferidos')} agora`);
    if (f.aCarregar) r.push(`${plural(f.aCarregar, 'pedido', 'pedidos')} para colocar no caminhão`);
    return r;
  }

  textoNotas(c: Carga): string {
    const n = c.notas;
    if (!n.total) return 'Notas emitidas';
    if (n.problema) return `${plural(n.problema, 'nota recusada', 'notas recusadas')} pela SEFAZ`;
    if (n.aguardando) return `${plural(n.aguardando, 'nota aguardando', 'notas aguardando')} a SEFAZ`;
    return n.total === 1 ? 'Nota aprovada' : `${n.total} notas aprovadas`;
  }

  periodoTexto(c: Carga): string | null {
    const t = c.turnos ?? [];
    if (t.length > 1) return 'Diurno e noturno';
    if (t[0] === 'D') return 'Diurno';
    if (t[0] === 'N') return 'Noturno';
    return null;
  }

  rotuloPedido(p: PedidoCarga): string {
    if (p.situacao === 'com_nota' && p.notaNumero) return `NF ${p.notaNumero} · ${p.notaSituacao ?? ''}`.trim();
    return ROTULO_PEDIDO[p.situacao];
  }

  icone(p: PedidoCarga): OqIconName {
    return ICONE_PEDIDO[p.situacao];
  }

  barraTitulo(b: BarraHora): string {
    return `${String(b.hora).padStart(2, '0')}h: ${b.qtd === 0 ? 'nenhum caminhão saiu' : plural(b.qtd, 'caminhão saiu', 'caminhões saíram')}`;
  }
}
