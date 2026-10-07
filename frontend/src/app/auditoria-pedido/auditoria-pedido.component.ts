import { Component, OnInit, computed, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { ActivatedRoute, Router } from '@angular/router';
import { HttpErrorResponse } from '@angular/common/http';
import { AuditoriaPedidoService } from './auditoria-pedido.service';
import { AuditoriaEvento, AuditoriaItem, AuditoriaPedido, OrigemEvento } from './auditoria-pedido.model';
import { OqIconComponent } from '../shared/icons/oq-icon.component';
import { OqSkeletonComponent } from '../shared/oq-skeleton/oq-skeleton.component';

const FORMATO_QTD = new Intl.NumberFormat('pt-BR', { maximumFractionDigits: 3 });
const FORMATO_VALOR = new Intl.NumberFormat('pt-BR', { style: 'currency', currency: 'BRL' });

type FiltroOrigem = 'todos' | OrigemEvento;

interface DiaLinhaTempo {
  dia: string;
  eventos: AuditoriaEvento[];
}

/**
 * Auditoria de Pedidos — tudo o que aconteceu com um pedido (Sankhya + Torre de Operação) numa linha do tempo:
 * inclusão, impressão do mapa, conferência, leituras, etapas, cortes/liberações, carregamento e nota gerada.
 * A URL guarda o número (/auditoria-pedido/63486) pra dar pra mandar o link pra alguém.
 */
@Component({
  selector: 'app-auditoria-pedido',
  standalone: true,
  imports: [FormsModule, OqIconComponent, OqSkeletonComponent],
  templateUrl: './auditoria-pedido.component.html',
  styleUrl: './auditoria-pedido.component.scss',
})
export class AuditoriaPedidoComponent implements OnInit {
  private readonly service = inject(AuditoriaPedidoService);
  private readonly route = inject(ActivatedRoute);
  private readonly router = inject(Router);

  readonly numero = signal('');
  readonly carregando = signal(false);
  readonly erro = signal<string | null>(null);
  readonly dados = signal<AuditoriaPedido | null>(null);
  readonly filtroOrigem = signal<FiltroOrigem>('todos');

  readonly opcoesOrigem: { id: FiltroOrigem; label: string }[] = [
    { id: 'todos', label: 'Tudo' },
    { id: 'SANKHYA', label: 'Sankhya' },
    { id: 'WMS', label: 'Torre de Operação' },
  ];

  /** Eventos filtrados, agrupados por dia (a lista já vem em ordem cronológica). */
  readonly dias = computed<DiaLinhaTempo[]>(() => {
    const filtro = this.filtroOrigem();
    const eventos = (this.dados()?.eventos ?? []).filter((e) => filtro === 'todos' || e.origem === filtro);
    const dias: DiaLinhaTempo[] = [];
    for (const e of eventos) {
      const dia = e.quando.substring(0, 10);
      const ultimo = dias[dias.length - 1];
      if (ultimo?.dia === dia) ultimo.eventos.push(e);
      else dias.push({ dia, eventos: [e] });
    }
    return dias;
  });

  readonly totalEventos = computed(() => this.dias().reduce((t, d) => t + d.eventos.length, 0));

  ngOnInit(): void {
    const n = this.route.snapshot.paramMap.get('numero');
    if (n) {
      this.numero.set(n);
      this.buscar();
    }
  }

  buscar(): void {
    const n = Number(this.numero().replace(/\D/g, ''));
    if (!n) {
      this.erro.set('Informe o número único ou o número do pedido.');
      return;
    }
    this.router.navigate(['/auditoria-pedido', n], { replaceUrl: true });
    this.carregando.set(true);
    this.erro.set(null);
    this.service.buscar(n).subscribe({
      next: (d) => {
        this.dados.set(d);
        this.carregando.set(false);
      },
      error: (e: HttpErrorResponse) => {
        this.dados.set(null);
        this.erro.set(e.error?.erro ?? 'Não foi possível consultar o pedido.');
        this.carregando.set(false);
      },
    });
  }

  setOrigem(o: FiltroOrigem): void {
    this.filtroOrigem.set(o);
  }

  // ── formatação ──

  dia(iso: string): string {
    const [a, m, d] = iso.split('-');
    const semana = new Date(`${iso}T12:00:00`).toLocaleDateString('pt-BR', { weekday: 'long' });
    return `${d}/${m}/${a} · ${semana}`;
  }

  hora(iso: string): string {
    return iso.substring(11, 16);
  }

  dataHora(iso: string | null): string {
    if (!iso) return '—';
    const [a, m, d] = iso.substring(0, 10).split('-');
    return `${d}/${m}/${a} ${iso.substring(11, 16)}`;
  }

  qtd(v: number | null): string {
    return v == null ? '—' : FORMATO_QTD.format(v);
  }

  valor(v: number | null): string {
    return v == null ? '—' : FORMATO_VALOR.format(v);
  }

  /** Conferido difere do pedido? (as duas quantidades já vêm na mesma unidade). */
  divergente(i: AuditoriaItem): boolean {
    if (i.qtdConferida == null || i.qtdNegociada == null) return false;
    return Math.abs(i.qtdConferida - i.qtdNegociada) > 0.0005;
  }

  statusNota(s: string | null): string {
    return s === 'L' ? 'Liberada' : s === 'P' ? 'Pendente' : s === 'A' ? 'Em atendimento' : s ?? '—';
  }
}
