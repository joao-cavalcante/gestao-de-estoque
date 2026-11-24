import { DatePipe } from '@angular/common';
import { Component, OnDestroy, OnInit, computed, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { Router } from '@angular/router';
import { Subscription } from 'rxjs';
import { SyncTickService } from '../shared/app-header/sync-tick.service';
import { OqIconComponent, OqIconName } from '../shared/icons/oq-icon.component';
import { OqModalidadePinsComponent } from '../shared/oq-modalidade-pins/oq-modalidade-pins.component';
import { OqSkeletonComponent } from '../shared/oq-skeleton/oq-skeleton.component';
import {
  Carga,
  CargasService,
  DiaSaida,
  PedidoCarga,
  ROTULO_PEDIDO,
  SituacaoCarga,
  SituacaoPedido,
  pesoTexto,
  rotuloImpressao,
  textoFaltas,
} from './cargas.service';

const ABAS: { chave: SituacaoCarga; rotulo: string }[] = [
  { chave: 'pendente', rotulo: 'Pendentes' },
  { chave: 'pronta', rotulo: 'Prontas para sair' },
  { chave: 'saiu', rotulo: 'Saíram' },
];

const ICONE_PEDIDO: Record<SituacaoPedido, OqIconName> = {
  corte: 'circle-alert',
  a_conferir: 'circle',
  em_conferencia: 'gear',
  a_carregar: 'entrega',
  carregado: 'entrega',
  com_nota: 'receipt',
};

/**
 * Cargas (24/11/2026) — situação das Ordens de Carga para quem coordena a expedição: pendentes, prontas para sair
 * e as que já saíram (DECISÃO DE PRODUTO: saiu = Fechar OC). Só leitura; a ação continua na Fila (o botão leva à
 * OC filtrada). Atualiza junto com o sync (60 s).
 */
@Component({
  selector: 'app-cargas',
  standalone: true,
  imports: [DatePipe, FormsModule, OqIconComponent, OqModalidadePinsComponent, OqSkeletonComponent],
  templateUrl: './cargas.component.html',
  styleUrl: './cargas.component.scss',
})
export class CargasComponent implements OnInit, OnDestroy {
  private readonly service = inject(CargasService);
  private readonly router = inject(Router);
  private readonly syncTick = inject(SyncTickService);
  private syncSub?: Subscription;
  private carregada = false;

  readonly abas = ABAS;
  readonly textoFaltas = textoFaltas;
  readonly pesoTexto = pesoTexto;
  readonly rotuloImpressao = rotuloImpressao;
  readonly rotuloPedido = ROTULO_PEDIDO;
  readonly iconePedido = ICONE_PEDIDO;

  private readonly cargas = signal<Carga[]>([]);
  readonly carregando = signal(true);
  readonly erro = signal<string | null>(null);
  readonly aba = signal<SituacaoCarga>('pendente');
  readonly dia = signal<DiaSaida>('hoje');
  readonly busca = signal('');
  readonly abertas = signal<ReadonlySet<number>>(new Set());

  readonly contagem = computed(() => {
    const c: Record<SituacaoCarga, number> = { pendente: 0, pronta: 0, saiu: 0 };
    for (const x of this.cargas()) c[x.situacao]++;
    return c;
  });

  readonly visiveis = computed(() => {
    const termo = this.busca().trim().toLowerCase();
    return this.cargas()
      .filter((c) => c.situacao === this.aba())
      .filter((c) => !termo || [String(c.ordemCarga), c.motorista, c.placa, ...c.pedidos.flatMap((p) => [String(p.nunota), p.cliente])]
        .some((v) => v != null && v.toLowerCase().includes(termo)));
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
    this.service.painel(this.dia()).subscribe({
      next: (p) => {
        this.cargas.set(p.cargas);
        this.carregada = true;
        this.erro.set(null);
        this.carregando.set(false);
      },
      error: (e) => {
        // Com a lista na tela, uma atualização que falha não apaga o que já está lá.
        if (!this.carregada) this.erro.set(e?.error?.erro ?? 'Não foi possível carregar as cargas.');
        this.carregando.set(false);
      },
    });
  }

  escolherDia(d: DiaSaida): void {
    if (this.dia() === d) return;
    this.dia.set(d);
    this.carregar();
  }

  alternar(oc: number): void {
    const s = new Set(this.abertas());
    if (s.has(oc)) s.delete(oc);
    else s.add(oc);
    this.abertas.set(s);
  }

  abrirNaFila(c: Carga): void {
    this.router.navigate(['/fila-tarefas'], { queryParams: { oc: c.ordemCarga } });
  }

  verImpressao(): void {
    this.router.navigate(['/impressao-oc']);
  }

  limparBusca(): void {
    this.busca.set('');
  }

  /** Ocupação do caminhão pelo peso (TGFORD.PESOMAX); null sem capacidade. */
  ocupacao(c: Carga): number | null {
    return c.pesoMaxKg ? Math.round((c.pesoKg * 100) / c.pesoMaxKg) : null;
  }

  /** "6 NFs: 5 aprovadas · 1 aguardando SEFAZ". */
  textoNotas(c: Carga): string {
    const n = c.notas;
    if (!n.total) return 'Notas no Sankhya';
    const partes = [`${n.aprovadas} ${n.aprovadas === 1 ? 'aprovada' : 'aprovadas'}`];
    if (n.aguardando) partes.push(`${n.aguardando} aguardando SEFAZ`);
    if (n.problema) partes.push(`${n.problema} com problema`);
    return `${n.total} ${n.total === 1 ? 'NF' : 'NFs'}: ${partes.join(' · ')}`;
  }

  /** Situação do pedido; com nota mostra "NF 3837 · Aprovada". */
  rotulo(p: PedidoCarga): string {
    if (p.situacao === 'com_nota' && p.notaNumero) return `NF ${p.notaNumero} · ${p.notaSituacao ?? ''}`.trim();
    return this.rotuloPedido[p.situacao];
  }

  private dataLocal(iso: string | null): Date | null {
    const m = /^(\d{4})-(\d{2})-(\d{2})/.exec(iso ?? '');
    return m ? new Date(Number(m[1]), Number(m[2]) - 1, Number(m[3])) : null;
  }

  saidaTexto(c: Carga): string {
    const d = this.dataLocal(c.saidaPrevista);
    if (!d) return 'sem saída prevista';
    const hoje = new Date();
    const mesmoDia = d.toDateString() === hoje.toDateString();
    return `saída ${mesmoDia ? 'hoje' : d.toLocaleDateString('pt-BR', { day: '2-digit', month: '2-digit' })}`;
  }

  atrasada(c: Carga): boolean {
    const d = this.dataLocal(c.saidaPrevista);
    if (!d || c.situacao === 'saiu') return false;
    const hoje = new Date();
    return d.getTime() < new Date(hoje.getFullYear(), hoje.getMonth(), hoje.getDate()).getTime();
  }

  textoVazio(): string {
    switch (this.aba()) {
      case 'pendente':
        return 'Nenhuma OC com pendência agora.';
      case 'pronta':
        return 'Nenhuma OC pronta esperando o Fechar OC.';
      case 'saiu':
        return this.dia() === 'hoje' ? 'Nenhuma OC saiu hoje ainda.' : 'Nenhuma OC saiu ontem.';
    }
  }
}
