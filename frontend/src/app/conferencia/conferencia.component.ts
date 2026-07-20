import { Component, OnDestroy, OnInit, computed, inject, signal } from '@angular/core';
import { ActivatedRoute, Router } from '@angular/router';
import { Subscription, interval } from 'rxjs';
import { filter, first, switchMap, timeout } from 'rxjs/operators';
import { OqConferenciaHeaderComponent } from './oq-conferencia-header/oq-conferencia-header.component';
import { OqScanBarComponent, ProdutoIdentificadoEvento } from './oq-scan-bar/oq-scan-bar.component';
import { OqPendingListComponent } from './oq-pending-list/oq-pending-list.component';
import { OqConferredListComponent } from './oq-conferred-list/oq-conferred-list.component';
import { OqLastScanPanelComponent } from './oq-last-scan-panel/oq-last-scan-panel.component';
import { OqConferenciaFooterComponent } from './oq-conferencia-footer/oq-conferencia-footer.component';
import { ConferenciaItem } from './conferencia.model';
import { SeparacaoService } from '../separacao/separacao.service';
import { ItemConferido, ItemSeparacao } from '../separacao/separacao.model';
import { Tarefa } from '../fila-tarefas/tarefa.model';

/** Mapeia o item real (vindo de app.separacao_itens) pro modelo visual do painel — mesmo shape do mock anterior. */
function mapearItem(item: ItemSeparacao): ConferenciaItem {
  const expected = Number(item.qtdNeg);
  const scanned = Number(item.qtdConferidaLocal);
  const divergente = scanned > expected;
  return {
    seq: item.sequencia,
    code: String(item.codprod),
    name: item.descricaoProduto || `Produto ${item.codprod}`,
    control: item.controle.trim() || '—',
    expected,
    scanned,
    status: divergente ? 'critical' : scanned >= expected ? 'ok' : 'pending',
    divergenceReason: divergente ? 'QTD. DIVERGENTE' : undefined,
  };
}

@Component({
  selector: 'app-conferencia',
  standalone: true,
  imports: [
    OqConferenciaHeaderComponent,
    OqScanBarComponent,
    OqPendingListComponent,
    OqConferredListComponent,
    OqLastScanPanelComponent,
    OqConferenciaFooterComponent,
  ],
  templateUrl: './conferencia.component.html',
  styleUrl: './conferencia.component.scss',
})
export class ConferenciaComponent implements OnInit, OnDestroy {
  private readonly route = inject(ActivatedRoute);
  private readonly router = inject(Router);
  private readonly separacaoService = inject(SeparacaoService);
  private sessaoSub?: Subscription;

  // TODO: mesmo TODO do resto do app — sem login/JWT amarrando tenant ainda.
  readonly tenantAtual = 'modial';

  carregando = signal(true);
  erro = signal<string | null>(null);

  nf = '—';
  parceiro = '—';
  vendedor = '—';
  numeroConferencia = '—';

  private readonly items = signal<ConferenciaItem[]>([]);
  private readonly conferred = signal<ConferenciaItem[]>([]);
  readonly lastScan = signal<ConferenciaItem | null>(null);

  /** Sessão atual — usado pelo scan-bar (identificar/conferir) e pelo devolver-item. */
  sessaoIdAtual: string | null = null;

  readonly pendingItems = computed(() => this.items());
  readonly pendingCount = computed(() => this.items().length);
  readonly conferredCount = computed(() => this.conferred().length);
  readonly divergenceCount = computed(() => this.conferred().filter((i) => i.status === 'critical').length);
  readonly canConfirm = computed(() => this.pendingCount() === 0 && this.divergenceCount() === 0);

  readonly sortedConferred = computed(() =>
    [...this.conferred()].sort((a, b) => {
      if (a.status === 'critical' && b.status !== 'critical') return -1;
      if (b.status === 'critical' && a.status !== 'critical') return 1;
      return 0;
    }),
  );

  ngOnInit(): void {
    const nunota = Number(this.route.snapshot.paramMap.get('nunota'));
    const tarefa = history.state?.tarefa as Tarefa | undefined;
    if (tarefa) {
      this.nf = tarefa.nf;
      this.parceiro = tarefa.cliente;
      this.vendedor = tarefa.responsavel;
    }
    this.numeroConferencia = `NUNOTA-${nunota}`;

    if (!nunota) {
      this.erro.set('Número da nota inválido.');
      this.carregando.set(false);
      return;
    }

    this.separacaoService.iniciar(this.tenantAtual, nunota).subscribe({
      next: (resp) => this.aguardarSessaoPronta(resp.sessaoId),
      error: (err) => {
        this.erro.set(err?.error?.erro ?? 'Falha ao iniciar separação.');
        this.carregando.set(false);
      },
    });
  }

  ngOnDestroy(): void {
    this.sessaoSub?.unsubscribe();
  }

  private aguardarSessaoPronta(sessaoId: string): void {
    this.sessaoSub = interval(700)
      .pipe(
        switchMap(() => this.separacaoService.buscarSessao(this.tenantAtual, sessaoId)),
        filter((s) => s.status !== 'carregando'),
        first(),
        timeout(60000),
      )
      .subscribe({
        next: (sessao) => {
          if (sessao.status !== 'pronta') {
            this.erro.set(sessao.erro ?? `Sessão terminou em status '${sessao.status}'.`);
            this.carregando.set(false);
            return;
          }
          this.sessaoIdAtual = sessaoId;
          this.separacaoService.buscarItens(this.tenantAtual, sessaoId).subscribe({
            next: (itens) => {
              const mapeados = itens.map(mapearItem);
              // Itens que já vieram com qtd_conferida_local >= esperado (conferência
              // parcial anterior, tela reaberta) precisam nascer em CONFERIDOS (ok ou
              // critical/divergente), não ficar presos em PENDENTES mostrando "000".
              this.items.set(mapeados.filter((i) => i.status === 'pending'));
              this.conferred.set(mapeados.filter((i) => i.status !== 'pending'));
              this.carregando.set(false);
            },
            error: (err) => {
              this.erro.set(err?.error?.erro ?? 'Falha ao carregar itens.');
              this.carregando.set(false);
            },
          });
        },
        error: () => {
          this.erro.set('Tempo esgotado aguardando o carregamento da sessão.');
          this.carregando.set(false);
        },
      });
  }

  /** Imagem do último produto identificado — carregada junto pro onConferido não perder ela ao confirmar. */
  private ultimaImagemIdentificada: string | null = null;

  /**
   * O scan-bar já identificou o produto (POST /identificar) — aqui só
   * mostra uma prévia (foto + nome) no painel de última leitura, ANTES da
   * quantidade ser confirmada. Não mexe nas listas de pendentes/conferidos.
   */
  onIdentificado(evento: ProdutoIdentificadoEvento): void {
    this.ultimaImagemIdentificada = evento.imagemUrl;
    const pendente = this.items().find((it) => it.code === String(evento.codprod));
    if (!pendente) return;
    this.lastScan.set({ ...pendente, imagemUrl: evento.imagemUrl });
  }

  /**
   * O scan-bar já resolveu (identificar) e confirmou (conferir) no backend
   * sozinho — aqui só reflete o resultado na UI (mover pendente→conferido,
   * ou atualizar a quantidade parcial se ainda não bateu o total).
   */
  onConferido(resultado: ItemConferido): void {
    const idx = this.items().findIndex((it) => it.seq === resultado.sequencia);
    if (idx === -1) {
      // Já não está mais entre os pendentes (chegou na quantidade total num
      // bipe anterior) — o próprio backend recalculou certo, só não há mais
      // o que mostrar como "pendente" pra esse item.
      return;
    }

    const item = this.items()[idx];
    const scannedNovo = Number(resultado.qtdConferidaLocal);
    const divergente = scannedNovo > item.expected;
    const atualizado: ConferenciaItem = {
      ...item,
      scanned: scannedNovo,
      status: divergente ? 'critical' : scannedNovo >= item.expected ? 'ok' : 'pending',
      divergenceReason: divergente ? 'QTD. DIVERGENTE' : undefined,
      imagemUrl: this.ultimaImagemIdentificada,
    };

    // Só sai da lista de pendentes quando atingiu o total — permite
    // continuar bipando o mesmo item em partes.
    if (scannedNovo >= item.expected) {
      this.items.update((arr) => arr.filter((_, i) => i !== idx));
      this.conferred.update((c) => [atualizado, ...c]);
    } else {
      this.items.update((arr) => arr.map((i, i2) => (i2 === idx ? atualizado : i)));
    }
    this.lastScan.set(atualizado);
  }

  /** Só mostra o erro no painel de última leitura — não é um item conferido de verdade, não entra na lista de CONFERIDOS. */
  onNaoEncontrado(codigo: string): void {
    this.lastScan.set({
      seq: 0,
      code: codigo,
      name: 'Código de barras não encontrado nos itens deste pedido',
      control: '—',
      expected: 0,
      scanned: 0,
      status: 'critical',
      divergenceReason: 'CÓDIGO NÃO ENCONTRADO',
    });
  }

  /** Desfaz TUDO que foi conferido desse item (produto+controle) — corrige bipe errado, volta pra pendentes do zero. */
  onDevolver(item: ConferenciaItem): void {
    if (!this.sessaoIdAtual) return;
    const controleReal = item.control === '—' ? '' : item.control;

    this.separacaoService.devolverItem(this.tenantAtual, this.sessaoIdAtual, Number(item.code), controleReal).subscribe({
      next: () => {
        this.conferred.update((arr) => arr.filter((i) => !(i.code === item.code && i.control === item.control)));
        const zerado: ConferenciaItem = { ...item, scanned: 0, status: 'pending', divergenceReason: undefined };
        this.items.update((arr) => [...arr, zerado].sort((a, b) => a.seq - b.seq));
      },
      error: () => {
        // Falha ao devolver — deixa como está, operador pode tentar de novo.
      },
    });
  }

  onResolver(item: ConferenciaItem): void {
    console.log('Resolver divergência:', item.code);
  }

  onVoltar(): void {
    this.router.navigate(['/fila-tarefas']);
  }

  onConfirmar(): void {
    console.log('Confirmar conferência');
  }
}
