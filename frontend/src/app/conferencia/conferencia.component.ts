import { Component, OnDestroy, OnInit, ViewChild, computed, inject, signal } from '@angular/core';
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
import {
  ConcluirEtapaResultado,
  FinalizarResultado,
  ItemConferido,
  ItemSeparacao,
  SessaoEtapa,
  TopFaturamento,
} from '../separacao/separacao.model';
import { OqLiberacaoCorteModalComponent } from '../liberacao-corte/oq-liberacao-corte-modal/oq-liberacao-corte-modal.component';
import { FormsModule } from '@angular/forms';
import { Tarefa, rotuloTipoSeparacao } from '../fila-tarefas/tarefa.model';
import { AuthService } from '../auth/auth.service';
import { OqIconComponent } from '../shared/icons/oq-icon.component';
import { SomFeedbackService } from '../shared/som-feedback.service';

/** Mapeia o item real (vindo de app.separacao_itens) pro modelo visual do painel — mesmo shape do mock anterior. */
function mapearItem(item: ItemSeparacao): ConferenciaItem {
  const expected = Number(item.qtdNeg);
  const scanned = Number(item.qtdConferidaLocal);
  const divergente = scanned > expected;
  const unidadePadrao = item.unidadePadrao?.trim() || item.codvol?.trim() || undefined;
  const unidadeComercial = item.unidadeComercial?.trim() || unidadePadrao;
  return {
    seq: item.sequencia,
    code: String(item.codprod),
    name: item.descricaoProduto || `Produto ${item.codprod}`,
    control: item.controle.trim() || '—',
    expected,
    scanned,
    status: divergente ? 'critical' : scanned >= expected ? 'ok' : 'pending',
    divergenceReason: divergente ? 'QTD. DIVERGENTE' : item.foraPedido ? 'FORA DO PEDIDO' : undefined,
    usaConfPeso: item.usaConfPeso,
    foraPedido: item.foraPedido,
    tipoSeparacao: item.tipoSeparacao,
    unidadePadrao,
    unidadeComercial,
    quantidadeComercial: item.quantidadeComercial != null ? Number(item.quantidadeComercial) : undefined,
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
    OqIconComponent,
    OqLiberacaoCorteModalComponent,
    FormsModule,
  ],
  templateUrl: './conferencia.component.html',
  styleUrl: './conferencia.component.scss',
})
export class ConferenciaComponent implements OnInit, OnDestroy {
  private readonly route = inject(ActivatedRoute);
  private readonly router = inject(Router);
  private readonly separacaoService = inject(SeparacaoService);
  private readonly authService = inject(AuthService);
  private readonly som = inject(SomFeedbackService);
  private sessaoSub?: Subscription;

  @ViewChild(OqScanBarComponent) private scanBar?: OqScanBarComponent;

  get tenantAtual(): string {
    return this.authService.obterTenantSlug() ?? '';
  }

  carregando = signal(true);
  erro = signal<string | null>(null);

  nf = '—';
  parceiro = '—';
  vendedor = '—';
  numeroUnico = '—';

  private readonly items = signal<ConferenciaItem[]>([]);
  private readonly conferred = signal<ConferenciaItem[]>([]);
  readonly lastScan = signal<ConferenciaItem | null>(null);

  /** Sessão atual — usado pelo scan-bar (identificar/conferir) e pelo devolver-item. */
  sessaoIdAtual: string | null = null;
  /** CCO.OBTERQTDBALANCA — rotina de peso portada do projeto base. */
  obterQtdBalanca: string | null = null;
  /**
   * Módulo por-tenant "conferência segmentada" (snapshot da sessão). Ponto de
   * ramificação: só um cliente tem o módulo ligado; o comportamento em si
   * (dividir a conferência) ainda não está implementado.
   */
  conferenciaSegmentada = false;
  /** CCO.FATAOCONCLUIR — 'S' = oferecer faturamento após finalizar. */
  fatAoConcluir: string | null = null;

  // ─── Fluxo pós-finalização ───────────────────────────────────────────────
  /** Modal de liberação de corte (quando o cortar deixou a conferência em STATUS='C'). */
  readonly mostrarModalLiberacaoCorte = signal(false);
  nuconfLiberacao: number | null = null;
  /** Modal de faturamento (CCO FATAOCONCLUIR='S'). */
  readonly mostrarModalFaturamento = signal(false);
  readonly topsFaturamento = signal<TopFaturamento[]>([]);
  codTipOperFaturamento: number | null = null;
  readonly faturando = signal(false);
  readonly erroFaturamento = signal<string | null>(null);
  readonly sucessoFaturamento = signal(false);
  /** Painel "Conferência finalizada" (com botão de imprimir etiquetas). */
  readonly mostrarPainelFinalizada = signal(false);

  /** Modo simplificado (sem dimensão) — só a quantidade de volumes, nativo do Sankhya. */
  readonly volume = signal(0);

  readonly pendingItems = computed(() => this.items());
  readonly pendingCount = computed(() => this.items().length);
  readonly conferredCount = computed(() => this.conferred().length);
  readonly divergenceCount = computed(() => this.conferred().filter((i) => i.status === 'critical').length);
  private readonly finalizando = signal(false);
  /** Botão "Finalizar Conferência" fica sempre disponível — quem decide o que fazer com a divergência é a CCO do Sankhya, não um bloqueio nosso. */
  readonly canConfirm = computed(() => !this.finalizando());
  /** Falta (pendente) ou sobra (crítico) — nos dois casos o Sankhya decide o ajuste via CCO (PROCEDCORTE/GERARPEDCOMPL), mas o operador precisa confirmar ciente disso. */
  readonly temDivergencia = computed(() => this.pendingCount() > 0 || this.divergenceCount() > 0);
  readonly mostrarModalDivergencia = signal(false);
  readonly mostrarModalCancelar = signal(false);
  readonly mostrarAtalhos = signal(false);
  private cancelando = false;

  /**
   * Aba ativa no layout de celular (≤639px) — só uma lista por vez ocupa a
   * tela. Sem efeito em tablet/desktop, onde as duas listas aparecem juntas.
   */
  readonly abaMobile = signal<'pendentes' | 'conferidos'>('pendentes');
  trocarAba(aba: 'pendentes' | 'conferidos'): void {
    this.abaMobile.set(aba);
  }

  /**
   * Flags "Comportamento da interface" da CCO (V28) — gateiam os painéis da
   * tela. Só 'N' explícito esconde; ausente/'S'/outro = mostra (fail-safe).
   */
  readonly exibirProd = signal(true);
  readonly exibirQtd = signal(true);
  readonly exibirProdConf = signal(true);
  readonly exibirQtdConf = signal(true);
  readonly exibirImgProd = signal(true);

  /** Aba realmente ativa no celular — respeita quais painéis a CCO deixou visíveis. */
  readonly abaAtiva = computed<'pendentes' | 'conferidos'>(() => {
    if (!this.exibirProd()) return 'conferidos';
    if (!this.exibirProdConf()) return 'pendentes';
    return this.abaMobile();
  });
  /** Abas só aparecem quando os dois painéis de lista estão visíveis. */
  readonly temAbas = computed(() => this.exibirProd() && this.exibirProdConf());
  /** Coluna direita existe se há lista de conferidos OU painel de imagem. */
  readonly temColunaDireita = computed(() => this.exibirProdConf() || this.exibirImgProd());
  /** Body vira uma coluna só quando um dos lados está totalmente escondido. */
  readonly umaColunaSo = computed(() => !this.exibirProd() || !this.temColunaDireita());

  // ─── Conferência por etapa (V29) ────────────────────────────────────────
  /** Valor cru de ?etapa= (lido no ngOnInit, ativado só quando a sessão confirma segmentação). */
  private etapaParam: number | null = null;
  /** Tipo de separação da etapa sendo conferida (null = conferência normal, não segmentada). */
  readonly etapaAtual = signal<number | null>(null);
  readonly etapasSessao = signal<SessaoEtapa[]>([]);
  /** true = tela está conferindo UMA etapa. */
  readonly modoEtapa = computed(() => this.etapaAtual() != null);
  readonly rotuloEtapaAtual = computed(() => {
    const t = this.etapaAtual();
    return t == null ? '' : rotuloTipoSeparacao(t);
  });
  /** true = sessão segmentada, mais de uma etapa pendente e nenhuma escolhida — mostra o seletor. */
  readonly precisaEscolherEtapa = computed(() => this.etapaAtual() == null && this.etapasSessao().length > 0);
  /** Etapas pra oferecer no seletor (pendentes primeiro). */
  readonly etapasParaEscolher = computed(() =>
    [...this.etapasSessao()]
      .sort((a, b) => a.tipoSeparacao - b.tipoSeparacao)
      .map((e) => ({ tipo: e.tipoSeparacao, rotulo: rotuloTipoSeparacao(e.tipoSeparacao), concluida: e.status === 'C' })),
  );
  private concluindoEtapa = false;

  escolherEtapa(tipo: number): void {
    this.etapaAtual.set(tipo);
    if (this.sessaoIdAtual) this.recarregarItens(this.sessaoIdAtual);
    // reflete na URL sem recarregar (permite F5 / compartilhar link da etapa)
    this.router.navigate([], { relativeTo: this.route, queryParams: { etapa: tipo }, replaceUrl: true });
  }

  readonly sortedConferred = computed(() =>
    [...this.conferred()].sort((a, b) => {
      if (a.status === 'critical' && b.status !== 'critical') return -1;
      if (b.status === 'critical' && a.status !== 'critical') return 1;
      return 0;
    }),
  );

  ngOnInit(): void {
    const nunota = Number(this.route.snapshot.paramMap.get('nunota'));
    const etapaRaw = Number(this.route.snapshot.queryParamMap.get('etapa'));
    this.etapaParam = Number.isFinite(etapaRaw) && etapaRaw >= 1 && etapaRaw <= 3 ? etapaRaw : null;
    const tarefa = history.state?.tarefa as Tarefa | undefined;
    if (tarefa) {
      this.nf = tarefa.nf;
      this.parceiro = tarefa.cliente;
      this.vendedor = tarefa.responsavel;
    }
    this.numeroUnico = nunota ? String(nunota) : '—';

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

  /**
   * Recarrega itens do backend e re-divide pendentes/conferidos. É a fonte da
   * verdade: a lista de pendentes é sempre o pedido negociado ainda não
   * conferido, nunca o resultado da bipagem. Usado ao abrir e ao devolver.
   */
  private recarregarItens(sessaoId: string, aoTerminar?: () => void): void {
    this.separacaoService.buscarItens(this.tenantAtual, sessaoId).subscribe({
      next: (itens) => {
        // Fora do pedido sem nada bipado não aparece em lugar nenhum (foi só
        // identificado e não conferido) — a lista de pendentes é o pedido.
        let mapeados = itens.map(mapearItem).filter((i) => !(i.foraPedido && i.scanned === 0));
        // Conferência por etapa (V29): a tela só enxerga os itens do tipo de separação da etapa.
        const etapa = this.etapaAtual();
        if (etapa != null) mapeados = mapeados.filter((i) => (i.tipoSeparacao ?? 1) === etapa);
        this.items.set(mapeados.filter((i) => i.status === 'pending'));
        this.conferred.set(mapeados.filter((i) => i.status !== 'pending'));
        aoTerminar?.();
      },
      error: (err) => {
        this.erro.set(err?.error?.erro ?? 'Falha ao carregar itens.');
        aoTerminar?.();
      },
    });
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
          this.obterQtdBalanca = sessao.obterQtdBalanca;
          this.conferenciaSegmentada = sessao.conferenciaSegmentada;
          this.fatAoConcluir = sessao.fatAoConcluir;
          this.exibirProd.set(sessao.exibirProd !== 'N');
          this.exibirQtd.set(sessao.exibirQtd !== 'N');
          this.exibirProdConf.set(sessao.exibirProdConf !== 'N');
          this.exibirQtdConf.set(sessao.exibirQtdConf !== 'N');
          this.exibirImgProd.set(sessao.exibirImgProd !== 'N');
          this.separacaoService.buscarVolume(this.tenantAtual, sessaoId).subscribe({
            next: (v) => this.volume.set(v.quantidade),
            error: () => {
              // Não bloqueia a conferência — operador ainda pode ajustar depois via o próprio stepper.
            },
          });

          if (sessao.conferenciaSegmentada) {
            // Resolve a etapa ativa ANTES de listar os itens (o filtro por etapa depende dela).
            this.separacaoService.buscarEtapas(this.tenantAtual, sessaoId).subscribe({
              next: (etapas) => {
                this.etapasSessao.set(etapas);
                const pendentes = etapas.filter((e) => e.status === 'P').map((e) => e.tipoSeparacao);
                if (this.etapaParam != null && etapas.some((e) => e.tipoSeparacao === this.etapaParam)) {
                  this.etapaAtual.set(this.etapaParam); // etapa pedida existe (pendente ou já concluída)
                } else if (pendentes.length === 1) {
                  this.etapaAtual.set(pendentes[0]); // sem ?etapa= e só sobra uma → assume ela
                }
                // >1 etapa pendente e sem ?etapa= válido → etapaAtual null → template mostra o seletor.
                this.recarregarItens(sessaoId, () => this.carregando.set(false));
              },
              error: () => this.recarregarItens(sessaoId, () => this.carregando.set(false)),
            });
          } else {
            this.recarregarItens(sessaoId, () => this.carregando.set(false));
          }
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

    // A imagem não vem mais no /identificar (evita travar o 1º Tab). Se não
    // veio do cache, busca à parte e atualiza a prévia quando chegar.
    if (evento.imagemUrl == null) {
      this.separacaoService.buscarImagemProduto(this.tenantAtual, evento.codprod).subscribe({
        next: ({ imagemBase64 }) => {
          if (imagemBase64 == null) return;
          this.ultimaImagemIdentificada = imagemBase64;
          const atual = this.lastScan();
          if (atual && atual.code === String(evento.codprod)) {
            this.lastScan.set({ ...atual, imagemUrl: imagemBase64 });
          }
        },
        error: () => {},
      });
    }
  }

  /**
   * O scan-bar já resolveu (identificar) e confirmou (conferir) no backend
   * sozinho — aqui só reflete o resultado na UI (mover pendente→conferido,
   * ou atualizar a quantidade parcial se ainda não bateu o total).
   */
  onConferido(resultado: ItemConferido): void {
    const controleResultado = resultado.controle.trim();
    let idx = this.items().findIndex((it) => it.seq === resultado.sequencia);
    if (idx === -1) {
      // Fallback: casa por produto + controle. Cobre o caso do item pesável /
      // produto com mais de uma linha, onde a SEQUENCIA que o /conferir devolve
      // (última linha do grupo) pode não ser a do item mostrado como pendente.
      idx = this.items().findIndex(
        (it) => it.code === String(resultado.codprod) && (it.control === '—' ? '' : it.control) === controleResultado,
      );
    }
    if (idx === -1) {
      const sc = Number(resultado.qtdConferidaLocal);
      const combina = (it: ConferenciaItem) =>
        it.code === String(resultado.codprod) && (it.control === '—' ? '' : it.control) === controleResultado;
      const jaConf = this.conferred().findIndex(combina);
      if (jaConf !== -1) {
        // Re-conferência de item já conferido (ex.: nova pesagem) — atualiza a qtd.
        const c = this.conferred()[jaConf];
        const div = sc > c.expected;
        this.conferred.update((arr) =>
          arr.map((it, i) =>
            i === jaConf
              ? { ...it, scanned: sc, status: div ? 'critical' : 'ok', divergenceReason: div ? 'QTD. DIVERGENTE' : undefined }
              : it,
          ),
        );
        this.lastScan.set(this.conferred()[jaConf]);
      } else {
        // Item que não estava na tela — produto fora do pedido, adicionado no
        // /identificar (qtd_neg=0, divergente por definição). Entra em conferidos.
        const novo: ConferenciaItem = {
          seq: resultado.sequencia,
          code: String(resultado.codprod),
          name: resultado.descricaoProduto || `Produto ${resultado.codprod}`,
          control: controleResultado || '—',
          expected: 0,
          scanned: sc,
          status: 'critical',
          divergenceReason: 'FORA DO PEDIDO',
          foraPedido: true,
          tipoSeparacao: this.etapaAtual() ?? 1,
          imagemUrl: this.ultimaImagemIdentificada,
        };
        this.conferred.update((c) => [novo, ...c]);
        this.lastScan.set(novo);
      }
      return;
    }

    const item = this.items()[idx];
    const scannedNovo = Number(resultado.qtdConferidaLocal);
    // Item pesável: QUALQUER peso > 0 conclui o item (o peso raramente bate o
    // nominal exato — mesma regra do projeto base). Não-pesável: precisa bater o
    // total pra sair de pendentes (permite bipar em partes).
    const concluido = item.usaConfPeso ? scannedNovo > 0 : scannedNovo >= item.expected;
    const divergente = item.usaConfPeso
      ? Math.abs(scannedNovo - item.expected) > 0.001
      : scannedNovo > item.expected;
    const atualizado: ConferenciaItem = {
      ...item,
      scanned: scannedNovo,
      status: divergente ? 'critical' : concluido ? 'ok' : 'pending',
      divergenceReason: divergente ? 'QTD. DIVERGENTE' : undefined,
      imagemUrl: this.ultimaImagemIdentificada,
    };

    if (concluido) {
      this.items.update((arr) => arr.filter((_, i) => i !== idx));
      this.conferred.update((c) => [atualizado, ...c]);
      // Última pendência bipada — lista zerou. Som distinto do "ok" comum
      // (já tocado no scan-bar), mesma ideia do projeto base.
      if (this.pendingCount() === 0) {
        this.som.tocar('finalizado');
        // No celular, salta pra aba de conferidos — o operador vê o resultado
        // sem precisar trocar de aba na mão.
        this.abaMobile.set('conferidos');
      }
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

  /** Erro ao confirmar quantidade (ex.: excede o pendente e a CCO do NUCCO não permite — QTDAMAIOR != 'D'). */
  onErroConferir(mensagem: string): void {
    this.lastScan.set({
      seq: 0,
      code: '—',
      name: mensagem,
      control: '—',
      expected: 0,
      scanned: 0,
      status: 'critical',
      divergenceReason: 'QUANTIDADE NÃO PERMITIDA',
    });
  }

  /** Desfaz TUDO que foi conferido desse item (produto+controle) — corrige bipe errado, volta pra pendentes do zero. */
  onDevolver(item: ConferenciaItem): void {
    if (!this.sessaoIdAtual) return;
    const controleReal = item.control === '—' ? '' : item.control;

    const sessaoId = this.sessaoIdAtual;
    this.separacaoService.devolverItem(this.tenantAtual, sessaoId, Number(item.code), controleReal).subscribe({
      // Recarrega do backend — que já apagou o item se era fora do pedido, ou
      // zerou se era linha do pedido. A tela reflete o estado real, sem palpite.
      next: () => this.recarregarItens(sessaoId),
      error: () => {
        // Falha ao devolver — deixa como está, operador pode tentar de novo.
      },
    });
  }

  /** Grava o total de volumes nativamente no Sankhya (TGFCON2.QTDVOL) — relê o valor confirmado, não assume otimisticamente. */
  onVolumeChange(quantidade: number): void {
    if (!this.sessaoIdAtual) return;
    this.separacaoService.definirVolume(this.tenantAtual, this.sessaoIdAtual, quantidade).subscribe({
      next: (v) => this.volume.set(v.quantidade),
      error: () => {
        // Falha ao gravar — mantém o valor anterior, operador pode tentar de novo.
      },
    });
  }

  onCancelarPedido(): void {
    this.mostrarModalCancelar.set(true);
  }

  onFecharModalCancelar(): void {
    this.mostrarModalCancelar.set(false);
  }

  onConfirmarCancelamento(): void {
    if (!this.sessaoIdAtual || this.cancelando) return;
    this.cancelando = true;
    this.separacaoService.cancelar(this.tenantAtual, this.sessaoIdAtual).subscribe({
      next: () => {
        this.cancelando = false;
        this.mostrarModalCancelar.set(false);
        this.router.navigate(['/fila-tarefas']);
      },
      error: (err) => {
        this.cancelando = false;
        this.mostrarModalCancelar.set(false);
        this.erro.set(err?.error?.erro ?? 'Falha ao cancelar o pedido.');
      },
    });
  }

  onResolver(item: ConferenciaItem): void {
    console.log('Resolver divergência:', item.code);
  }

  /**
   * Clique num item de pendentes — manda o scan-bar identificar o produto por
   * CODPROD (sem bipar/digitar). O operador cai direto no controle ou na
   * quantidade; no mobile elimina a digitação do código de barras.
   */
  onSelecionarPendente(item: ConferenciaItem): void {
    if (item.foraPedido) return;
    const codprod = Number(item.code);
    if (!Number.isFinite(codprod)) return;
    this.scanBar?.identificarPorCodprod(codprod);
  }

  onVoltar(): void {
    this.router.navigate(['/fila-tarefas']);
  }

  onConfirmar(): void {
    if (!this.sessaoIdAtual || this.finalizando()) return;
    // Conferência por etapa: o botão conclui a etapa (a última fecha a nota no Sankhya).
    if (this.modoEtapa()) {
      this.concluirEtapaAgora(false);
      return;
    }
    if (this.temDivergencia()) {
      this.mostrarModalDivergencia.set(true);
      return;
    }
    this.executarFinalizacao();
  }

  /** "Cortar" e "Finalizar divergente" chamam a mesma ação — quem decide o ajuste é a CCO do Sankhya (PROCEDCORTE/GERARPEDCOMPL), não o botão escolhido aqui. */
  onConfirmarDivergente(): void {
    this.mostrarModalDivergencia.set(false);
    if (this.modoEtapa()) {
      this.concluirEtapaAgora(true);
      return;
    }
    this.executarFinalizacao();
  }

  /**
   * Conclui a etapa atual. `409` com pendentes → abre o modal de divergência
   * ("Concluir mesmo assim?"). Última etapa → o backend finaliza a nota no
   * Sankhya e devolve a cadeia de corte/faturamento (aposFinalizacao).
   */
  private concluirEtapaAgora(manterPendente: boolean): void {
    const tipo = this.etapaAtual();
    if (!this.sessaoIdAtual || tipo == null || this.concluindoEtapa || this.finalizando()) return;
    this.concluindoEtapa = true;
    this.finalizando.set(true);
    const operador = this.authService.usuario()?.email ?? 'operador';
    this.separacaoService
      .concluirEtapa(this.tenantAtual, this.sessaoIdAtual, { tipoSeparacao: tipo, manterPendente, operador })
      .subscribe({
        next: (res: ConcluirEtapaResultado) => {
          this.concluindoEtapa = false;
          this.finalizando.set(false);
          if (res.conferenciaFinalizada) {
            this.aposFinalizacao({ ok: true, aguardandoCorte: res.aguardandoCorte, nuconf: res.nuconf });
          } else {
            this.router.navigate(['/fila-tarefas']);
          }
        },
        error: (err) => {
          this.concluindoEtapa = false;
          this.finalizando.set(false);
          if (err?.status === 409 && typeof err?.error?.pendentes === 'number') {
            this.mostrarModalDivergencia.set(true);
            return;
          }
          this.erro.set(err?.error?.erro ?? 'Falha ao concluir a etapa.');
        },
      });
  }

  onCancelarDivergencia(): void {
    this.mostrarModalDivergencia.set(false);
  }

  private executarFinalizacao(): void {
    if (!this.sessaoIdAtual || this.finalizando()) return;
    this.finalizando.set(true);
    this.separacaoService.finalizar(this.tenantAtual, this.sessaoIdAtual).subscribe({
      next: (res) => {
        this.finalizando.set(false);
        this.aposFinalizacao(res);
      },
      error: (err) => {
        this.finalizando.set(false);
        this.erro.set(err?.error?.erro ?? 'Falha ao finalizar a conferência.');
      },
    });
  }

  /** Cadeia pós-finalização: liberação de corte → faturamento → painel "finalizada". */
  private aposFinalizacao(res: FinalizarResultado): void {
    if (res.aguardandoCorte && res.nuconf != null) {
      this.nuconfLiberacao = res.nuconf;
      this.mostrarModalLiberacaoCorte.set(true);
      return;
    }
    if (this.fatAoConcluir === 'S') {
      this.abrirModalFaturamento();
      return;
    }
    this.mostrarPainelFinalizada.set(true);
  }

  onLiberacaoCorteFechada(): void {
    this.mostrarModalLiberacaoCorte.set(false);
    // Segue a cadeia: faturamento (se a CCO pedir) ou o painel final.
    if (this.fatAoConcluir === 'S') this.abrirModalFaturamento();
    else this.mostrarPainelFinalizada.set(true);
  }

  private abrirModalFaturamento(): void {
    if (!this.sessaoIdAtual) return;
    this.erroFaturamento.set(null);
    this.sucessoFaturamento.set(false);
    this.codTipOperFaturamento = null;
    this.separacaoService.topsFaturamento(this.tenantAtual, this.sessaoIdAtual).subscribe({
      next: (tops) => {
        this.topsFaturamento.set(tops);
        this.codTipOperFaturamento = tops.length === 1 ? tops[0].codTipOper : null;
        this.mostrarModalFaturamento.set(true);
      },
      error: () => {
        // Sem TOPs / falha ao listar — pula o faturamento e segue pro painel final.
        this.mostrarPainelFinalizada.set(true);
      },
    });
  }

  confirmarFaturamento(): void {
    if (!this.sessaoIdAtual || this.codTipOperFaturamento == null || this.faturando()) return;
    this.faturando.set(true);
    this.erroFaturamento.set(null);
    this.separacaoService.faturar(this.tenantAtual, this.sessaoIdAtual, this.codTipOperFaturamento).subscribe({
      next: () => {
        this.faturando.set(false);
        this.sucessoFaturamento.set(true);
      },
      error: (err) => {
        this.faturando.set(false);
        this.erroFaturamento.set(err?.error?.erro ?? 'Falha ao faturar a nota.');
      },
    });
  }

  fecharModalFaturamento(): void {
    this.mostrarModalFaturamento.set(false);
    this.mostrarPainelFinalizada.set(true);
  }

  imprimirEtiquetas(): void {
    if (!this.sessaoIdAtual) return;
    window.open(`/etiquetas/${this.sessaoIdAtual}`, '_blank');
  }

  sairParaFila(): void {
    this.router.navigate(['/fila-tarefas']);
  }
}
