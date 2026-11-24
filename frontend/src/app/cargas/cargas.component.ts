import { DatePipe } from '@angular/common';
import { Component, OnDestroy, OnInit, computed, inject, signal } from '@angular/core';
import { Subscription } from 'rxjs';
import { SyncTickService } from '../shared/app-header/sync-tick.service';
import { OqIconComponent, OqIconName } from '../shared/icons/oq-icon.component';
import { OqSkeletonComponent } from '../shared/oq-skeleton/oq-skeleton.component';
import { Carga, CargasService, PedidoCarga, ROTULO_PEDIDO, SituacaoCarga, SituacaoPedido, pesoTexto, rotuloImpressao } from './cargas.service';

type Periodo = 'todos' | 'D' | 'N';

interface Alerta {
  icone: OqIconName;
  texto: string;
  ocs: number[];
  grave: boolean;
}

const ICONE_PEDIDO: Record<SituacaoPedido, OqIconName> = {
  corte: 'circle-alert',
  a_conferir: 'circle',
  em_conferencia: 'gear',
  a_carregar: 'entrega',
  carregado: 'entrega',
  com_nota: 'receipt',
};

const COLUNAS: { chave: SituacaoCarga; titulo: string }[] = [
  { chave: 'pendente', titulo: 'Carregando' },
  { chave: 'pronta', titulo: 'Prontos para sair' },
  { chave: 'saiu', titulo: 'Saíram hoje' },
];

function plural(n: number, um: string, varios: string): string {
  return `${n} ${n === 1 ? um : varios}`;
}

/**
 * Cargas (24/11/2026) — visão CONSOLIDADA das Ordens de Carga para quem acompanha (usuário: "dashboard… fácil
 * entendimento porém completo"; depois "modelo consolidado", sem cartões nem cara de TV). Só leitura e numa tela só
 * (a página não rola; só a lista de OCs rola por dentro): totais por situação, por período (Diurno/Noturno), uma linha
 * de atenção e a lista de OCs (clique abre os pedidos). Saiu = Fechar OC (DECISÃO DE PRODUTO). Atualiza com o sync.
 */
@Component({
  selector: 'app-cargas',
  standalone: true,
  imports: [DatePipe, OqIconComponent, OqSkeletonComponent],
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
        texto: plural(atrasadas.length, 'atrasado', 'atrasados') });
    }
    const pesados = this.cargas().filter((c) => (this.ocupacao(c) ?? 0) > 100);
    if (pesados.length) {
      lista.push({ icone: 'scale', grave: true, ocs: pesados.map((c) => c.ordemCarga),
        texto: `${pesados.length} acima do peso` });
    }
    const cortes = abertas.filter((c) => c.faltas.corte > 0);
    if (cortes.length) {
      const n = cortes.reduce((s, c) => s + c.faltas.corte, 0);
      lista.push({ icone: 'circle-alert', grave: true, ocs: cortes.map((c) => c.ordemCarga),
        texto: `${plural(n, 'pedido esperando', 'pedidos esperando')} liberação de corte` });
    }
    const notasRuins = saiu.filter((c) => c.notas.problema > 0);
    if (notasRuins.length) {
      lista.push({ icone: 'receipt', grave: true, ocs: notasRuins.map((c) => c.ordemCarga),
        texto: `${notasRuins.length} com nota recusada pela SEFAZ` });
    }
    const impErro = saiu.filter((c) => c.impressao?.status === 'erro');
    if (impErro.length) {
      lista.push({ icone: 'impressora', grave: false, ocs: impErro.map((c) => c.ordemCarga),
        texto: `${impErro.length} com erro na impressão` });
    }
    const semMotorista = abertas.filter((c) => !c.motorista);
    if (semMotorista.length) {
      lista.push({ icone: 'user', grave: false, ocs: semMotorista.map((c) => c.ordemCarga),
        texto: `${semMotorista.length} sem motorista` });
    }
    return lista;
  });

  /** Pedidos por situação (soma dos pedidos das OCs). */
  readonly pedidosPorColuna = computed(() => {
    const r: Record<SituacaoCarga, number> = { pendente: 0, pronta: 0, saiu: 0 };
    for (const c of this.cargas()) r[c.situacao] += c.pedidos.length;
    return r;
  });

  readonly pedidosTotal = computed(() => this.cargas().reduce((s, c) => s + c.pedidos.length, 0));
  readonly pesoTotal = computed(() => this.cargas().reduce((s, c) => s + c.pesoKg, 0));

  /** Caminhões por período × situação (OC com pedidos dos dois períodos conta nos dois). */
  readonly porPeriodo = computed(() =>
    (['D', 'N'] as const).map((t) => {
      const doPeriodo = this.todas().filter((c) => (c.turnos ?? []).includes(t));
      const n = (s: SituacaoCarga) => doPeriodo.filter((c) => c.situacao === s).length;
      return { rotulo: t === 'D' ? 'Diurno' : 'Noturno', pendente: n('pendente'), pronta: n('pronta'), saiu: n('saiu'), total: doPeriodo.length };
    }),
  );
  readonly temMista = computed(() => this.todas().some((c) => (c.turnos ?? []).length > 1));

  /** Lista de OCs: carregando, depois prontas, depois as que saíram (a ordem do backend dentro de cada grupo). */
  readonly linhas = computed(() => [...this.porColuna().pendente, ...this.porColuna().pronta, ...this.porColuna().saiu]);

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

  /** Situação numa frase curta para a linha da tabela. */
  situacaoTexto(c: Carga): string {
    if (c.situacao === 'pendente') {
      const f = c.faltas;
      const partes: string[] = [];
      if (f.corte) partes.push(`${f.corte} no corte`);
      if (f.aConferir) partes.push(`${f.aConferir} a conferir`);
      if (f.emConferencia) partes.push(`${f.emConferencia} conferindo`);
      if (f.aCarregar) partes.push(`${f.aCarregar} a carregar`);
      return `Carregando · falta ${partes.join(', ')}`;
    }
    if (c.situacao === 'pronta') return 'Pronto · falta fechar a OC';
    return `Saiu`;
  }

  saidaCurta(c: Carga): string {
    const d = this.dataLocal(c.saidaPrevista);
    if (!d) return '—';
    if (d.toDateString() === new Date().toDateString()) return 'hoje';
    return d.toLocaleDateString('pt-BR', { day: '2-digit', month: '2-digit' });
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

}
