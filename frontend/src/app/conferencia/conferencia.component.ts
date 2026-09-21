import { Component, ElementRef, OnDestroy, OnInit, ViewChild, computed, inject, signal } from '@angular/core';
import { ActivatedRoute, Router } from '@angular/router';
import { Subscription, interval } from 'rxjs';
import { filter, first, switchMap, timeout } from 'rxjs/operators';
import { OqConferenciaHeaderComponent } from './oq-conferencia-header/oq-conferencia-header.component';
import { OqScanBarComponent, ProdutoIdentificadoEvento } from './oq-scan-bar/oq-scan-bar.component';
import { OqPendingListComponent } from './oq-pending-list/oq-pending-list.component';
import { OqConferredListComponent } from './oq-conferred-list/oq-conferred-list.component';
import { OqLastScanPanelComponent } from './oq-last-scan-panel/oq-last-scan-panel.component';
import { OqConferenciaFooterComponent } from './oq-conferencia-footer/oq-conferencia-footer.component';
import { ConferenciaItem, ItemStatus } from './conferencia.model';
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
import { OqSpinnerComponent } from '../shared/icons/oq-spinner.component';
import { OqSkeletonComponent } from '../shared/oq-skeleton/oq-skeleton.component';
import { SomFeedbackService } from '../shared/som-feedback.service';

/**
 * Tolerância de peso — só vale pra divergência A MENOR (conferido < esperado):
 * item pesável só entra em divergência se pesar mais de 5% abaixo do esperado.
 * A MAIOR (conferido > esperado) NUNCA diverge — pesar mais que o negociado
 * não precisa de liberação nem alerta, por maior que seja o excesso. Mesma
 * regra da auto-liberação de corte por peso
 * (LiberacaoCorteService.autoLiberarPesoDentroTolerancia, backend).
 */
const TOLERANCIA_PESO = 0.05;

/** Desvio |conferido - esperado| / esperado. Retorna 0 quando não há esperado. */
function desvioPeso(scanned: number, expected: number): number {
  if (expected <= 0) return scanned > 0 ? 1 : 0;
  return Math.abs(scanned - expected) / expected;
}

/** Item pesável fora da tolerância — só a menor, além de 5%. A maior nunca diverge. */
function pesoForaDaTolerancia(scanned: number, expected: number): boolean {
  if (scanned > expected) return false;
  return desvioPeso(scanned, expected) > TOLERANCIA_PESO;
}

/**
 * Desvio SIGNED (conferido - esperado) / esperado, em %, 1 casa — positivo =
 * a maior, negativo = a menor. Diferente de desvioPeso() (sempre absoluto,
 * usado só pra decidir tolerância): este é pra EXIBIÇÃO, sempre calculado
 * pra todo item pesável já conferido, divergente ou não — vira uma
 * observação neutra na tela; só vira alerta vermelho quando
 * pesoForaDaTolerancia() for true.
 */
function desvioPesoPctSigned(scanned: number, expected: number): number {
  if (expected <= 0) return scanned > 0 ? 100 : 0;
  return Math.round(((scanned - expected) / expected) * 1000) / 10;
}

/**
 * Arredonda a 3 casas (mesma precisão exibida na tela). Comparações de
 * conferido/divergente são feitas nesse arredondamento: o operador confere o
 * valor QUE VÊ (ex.: 2,083), e o esperado real pode ter mais casas
 * (25 BI ÷ 12 = 2,08333) — sem isso a conferência nunca "fecha" e o item fica
 * pendente, levando o operador a bipar de novo e duplicar a quantidade.
 */
function round3(n: number): number {
  return Math.round((n + Number.EPSILON) * 1000) / 1000;
}

/** Pesável: qualquer peso > 0 conclui. Não-pesável: só bate o total no arredondamento de 3 casas (ver round3). */
function estaConferido(item: { usaConfPeso?: boolean; scanned: number; expected: number }): boolean {
  return item.usaConfPeso ? item.scanned > 0 : round3(item.scanned) >= round3(item.expected);
}

/** Mapeia o item real (vindo de app.separacao_itens) pro modelo visual do painel — mesmo shape do mock anterior. */
function mapearItem(item: ItemSeparacao): ConferenciaItem {
  const expected = Number(item.qtdNeg);
  const scanned = Number(item.qtdConferidaLocal);
  const unidadePadrao = item.unidadePadrao?.trim() || item.codvol?.trim() || undefined;
  const unidadeComercial = item.unidadeComercial?.trim() || unidadePadrao;
  const conferido = estaConferido({ usaConfPeso: item.usaConfPeso, scanned, expected });
  // Pesável: só diverge acima de ±5% do esperado. Não-pesável: diverge se passou do esperado
  // (comparado no arredondamento de 3 casas — ver round3).
  const divergePeso = item.usaConfPeso && scanned > 0 && pesoForaDaTolerancia(scanned, expected);
  const divergeQtd = !item.usaConfPeso && round3(scanned) > round3(expected);
  const divergente = divergePeso || divergeQtd;
  return {
    seq: item.sequencia,
    code: String(item.codprod),
    name: item.descricaoProduto || `Produto ${item.codprod}`,
    control: item.controle.trim() || '—',
    expected,
    scanned,
    status: divergente ? 'critical' : conferido ? 'ok' : 'pending',
    divergenceReason: divergePeso
      ? 'PESO FORA DA TOLERÂNCIA'
      : divergeQtd
        ? 'QTD. DIVERGENTE'
        : item.foraPedido
          ? 'FORA DO PEDIDO'
          : undefined,
    divergenciaPeso: divergePeso || undefined,
    // Observação de peso: SEMPRE presente pra item pesável já conferido (não só
    // quando diverge) — vira alerta vermelho só quando divergenciaPeso é true.
    desvioPesoPct: item.usaConfPeso && scanned > 0 ? desvioPesoPctSigned(scanned, expected) : undefined,
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
    OqSpinnerComponent,
    OqSkeletonComponent,
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
  @ViewChild('inputCrachaOperador') private inputCrachaOperador?: ElementRef<HTMLInputElement>;

  get tenantAtual(): string {
    return this.authService.obterTenantSlug() ?? '';
  }

  carregando = signal(true);
  erro = signal<string | null>(null);

  // ─── Bipagem de crachá na entrada (V33) ─────────────────────────────────
  /**
   * Só exige bipar quem está logado é uma conta de ESTAÇÃO (PC fixo, ex.:
   * "Stage1") — aí sim o login não identifica quem está de fato conferindo.
   * Login pessoal normal já identifica; não tem por que bipar de novo.
   */
  readonly exigeCracha = this.authService.usuario()?.perfil === 'ESTACAO';
  /** sessaoId já existe (iniciar() respondeu) — usado pelo template pra saber se já pode mostrar o campo de crachá. */
  sessaoIdParaOperador: string | null = null;
  readonly operadorIdentificado = signal(!this.exigeCracha);
  readonly nomeOperador = signal<string | null>(null);
  readonly identificandoOperador = signal(false);
  readonly erroOperador = signal<string | null>(null);
  crachaoOperador = '';

  nf = '—';
  parceiro = '—';
  vendedor = '—';
  numeroUnico = '—';

  private readonly items = signal<ConferenciaItem[]>([]);
  private readonly conferred = signal<ConferenciaItem[]>([]);
  /**
   * Todos os itens da SESSÃO (sem o filtro por etapa que `items`/`conferred`
   * aplicam) — necessário pra decisão de divergência na última etapa e pra
   * tabela do pop-up de finalização divergente: um item divergente ficou
   * numa etapa JÁ CONCLUÍDA (ex.: Secos) não aparece mais em `items`/
   * `conferred` depois que o operador passa pra etapa seguinte, mas a
   * divergência continua pendente na nota inteira (bug real: nota 57525,
   * divergência de Secos só apareceu como "aguardando corte" ao finalizar em
   * Refrigerados, sem nunca passar pelo pop-up de Cortar/Finalizar Divergente).
   */
  private readonly todosItensMapeados = signal<ConferenciaItem[]>([]);
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
  /**
   * Item parcial aparece em pendentes E conferidos ao mesmo tempo (mostra o
   * progresso nos dois painéis) — pendingCount+conferredCount não serve mais
   * de total pra barra de progresso do rodapé (contaria o parcial 2x). Total
   * de verdade é a contagem de SEQUENCIA distintas nos dois; "feito" é só
   * quem já bateu o total (estaConferido), não quem só tem progresso parcial.
   */
  readonly totalItensCount = computed(
    () => new Set([...this.items().map((i) => i.seq), ...this.conferred().map((i) => i.seq)]).size,
  );
  readonly itensCompletosCount = computed(() => this.conferred().filter((i) => estaConferido(i)).length);
  readonly divergenceCount = computed(() => this.conferred().filter((i) => i.status === 'critical').length);
  /** Requisição de confirmar/concluir etapa em voo — cobre os dois fluxos (executarFinalizacao/concluirEtapaAgora). Público: o footer usa pra mostrar o spinner. */
  readonly finalizando = signal(false);
  /**
   * Botão "Finalizar Conferência" fica sempre disponível — quem decide o que
   * fazer com a divergência é a CCO do Sankhya, não um bloqueio nosso. Única
   * exceção: CCO.FORMACAOVOLUMES 'S'/'T'/'D' exige volume apontado antes de
   * liberar (backend também recusa, ver SeparacaoService.finalizar — isto é
   * só a UX, não a única barreira).
   */
  readonly canConfirm = computed(() => !this.finalizando() && (!this.exigeVolume() || this.volume() > 0));
  /** Falta (pendente) ou sobra (crítico) — nos dois casos o Sankhya decide o ajuste via CCO (PROCEDCORTE/GERARPEDCOMPL), mas o operador precisa confirmar ciente disso. */
  readonly temDivergencia = computed(() => this.pendingCount() > 0 || this.divergenceCount() > 0);
  /**
   * Itens divergentes da SESSÃO INTEIRA (todas as etapas, não só a atual) —
   * usado pra decidir se a ÚLTIMA etapa deve abrir o pop-up de finalização
   * divergente, e pra popular a tabela dele. Uma divergência criada numa
   * etapa já concluída (ex.: Secos) continua valendo até a nota fechar de
   * vez — `temDivergencia`/`itensDivergentes` (escopo só da etapa atual,
   * abaixo) não enxergam mais isso depois que o operador passa de etapa.
   *
   * AGRUPA por produto+controle antes de avaliar a divergência: o mesmo
   * produto+controle pode estar espalhado em mais de uma SEQUENCIA da nota
   * (entregas parciais — ver conferirItem no backend, V37).
   *
   * `todosItensMapeados` só é atualizado por um fetch completo
   * (`recarregarItens`), NUNCA pelas bipagens/pesagens locais (`onConferido`
   * patcheia `items`/`conferred` direto, sem re-fetch) — pra etapa ATUAL isso
   * fica desatualizado a cada scan (bug real confirmado: Queijo Mussarela
   * pesado a 25,38kg, dentro da tolerância, aparecia TAMBÉM como "25.2 / 0 /
   * A MENOR" porque a tabela ainda via o snapshot de ANTES da pesagem). Pra
   * etapa ATUAL usa `items`/`conferred` (sempre em dia); pra OUTRAS etapas
   * (já concluídas, congeladas — não recebem novas bipagens) usa o snapshot
   * de `todosItensMapeados`, que é reafirmado a cada troca de etapa (nova
   * navegação = novo fetch).
   */
  readonly itensDivergentesSessao = computed(() => {
    const etapaAtualVal = this.etapaAtual();
    const chaveDe = (i: ConferenciaItem) => `${i.code}|${i.control}`;
    const itensEtapaAtual = [...this.items(), ...this.conferred()];
    const chavesEtapaAtual = new Set(itensEtapaAtual.map(chaveDe));
    const vistos = new Set<number>();
    const itensEtapaAtualUnicos = itensEtapaAtual.filter((i) => (vistos.has(i.seq) ? false : (vistos.add(i.seq), true)));
    // Sessão sem conceito de etapa (não segmentada, ou recontagem — que agora
    // é sempre etapa única): não existe "outra etapa" nenhuma pra buscar no
    // snapshot antigo — items()/conferred() JÁ são a sessão inteira, sempre
    // em dia. Bug real confirmado (nota 57516, recontagem): usar o snapshot
    // aqui SOMAVA o mesmo item duas vezes (uma via items()/conferred(), outra
    // via todosItensMapeados() inteiro) — "Conferido" aparecia exatamente
    // metade do "Pedido" pra todo item, mesmo sem nenhuma divergência real.
    const itensOutrasEtapas = etapaAtualVal == null
      ? []
      : this.todosItensMapeados().filter((i) => !chavesEtapaAtual.has(chaveDe(i)));
    const todos = [...itensOutrasEtapas, ...itensEtapaAtualUnicos];

    const porGrupo = new Map<string, ConferenciaItem[]>();
    for (const item of todos) {
      const chave = chaveDe(item);
      (porGrupo.get(chave) ?? porGrupo.set(chave, []).get(chave)!).push(item);
    }
    const agregados: ConferenciaItem[] = [];
    for (const linhas of porGrupo.values()) {
      const base = linhas[0];
      const expected = linhas.reduce((acc, l) => acc + l.expected, 0);
      const scanned = linhas.reduce((acc, l) => acc + l.scanned, 0);
      const d = this.avaliarDivergencia({ usaConfPeso: base.usaConfPeso, expected }, scanned);
      if (d.status === 'ok') continue;
      agregados.push({
        ...base,
        expected,
        scanned,
        status: d.status,
        divergenceReason: base.foraPedido ? 'FORA DO PEDIDO' : d.divergenceReason,
        divergenciaPeso: d.divergenciaPeso,
        desvioPesoPct: d.desvioPesoPct,
      });
    }
    return agregados.sort((a, b) => a.name.localeCompare(b.name));
  });
  readonly temDivergenciaSessao = computed(() => this.itensDivergentesSessao().length > 0);
  readonly mostrarModalDivergencia = signal(false);
  /** Aviso simples (regra 5): divergência de não pesável numa etapa que NÃO é a última — só informa, não corta nem finaliza nada. */
  readonly mostrarModalAvisoEtapa = signal(false);
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

  /** CCO.FORMACAOVOLUMES 'S'/'T'/'D' — exige volume > 0 pra habilitar Confirmar/Concluir Etapa. */
  readonly exigeVolume = signal(false);

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
  /**
   * true = a etapa atual é a ÚLTIMA pendente (ou a sessão nem é segmentada —
   * aí só existe "a última"). Não assume ordem fixa: olha só quantas etapas
   * ainda estão 'P' na sessão. É esse sinal, não o tipo da etapa, que decide
   * se a divergência gera só um aviso (regra 5) ou a finalização divergente
   * de verdade com "Cortar"/"Finalizar divergente" (regra 6).
   */
  readonly ehUltimaEtapaPendente = computed(
    () => !this.modoEtapa() || this.etapasSessao().filter((e) => e.status === 'P').length <= 1,
  );
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
    if (this.sessaoIdAtual) {
      this.recarregarItens(this.sessaoIdAtual);
      this.carregarVolume(this.sessaoIdAtual); // contador de volume é por etapa
    }
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
      next: (resp) => {
        this.sessaoIdParaOperador = resp.sessaoId;
        if (this.exigeCracha) setTimeout(() => this.inputCrachaOperador?.nativeElement.focus());
        this.aguardarSessaoPronta(resp.sessaoId);
      },
      error: (err) => {
        this.erro.set(err?.error?.erro ?? 'Falha ao iniciar separação.');
        this.carregando.set(false);
      },
    });
  }

  /**
   * Bipagem de crachá pedida SEMPRE ao entrar nesta tela (V33) — mesmo se o
   * navegador já está logado (conta genérica/supervisor da estação, por
   * exemplo). Não troca a sessão/token; só marca "quem assumiu esta
   * conferência" — backend usa isso pra registrar autoria (concluida_por),
   * nunca o que vier solto no corpo.
   */
  identificarOperadorCracha(): void {
    const codigo = this.crachaoOperador.trim();
    if (!codigo || this.identificandoOperador() || !this.sessaoIdParaOperador) return;

    this.identificandoOperador.set(true);
    this.erroOperador.set(null);

    this.separacaoService.identificarOperador(this.sessaoIdParaOperador, codigo).subscribe({
      next: (res) => {
        this.crachaoOperador = '';
        this.identificandoOperador.set(false);
        this.nomeOperador.set(res.nome);
        this.operadorIdentificado.set(true);
      },
      error: (err) => {
        this.crachaoOperador = '';
        this.identificandoOperador.set(false);
        this.erroOperador.set(err?.error?.erro ?? 'Crachá não reconhecido.');
        setTimeout(() => this.inputCrachaOperador?.nativeElement.focus());
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
  /**
   * Status/divergência de um item conferido, aplicando a tolerância de ±5% pro
   * peso: item pesável só é divergente se o peso conferido sair de ±5% do
   * esperado; a divergência de peso tem indicador visual próprio (divergenciaPeso).
   */
  private avaliarDivergencia(
    item: Pick<ConferenciaItem, 'usaConfPeso' | 'expected'>,
    scanned: number,
  ): {
    conferido: boolean;
    status: ItemStatus;
    divergenceReason: string | undefined;
    divergenciaPeso: true | undefined;
    desvioPesoPct: number | undefined;
  } {
    const conferido = item.usaConfPeso ? scanned > 0 : round3(scanned) >= round3(item.expected);
    const divergePeso = !!item.usaConfPeso && scanned > 0 && pesoForaDaTolerancia(scanned, item.expected);
    const divergeQtd = !item.usaConfPeso && round3(scanned) > round3(item.expected);
    const divergente = divergePeso || divergeQtd;
    return {
      conferido,
      status: divergente ? 'critical' : conferido ? 'ok' : 'pending',
      divergenceReason: divergePeso ? 'PESO FORA DA TOLERÂNCIA' : divergeQtd ? 'QTD. DIVERGENTE' : undefined,
      divergenciaPeso: divergePeso || undefined,
      desvioPesoPct: item.usaConfPeso && scanned > 0 ? desvioPesoPctSigned(scanned, item.expected) : undefined,
    };
  }

  private recarregarItens(sessaoId: string, aoTerminar?: () => void): void {
    this.separacaoService.buscarItens(this.tenantAtual, sessaoId).subscribe({
      next: (itens) => {
        // Fora do pedido sem nada bipado não aparece em lugar nenhum (foi só
        // identificado e não conferido) — a lista de pendentes é o pedido.
        let mapeados = itens.map(mapearItem).filter((i) => !(i.foraPedido && i.scanned === 0));
        this.todosItensMapeados.set(mapeados);
        // Conferência por etapa (V29): a tela só enxerga os itens do tipo de separação da etapa.
        const etapa = this.etapaAtual();
        if (etapa != null) mapeados = mapeados.filter((i) => (i.tipoSeparacao ?? 1) === etapa);
        // Pendentes = ainda falta algo (mesmo que já tenha parte bipada — mostra o
        // restante). Conferidos = qualquer progresso > 0, completo ou parcial. Um
        // item parcial aparece nos DOIS ao mesmo tempo (pedido de dono do vendedor:
        // "bipei 5 de 10, quero ver 5 pendente E 5 conferido").
        this.items.set(mapeados.filter((i) => !estaConferido(i)));
        this.conferred.set(mapeados.filter((i) => i.scanned > 0));
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
          this.exigeVolume.set(!!sessao.formacaoVolumes && ['S', 'T', 'D'].includes(sessao.formacaoVolumes.trim().toUpperCase()));
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
                this.carregarVolume(sessaoId);
                this.recarregarItens(sessaoId, () => this.carregando.set(false));
              },
              error: () => this.recarregarItens(sessaoId, () => this.carregando.set(false)),
            });
          } else {
            this.carregarVolume(sessaoId);
            this.recarregarItens(sessaoId, () => this.carregando.set(false));
          }
        },
        error: () => {
          this.erro.set('Tempo esgotado aguardando o carregamento da sessão.');
          this.carregando.set(false);
        },
      });
  }

  /**
   * Carrega o contador de volumes: por ETAPA na conferência segmentada (cada
   * operador conta o seu), pelo contador da sessão nas não segmentadas.
   */
  private carregarVolume(sessaoId: string): void {
    this.separacaoService.buscarVolume(this.tenantAtual, sessaoId, this.etapaAtual()).subscribe({
      next: (v) => this.volume.set(v.quantidade),
      error: () => {
        /* não bloqueia a conferência */
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
    // Mesmo produto+controle pode estar espalhado em mais de uma SEQUENCIA da
    // nota (ex.: entregas parciais) — o backend redistribui a leitura entre
    // TODAS as linhas do grupo, não só a última (resultado.sequencia). Sem
    // isto, as linhas "de trás" ficavam com o valor certo no banco mas a tela
    // nunca aprendia disso: pendente "debitava" mas nunca virava conferido.
    (resultado.linhas ?? [])
      .filter((l) => l.sequencia !== resultado.sequencia)
      .forEach((l) => this.aplicarLinhaConferida(l.sequencia, l.qtdConferidaLocal));

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
        const d = this.avaliarDivergencia(c, sc);
        this.conferred.update((arr) =>
          arr.map((it, i) =>
            i === jaConf
              ? {
                  ...it,
                  scanned: sc,
                  status: d.status,
                  divergenceReason: d.divergenceReason,
                  divergenciaPeso: d.divergenciaPeso,
                  desvioPesoPct: d.desvioPesoPct,
                }
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
    // nominal exato). Não-pesável: precisa bater o total pra sair de pendentes.
    // A divergência do pesável só conta acima de ±5% do esperado (ver avaliarDivergencia).
    const d = this.avaliarDivergencia(item, scannedNovo);
    const concluido = d.conferido;
    const atualizado: ConferenciaItem = {
      ...item,
      scanned: scannedNovo,
      status: d.status,
      divergenceReason: d.divergenceReason,
      divergenciaPeso: d.divergenciaPeso,
      desvioPesoPct: d.desvioPesoPct,
      imagemUrl: this.ultimaImagemIdentificada,
    };

    // Pendentes: sai só quando conclui de verdade; senão fica com o restante atualizado.
    if (concluido) {
      this.items.update((arr) => arr.filter((_, i) => i !== idx));
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
    // Conferidos: qualquer progresso > 0 aparece/atualiza aqui, completo ou
    // parcial — item parcial fica visível nos dois painéis ao mesmo tempo.
    if (scannedNovo > 0) this.upsertConferred(atualizado);
    this.lastScan.set(atualizado);
  }

  /** Insere ou atualiza (por seq) uma linha na lista de conferidos — sem duplicar. */
  private upsertConferred(item: ConferenciaItem): void {
    this.conferred.update((c) => {
      const idx = c.findIndex((it) => it.seq === item.seq);
      if (idx === -1) return [item, ...c];
      const copia = [...c];
      copia[idx] = item;
      return copia;
    });
  }

  /**
   * Aplica a qtd_conferida_local de UMA linha do grupo (que não a principal
   * já tratada em onConferido) — mesma transição pendente→conferido, sem
   * mexer no "último bipe" exibido. Sem correspondência em pendentes (ex.:
   * já estava conferida, ou não existe) não faz nada — evita duplicar linha.
   */
  private aplicarLinhaConferida(seq: number, qtdConferidaLocalStr: string): void {
    const idx = this.items().findIndex((it) => it.seq === seq);
    if (idx === -1) return;

    const item = this.items()[idx];
    const scannedNovo = Number(qtdConferidaLocalStr);
    const d = this.avaliarDivergencia(item, scannedNovo);
    const atualizado: ConferenciaItem = {
      ...item,
      scanned: scannedNovo,
      status: d.status,
      divergenceReason: d.divergenceReason,
      divergenciaPeso: d.divergenciaPeso,
      desvioPesoPct: d.desvioPesoPct,
    };

    if (d.conferido) {
      this.items.update((arr) => arr.filter((_, i) => i !== idx));
    } else {
      this.items.update((arr) => arr.map((i, i2) => (i2 === idx ? atualizado : i)));
    }
    if (scannedNovo > 0) this.upsertConferred(atualizado);
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

  /**
   * Total de volumes (modo simplificado) — contador local da sessão, resposta
   * imediata. Vai pro Sankhya no `cortar` da finalização. Otimista: mostra na
   * hora e reverte se a gravação falhar.
   */
  onVolumeChange(quantidade: number): void {
    if (!this.sessaoIdAtual || quantidade < 0) return;
    const anterior = this.volume();
    this.volume.set(quantidade);
    this.separacaoService.definirVolume(this.tenantAtual, this.sessaoIdAtual, quantidade, this.etapaAtual()).subscribe({
      next: (v) => this.volume.set(v.quantidade),
      error: () => this.volume.set(anterior),
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
      const ultima = this.ehUltimaEtapaPendente();
      // Na ÚLTIMA etapa a checagem é da SESSÃO INTEIRA (todas as etapas, não só a
      // atual) — uma divergência de uma etapa já concluída (ex.: Secos) continua
      // valendo até a nota fechar de vez (ver itensDivergentesSessao). Numa etapa
      // intermediária, o aviso é só sobre o que essa etapa em si tem pendente.
      const divergente = ultima ? this.temDivergenciaSessao() : this.temDivergencia();
      if (divergente) {
        // Regra 5 vs 6: só a ÚLTIMA etapa pendente oferece corte/finalização
        // divergente de verdade — etapa intermediária é só um aviso.
        if (ultima) {
          this.mostrarModalDivergencia.set(true);
        } else {
          this.mostrarModalAvisoEtapa.set(true);
        }
        return;
      }
      this.concluirEtapaAgora(false);
      return;
    }
    if (this.temDivergenciaSessao()) {
      this.mostrarModalDivergencia.set(true);
      return;
    }
    this.executarFinalizacao();
  }

  /** Aviso de etapa intermediária (regra 5): só segue pras próximas etapas, sem cortar nem finalizar nada. */
  onContinuarAvisoEtapa(): void {
    this.mostrarModalAvisoEtapa.set(false);
    this.concluirEtapaAgora(true);
  }

  onCancelarAvisoEtapa(): void {
    this.mostrarModalAvisoEtapa.set(false);
  }

  /**
   * Última etapa pendente com divergência (regra 6) — "Cortar" e "Finalizar
   * divergente" chamam a MESMA ação: quem decide o ajuste é a CCO do Sankhya
   * (PROCEDCORTE/GERARPEDCOMPL), não o botão escolhido aqui. "Liberar sozinho"
   * (sem um liberador humano logando) só existe pra item PESÁVEL dentro da
   * tolerância de 5% (ver LiberacaoCorteService.autoLiberarPesoDentroTolerancia)
   * — item não pesável divergente SEMPRE precisa da tela de liberação manual
   * (login do liberador), mesmo quando o operador clica em "Cortar" aqui; uma
   * versão anterior fazia "Cortar" liberar não pesável sozinho com a
   * credencial de serviço — revertida (caso real: 2 itens de secos genuinamente
   * divergentes foram liberados sem nenhum liberador humano revisar). Modal
   * fica aberto (spinner nos botões, ver finalizando()) até a chamada terminar
   * — fechar na hora do clique deixava o "Enviando para o Sankhya" visível só
   * no rodapé, fora do que o usuário estava olhando.
   */
  onConfirmarDivergente(): void {
    if (this.modoEtapa()) {
      this.concluirEtapaAgora(true);
      return;
    }
    this.executarFinalizacao();
  }

  /**
   * Conclui a etapa atual. `409` com pendentes → abre o pop-up adequado
   * (aviso na intermediária, divergência na última). Última etapa → o
   * backend finaliza a nota no Sankhya e devolve a cadeia de corte/faturamento
   * (aposFinalizacao).
   */
  private concluirEtapaAgora(manterPendente: boolean): void {
    const tipo = this.etapaAtual();
    if (!this.sessaoIdAtual || tipo == null || this.concluindoEtapa || this.finalizando()) return;
    this.concluindoEtapa = true;
    this.finalizando.set(true);
    // Quem conclui vem do JWT no backend (call.exigirAuth()), não daqui.
    this.separacaoService
      .concluirEtapa(this.tenantAtual, this.sessaoIdAtual, { tipoSeparacao: tipo, manterPendente })
      .subscribe({
        next: (res: ConcluirEtapaResultado) => {
          this.concluindoEtapa = false;
          this.finalizando.set(false);
          this.mostrarModalDivergencia.set(false);
          this.mostrarModalAvisoEtapa.set(false);
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
            if (this.ehUltimaEtapaPendente()) {
              this.mostrarModalDivergencia.set(true);
            } else {
              this.mostrarModalAvisoEtapa.set(true);
            }
            return;
          }
          this.mostrarModalDivergencia.set(false);
          this.mostrarModalAvisoEtapa.set(false);
          this.erro.set(err?.error?.erro ?? 'Falha ao concluir a etapa.');
        },
      });
  }

  onCancelarDivergencia(): void {
    if (this.finalizando()) return;
    this.mostrarModalDivergencia.set(false);
  }

  private executarFinalizacao(): void {
    if (!this.sessaoIdAtual || this.finalizando()) return;
    this.finalizando.set(true);
    this.separacaoService.finalizar(this.tenantAtual, this.sessaoIdAtual).subscribe({
      next: (res) => {
        this.finalizando.set(false);
        this.mostrarModalDivergencia.set(false);
        this.aposFinalizacao(res);
      },
      error: (err) => {
        this.finalizando.set(false);
        this.mostrarModalDivergencia.set(false);
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

  /** Há item pesável já pesado na sessão — habilita "Imprimir etiqueta de peso" (tela e pop-up de finalização). */
  readonly temPesavelConferido = computed(() =>
    this.todosItensMapeados().some((i) => i.usaConfPeso && i.scanned > 0),
  );

  imprimirEtiquetaPeso(): void {
    if (!this.sessaoIdAtual) return;
    window.open(`/etiquetas-peso/${this.sessaoIdAtual}`, '_blank');
  }

  imprimirEtiquetas(): void {
    if (!this.sessaoIdAtual) return;
    window.open(`/etiquetas/${this.sessaoIdAtual}`, '_blank');
  }

  sairParaFila(): void {
    this.router.navigate(['/fila-tarefas']);
  }
}
