import { Component, OnDestroy, OnInit, computed, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { Router, RouterLink } from '@angular/router';
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

/** Atividades da Entrada (19/11/2026): Recebimento agora; Armazenagem chega na etapa 4 (endereçamento). */
type Atividade = 'recebimento' | 'armazenagem';

const EM_ABERTO: SituacaoRecebimento[] = ['aguardando_conferencia', 'em_conferencia', 'divergencia'];

/** Prioridade na lista: o que trava alguém primeiro. */
const ORDEM: Record<SituacaoRecebimento, number> = {
  divergencia: 0,
  em_conferencia: 1,
  aguardando_conferencia: 2,
  aguardando_liberacao: 3,
  liberada: 4,
};

/**
 * Entrada — atividades do chão na entrada de mercadoria (19/11/2026): Recebimento (conferir e pesar a compra) e
 * Armazenagem (etapa 4). A compra saiu da Fila de Conferência e fica só aqui; conferida, sai daqui e vai para a tela
 * Liberação de Recebimento (pedido do usuário: "na entrada a gente mantém o foco na atividade").
 */
@Component({
  selector: 'app-entrada-lista',
  standalone: true,
  imports: [FormsModule, RouterLink, OqIconComponent, OqSkeletonComponent],
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
  readonly atividade = signal<Atividade>('recebimento');
  readonly busca = signal('');

  readonly aReceber = computed(() => this.recebimentos().filter((r) => EM_ABERTO.includes(r.situacao)));
  readonly aguardandoLiberacao = computed(() => this.recebimentos().filter((r) => r.situacao === 'aguardando_liberacao').length);

  readonly visiveis = computed(() => {
    const termo = this.busca().trim().toLowerCase();
    return this.aReceber()
      .filter((r) => !termo || [r.nunota, r.numeroNota, r.fornecedor, r.codigoParceiro]
        .some((v) => v != null && String(v).toLowerCase().includes(termo)))
      .sort((a, b) => ORDEM[a.situacao] - ORDEM[b.situacao] || b.nunota - a.nunota);
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
      case 'liberada':
        return { texto: 'Ver na liberação', forte: false };
    }
  }

  agir(r: Recebimento): void {
    if (r.situacao === 'divergencia') {
      this.router.navigate(['/liberacoes']);
    } else if (r.situacao === 'aguardando_liberacao' || r.situacao === 'liberada') {
      this.router.navigate(['/liberacoes/recebimento', r.nunota]);
    } else {
      this.router.navigate(['/conferencia', r.nunota], {
        state: {
          origem: 'entrada',
          tarefa: { nf: r.numeroNota ? String(r.numeroNota) : '—', cliente: r.fornecedor ?? '—', responsavel: '—' },
        },
      });
    }
  }

  limparBusca(): void {
    this.busca.set('');
  }
}
