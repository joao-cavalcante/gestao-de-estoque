import { Component, OnDestroy, OnInit, computed, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { Router } from '@angular/router';
import { Subscription } from 'rxjs';
import { OqIconComponent } from '../shared/icons/oq-icon.component';
import { OqSkeletonComponent } from '../shared/oq-skeleton/oq-skeleton.component';
import { SyncTickService } from '../shared/app-header/sync-tick.service';
import {
  EntradaService,
  ICONE_SITUACAO,
  ROTULO_SITUACAO,
  Recebimento,
  SituacaoRecebimento,
  ddmm,
  pesoTexto,
  rotuloAjuste,
} from './entrada.service';

type Filtro = 'aberto' | 'liberacao' | 'liberadas' | 'todos';

const EM_ABERTO: SituacaoRecebimento[] = ['aguardando_conferencia', 'em_conferencia', 'divergencia'];

/** Prioridade na lista: o que trava alguém primeiro. */
const ORDEM: Record<SituacaoRecebimento, number> = {
  divergencia: 0,
  aguardando_liberacao: 1,
  em_conferencia: 2,
  aguardando_conferencia: 3,
  liberada: 4,
};

/**
 * Entrada — recebimentos de compra (19/11/2026, proposta "Entrada com endereçamento", etapa 2, primeira entrega).
 * A compra saiu da Fila de Conferência e fica só aqui. Conferir abre a mesma tela de conferência; a conferida vai
 * para a mesa de liberação. Só leitura: a confirmação da compra continua no Sankhya.
 */
@Component({
  selector: 'app-entrada-lista',
  standalone: true,
  imports: [FormsModule, OqIconComponent, OqSkeletonComponent],
  templateUrl: './entrada-lista.component.html',
  styleUrl: './entrada.scss',
})
export class EntradaListaComponent implements OnInit, OnDestroy {
  private readonly service = inject(EntradaService);
  private readonly router = inject(Router);
  private readonly syncTick = inject(SyncTickService);
  private syncSub?: Subscription;
  private carregada = false;

  readonly rotulo = ROTULO_SITUACAO;
  readonly icone = ICONE_SITUACAO;
  readonly ddmm = ddmm;
  readonly pesoTexto = pesoTexto;
  readonly rotuloAjuste = rotuloAjuste;

  readonly recebimentos = signal<Recebimento[]>([]);
  readonly carregando = signal(true);
  readonly erro = signal<string | null>(null);
  readonly filtro = signal<Filtro>('aberto');
  readonly busca = signal('');

  readonly contagens = computed(() => {
    const l = this.recebimentos();
    return {
      aberto: l.filter((r) => EM_ABERTO.includes(r.situacao)).length,
      liberacao: l.filter((r) => r.situacao === 'aguardando_liberacao').length,
      liberadas: l.filter((r) => r.situacao === 'liberada').length,
      todos: l.length,
    };
  });

  readonly visiveis = computed(() => {
    const f = this.filtro();
    const termo = this.busca().trim().toLowerCase();
    return this.recebimentos()
      .filter((r) =>
        f === 'todos' ? true
        : f === 'aberto' ? EM_ABERTO.includes(r.situacao)
        : f === 'liberacao' ? r.situacao === 'aguardando_liberacao'
        : r.situacao === 'liberada',
      )
      .filter((r) => !termo || [r.nunota, r.numeroNota, r.fornecedor, r.codigoParceiro]
        .some((v) => v != null && String(v).toLowerCase().includes(termo)))
      .sort((a, b) => ORDEM[a.situacao] - ORDEM[b.situacao] || b.nunota - a.nunota);
  });

  readonly filtros: { id: Filtro; label: string }[] = [
    { id: 'aberto', label: 'Em aberto' },
    { id: 'liberacao', label: 'Aguardando liberação' },
    { id: 'liberadas', label: 'Liberadas' },
    { id: 'todos', label: 'Todos' },
  ];

  readonly rotuloFiltro = computed(() => this.filtros.find((f) => f.id === this.filtro())?.label ?? '');

  ngOnInit(): void {
    this.carregar();
    this.syncSub = this.syncTick.onTick.subscribe(() => this.carregar());
  }

  ngOnDestroy(): void {
    this.syncSub?.unsubscribe();
  }

  carregar(): void {
    if (!this.carregada) this.carregando.set(true);
    this.service.listar().subscribe({
      next: (l) => {
        this.recebimentos.set(l);
        this.carregada = true;
        this.erro.set(null);
        this.carregando.set(false);
        this.syncTick.registrarSucesso();
      },
      error: (e) => {
        const msg = e?.error?.erro ?? 'Não foi possível carregar a Entrada.';
        if (!this.carregada) this.erro.set(msg);
        this.carregando.set(false);
        this.syncTick.registrarFalha(msg);
      },
    });
  }

  ocorrencias(r: Recebimento): number {
    return (r.devolucao ? 1 : 0) + (r.complemento ? 1 : 0) + (r.ajustesPeso?.length ?? 0);
  }

  temErroAjuste(r: Recebimento): boolean {
    return !!r.ajustesPeso?.some((a) => a.status === 'erro');
  }

  /** Texto e destino do botão do card — um botão por card, como na Fila. */
  acao(r: Recebimento): { texto: string; forte: boolean } {
    switch (r.situacao) {
      case 'aguardando_conferencia':
        return { texto: 'Conferir', forte: false };
      case 'em_conferencia':
        return r.andamento
          ? { texto: `Continuar · ${r.andamento.conferidos}/${r.andamento.total}`, forte: true }
          : { texto: 'Conferir', forte: false };
      case 'divergencia':
        return { texto: 'Liberação de corte', forte: true };
      case 'aguardando_liberacao':
        return { texto: 'Abrir mesa', forte: true };
      case 'liberada':
        return { texto: 'Ver mesa', forte: false };
    }
  }

  agir(r: Recebimento): void {
    if (r.situacao === 'divergencia') {
      this.router.navigate(['/liberacao-corte']);
    } else if (r.situacao === 'aguardando_liberacao' || r.situacao === 'liberada') {
      this.router.navigate(['/entrada', r.nunota]);
    } else {
      this.router.navigate(['/conferencia', r.nunota], {
        state: {
          origem: 'entrada',
          tarefa: { nf: r.numeroNota ? String(r.numeroNota) : '—', cliente: r.fornecedor ?? '—', responsavel: '—' },
        },
      });
    }
  }

  limparFiltros(): void {
    this.filtro.set('todos');
    this.busca.set('');
  }
}
