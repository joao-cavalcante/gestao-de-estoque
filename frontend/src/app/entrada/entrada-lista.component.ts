import { Component, OnDestroy, OnInit, computed, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { Router, RouterLink } from '@angular/router';
import { Subscription } from 'rxjs';
import { OqIconComponent } from '../shared/icons/oq-icon.component';
import { OqSkeletonComponent } from '../shared/oq-skeleton/oq-skeleton.component';
import { SyncTickService } from '../shared/app-header/sync-tick.service';
import {
  CompraArmazenagem,
  EntradaService,
  ItemArmazenagem,
  ROTULO_ARMAZENAGEM,
  qtdTexto,
  ICONE_SITUACAO,
  ROTULO_SITUACAO,
  Recebimento,
  SituacaoRecebimento,
  ddmm,
  pesoTexto,
  rotuloAjuste,
} from './entrada.service';

/** Atividades da Entrada (19/11/2026): Recebimento e Armazenagem (endereçamento, 27/11/2026). */
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
  readonly rotuloArm = ROTULO_ARMAZENAGEM;
  readonly qtdTexto = qtdTexto;

  /** Armazenagem: compras com tarefa aberta, concluídas nas últimas 24 h e as aprovadas esperando tarefa. */
  readonly armazenagem = signal<CompraArmazenagem[] | null>(null);
  readonly erroArmazenagem = signal<string | null>(null);
  readonly abertas = signal<ReadonlySet<number>>(new Set());
  readonly pendentesArmazenagem = computed(() => (this.armazenagem() ?? []).filter((c) => c.situacao !== 'guardado').length);
  readonly armazenagemVisivel = computed(() => {
    const termo = this.busca().trim().toLowerCase();
    return (this.armazenagem() ?? []).filter((c) => !termo || [c.nunota, c.numnota, c.fornecedor]
      .some((v) => v != null && String(v).toLowerCase().includes(termo)));
  });

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
    this.carregarArmazenagem();
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

  carregarArmazenagem(): void {
    this.service.listarArmazenagem().subscribe({
      next: (l) => {
        this.armazenagem.set(l);
        this.erroArmazenagem.set(null);
      },
      error: (e) => this.erroArmazenagem.set(e?.error?.erro ?? 'Não foi possível ler a armazenagem no Sankhya.'),
    });
  }

  alternar(c: CompraArmazenagem): void {
    const s = new Set(this.abertas());
    if (s.has(c.nunota)) s.delete(c.nunota);
    else s.add(c.nunota);
    this.abertas.set(s);
  }

  guardados(c: CompraArmazenagem): number {
    return c.itens.filter((i) => i.situacao === 'CO').length;
  }

  operadores(c: CompraArmazenagem): string {
    return [...new Set(c.itens.map((i) => i.operador).filter((o): o is string => !!o))].join(', ');
  }

  rotuloItem(i: ItemArmazenagem): string {
    if (i.situacao === 'CO') return 'Guardado';
    if (i.situacao === 'CA') return 'Cancelado';
    if (i.qtdFeita > 0) return `Guardando · ${qtdTexto(i.qtdFeita)} de ${qtdTexto(i.qtd)}`;
    return i.situacao === 'EX' ? 'Em andamento' : 'Na doca';
  }

  /** "2026-11-27T15:54:15" → "27/11 15:54". */
  quando(iso: string | null): string {
    const m = /^\d{4}-(\d{2})-(\d{2})T(\d{2}):(\d{2})/.exec(iso ?? '');
    return m ? `${m[2]}/${m[1]} ${m[3]}:${m[4]}` : '';
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
