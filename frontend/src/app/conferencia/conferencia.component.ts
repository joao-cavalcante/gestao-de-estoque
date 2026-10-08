import { Component, ElementRef, OnDestroy, OnInit, ViewChild, computed, effect, inject, signal } from '@angular/core';
import { ActivatedRoute, Router } from '@angular/router';
import { Subscription, interval, of, timer } from 'rxjs';
import { catchError, filter, first, switchMap, timeout } from 'rxjs/operators';
import { OqConferenciaHeaderComponent } from './oq-conferencia-header/oq-conferencia-header.component';
import { OqScanBarComponent, ProdutoIdentificadoEvento } from './oq-scan-bar/oq-scan-bar.component';
import { OqPendingListComponent } from './oq-pending-list/oq-pending-list.component';
import { OqConferredListComponent } from './oq-conferred-list/oq-conferred-list.component';
import { OqLastScanPanelComponent } from './oq-last-scan-panel/oq-last-scan-panel.component';
import { OqConferenciaFooterComponent } from './oq-conferencia-footer/oq-conferencia-footer.component';
import { ConferenciaItem, ItemStatus } from './conferencia.model';
import { SeparacaoService } from '../separacao/separacao.service';
import { LockService } from '../separacao/lock.service';
import {
  ConcluirEtapaResultado,
  ConclusaoServidor,
  FinalizacaoProgresso,
  FinalizarResultado,
  ItemConferido,
  ItemSeparacao,
  SessaoEtapa,
  SincronizacaoSankhya,
} from '../separacao/separacao.model';
import { OqLiberacaoCorteModalComponent } from '../liberacao-corte/oq-liberacao-corte-modal/oq-liberacao-corte-modal.component';
import { OqFechamentoOcModalComponent } from '../fila-tarefas/oq-fechamento-oc-modal/oq-fechamento-oc-modal.component';
import { FormsModule } from '@angular/forms';
import { Tarefa, rotuloTipoSeparacao } from '../fila-tarefas/tarefa.model';
import { AuthService } from '../auth/auth.service';
import { OqIconComponent } from '../shared/icons/oq-icon.component';
import { OqSpinnerComponent } from '../shared/icons/oq-spinner.component';
import { OqSkeletonComponent } from '../shared/oq-skeleton/oq-skeleton.component';
import { OqConferidosChecklistComponent } from '../reconferencia/oq-conferidos-checklist.component';
import { ReconferenciaDetalhe, ReconferenciaService } from '../reconferencia/reconferencia.service';
import { ActionFeedbackService } from '../shared/action-feedback/action-feedback.service';
import { OqFeedbackFlashDirective } from '../shared/action-feedback/oq-feedback-flash.directive';

/**
 * Tolerância de peso do item pesável (V50), em FRAÇÃO (0.05 = 5%): quanto pode pesar a
 * mais / a menos que o pedido sem virar divergência. null = sem limite naquele sentido.
 * Vem da sessão (copiada do NUCCO na abertura — Configuração de Conferência). Mesma regra
 * da auto-liberação de corte por peso (LiberacaoCorteService.autoLiberarPesoDentroTolerancia).
 */
interface ToleranciaPeso {
  acima: number | null;
  abaixo: number | null;
}

/** Regra de antes da V50 (NUCCO sem configuração): a maior sem limite, a menor até 5%. */
const TOLERANCIA_PADRAO: ToleranciaPeso = { acima: null, abaixo: 0.05 };

/** Desvio |conferido - esperado| / esperado. Retorna 0 quando não há esperado. */
function desvioPeso(scanned: number, expected: number): number {
  if (expected <= 0) return scanned > 0 ? 1 : 0;
  return Math.abs(scanned - expected) / expected;
}

/** Item pesável fora da tolerância da sessão — a maior e a menor com limites próprios (null = sem limite). */
function pesoForaDaTolerancia(scanned: number, expected: number, tol: ToleranciaPeso): boolean {
  const limite = scanned > expected ? tol.acima : tol.abaixo;
  if (limite == null) return false;
  return desvioPeso(scanned, expected) > limite;
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

/**
 * Conferido = o total bateu (arredondamento de 3 casas — ver round3). Vale também pro PESÁVEL
 * (29/09, pedido do usuário): antes "qualquer peso > 0 conclui", e um item de 2 caixas saía de
 * Pendentes na 1ª pesagem. Agora o pesável fica em Pendentes com o KG que falta até o peso chegar
 * no pedido — qualquer falta, dentro ou fora da tolerância. A tolerância só decide a liberação
 * silenciosa no corte (LiberacaoCorteService) e o alerta de peso A MAIOR.
 */
function estaConferido(item: { usaConfPeso?: boolean; scanned: number; expected: number }): boolean {
  return round3(item.scanned) >= round3(item.expected);
}

/**
 * Pesável com peso A MENOR (já pesado, ainda não bateu o pedido) mas dentro da tolerância de
 * baixo — não é divergência: não abre o pop-up; o backend libera o corte sozinho (autoLiberarPesoDentroTolerancia).
 */
function pesoNaToleranciaAbaixo(usaConfPeso: boolean | undefined, scanned: number, expected: number, tol: ToleranciaPeso): boolean {
  return !!usaConfPeso && scanned > 0 && round3(scanned) < round3(expected) && !pesoForaDaTolerancia(scanned, expected, tol);
}

/** Mapeia o item real (vindo de app.separacao_itens) pro modelo visual do painel — mesmo shape do mock anterior. */
function mapearItem(item: ItemSeparacao, tol: ToleranciaPeso): ConferenciaItem {
  const expected = Number(item.qtdNeg);
  const scanned = Number(item.qtdConferidaLocal);
  const unidadePadrao = item.unidadePadrao?.trim() || item.codvol?.trim() || undefined;
  const unidadeComercial = item.unidadeComercial?.trim() || unidadePadrao;
  const conferido = estaConferido({ usaConfPeso: item.usaConfPeso, scanned, expected });
  // Pesável: só diverge A MAIOR fora da tolerância de cima (a menor fica em Pendentes — ver
  // estaConferido). Não-pesável: diverge se passou do esperado (arredondamento de 3 casas — ver round3).
  const divergePeso = item.usaConfPeso && scanned > expected && pesoForaDaTolerancia(scanned, expected, tol);
  const divergeQtd = !item.usaConfPeso && round3(scanned) > round3(expected);
  const divergente = divergePeso || divergeQtd;
  return {
    seq: item.sequencia,
    code: String(item.codprod),
    // "Descrição - Complemento" (TGFPRO.COMPLDESC), quando houver — mesmo formato de todas as telas.
    name: [item.descricaoProduto?.trim(), item.complementoDescricao?.trim()].filter(Boolean).join(' - ') || `Produto ${item.codprod}`,
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
    pesoNaTolerancia: pesoNaToleranciaAbaixo(item.usaConfPeso, scanned, expected, tol) || undefined,
    usaConfPeso: item.usaConfPeso,
    foraPedido: item.foraPedido,
    tipoSeparacao: item.tipoSeparacao,
    unidadePadrao,
    unidadeComercial,
    quantidadeComercial: item.quantidadeComercial != null ? Number(item.quantidadeComercial) : undefined,
    conversao: item.conversao ?? undefined,
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
    OqFechamentoOcModalComponent,
    OqSpinnerComponent,
    OqSkeletonComponent,
    OqConferidosChecklistComponent,
    OqFeedbackFlashDirective,
    FormsModule,
  ],
  templateUrl: './conferencia.component.html',
  styleUrl: './conferencia.component.scss',
})
export class ConferenciaComponent implements OnInit, OnDestroy {
  private readonly route = inject(ActivatedRoute);
  private readonly router = inject(Router);
  private readonly separacaoService = inject(SeparacaoService);
  private readonly lockService = inject(LockService);
  private readonly authService = inject(AuthService);
  private readonly feedback = inject(ActionFeedbackService);
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
  motorista: string | null = null;
  veiculo: string | null = null;

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
  /** V50 — tolerância de peso da sessão (fração; null = sem limite). Definida ao abrir a sessão. */
  private toleranciaPeso: ToleranciaPeso = TOLERANCIA_PADRAO;

  // ─── Fluxo pós-finalização ───────────────────────────────────────────────
  /** Modal de liberação de corte (quando o cortar deixou a conferência em STATUS='C'). */
  readonly mostrarModalLiberacaoCorte = signal(false);
  nuconfLiberacao: number | null = null;
  /** Modal de faturamento (CCO FATAOCONCLUIR='S'). */
  readonly mostrarModalFaturamento = signal(false);
  /** Painel "Conferência finalizada" (com botão de imprimir etiquetas). */
  readonly mostrarPainelFinalizada = signal(false);
  /** Etiquetas da ETAPA recém-concluída (volumes acumulados + peso) — pop-up de fim de etapa e painel final. */
  readonly etapaImpressao = signal<{ tipo: number; rotulo: string; volumes: number; pesaveis: boolean } | null>(null);
  readonly mostrarPainelEtapaConcluida = signal(false);

  /** Modo simplificado (sem dimensão) — só a quantidade de volumes, nativo do Sankhya. */
  readonly volume = signal(0);

  /** Pendentes em ordem alfabética de produto ("Descrição - Complemento"). */
  readonly pendingItems = computed(() => [...this.items()].sort(porNomeProduto));
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
  /** Etapa do finalizar em andamento no backend (polling de 1s enquanto `finalizando`). */
  private readonly progressoFinalizacao = signal<FinalizacaoProgresso | null>(null);
  readonly textoFinalizacao = computed(() => {
    if (!this.finalizando()) return null;
    const p = this.progressoFinalizacao();
    switch (p?.fase) {
      case 'itens': return `Enviando itens ao Sankhya… ${p.feitos} de ${p.total}`;
      case 'corte': return 'Fechando a conferência (corte)…';
      case 'liberacao': return 'Liberando itens pesáveis…';
      case 'finalizando': return 'Finalizando a conferência…';
      default: return 'Enviando para o Sankhya…';
    }
  });
  /** 0..1 só na fase de envio dos itens (a única com contagem); null nas demais. */
  readonly percentualFinalizacao = computed(() => {
    const p = this.progressoFinalizacao();
    return this.finalizando() && p?.fase === 'itens' && p.total > 0 ? p.feitos / p.total : null;
  });
  private readonly acompanharFinalizacao = effect((onCleanup) => {
    if (!this.finalizando()) return;
    const sessaoId = this.sessaoIdAtual;
    if (!sessaoId) return;
    const sub = timer(0, 1000)
      .pipe(switchMap(() => this.separacaoService.progressoFinalizacao(this.tenantAtual, sessaoId).pipe(catchError(() => of(null)))))
      .subscribe((p) => {
        this.progressoFinalizacao.set(p);
        if (p?.concluido) this.recuperarConclusao(p.concluido);
      });
    onCleanup(() => sub.unsubscribe());
  });
  /** Envio em andamento (concluir etapa / finalizar) — pra poder abandonar o request se o servidor já concluiu. */
  private operacaoSub?: Subscription;
  private operacaoEmCurso:
    | { tipo: 'etapa'; etapa: number; info: { tipo: number; rotulo: string; volumes: number; pesaveis: boolean } }
    | { tipo: 'finalizar' }
    | null = null;
  /**
   * Botão "Finalizar Conferência" fica sempre disponível — quem decide o que
   * fazer com a divergência é a CCO do Sankhya, não um bloqueio nosso. Única
   * exceção: CCO.FORMACAOVOLUMES 'S'/'T'/'D' exige volume apontado antes de
   * liberar (backend também recusa, ver SeparacaoService.finalizar — isto é
   * só a UX, não a única barreira).
   */
  readonly canConfirm = computed(() => !this.finalizando() && (!this.exigeVolume() || this.volume() > 0));
  /** Falta (pendente) ou sobra (crítico) — nos dois casos o Sankhya decide o ajuste via CCO (PROCEDCORTE/GERARPEDCOMPL), mas o operador precisa confirmar ciente disso. */
  readonly temDivergencia = computed(() => this.items().some((i) => !i.pesoNaTolerancia) || this.divergenceCount() > 0);
  /** Pendente que é só pesável a menor dentro da tolerância — conclui sem aviso, com o corte liberado sozinho. */
  readonly soPendenteNaTolerancia = computed(() => this.items().length > 0 && this.items().every((i) => !!i.pesoNaTolerancia));
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
      // Soma da qtd comercial das linhas (mantém a proporção comercial/base do grupo — pop-up de divergência).
      const quantidadeComercial = linhas.every((l) => l.quantidadeComercial != null)
        ? linhas.reduce((acc, l) => acc + (l.quantidadeComercial ?? 0), 0)
        : base.quantidadeComercial;
      const d = this.avaliarDivergencia({ usaConfPeso: base.usaConfPeso, expected }, scanned);
      // Pesável a menor DENTRO da tolerância não é divergência: o backend libera o corte sozinho
      // (autoLiberarPesoDentroTolerancia) — não entra no pop-up nem trava a finalização.
      if (d.status === 'ok' || d.pesoNaTolerancia) continue;
      agregados.push({
        ...base,
        expected,
        scanned,
        status: d.status,
        divergenceReason: base.foraPedido ? 'FORA DO PEDIDO' : d.divergenceReason,
        divergenciaPeso: d.divergenciaPeso,
        desvioPesoPct: d.desvioPesoPct,
        pesoNaTolerancia: d.pesoNaTolerancia,
        quantidadeComercial,
      });
    }
    return agregados.sort(porNomeProduto);
  });
  readonly temDivergenciaSessao = computed(() => this.itensDivergentesSessao().length > 0);

  /**
   * SÓ EXIBIÇÃO (pop-up de divergência): tipo + explicação da divergência, em vez do "A MENOR" seco.
   * Pesável cita a tolerância da sessão que foi estourada; não pesável diz se é falta, sobra ou nada conferido.
   */
  descreverDivergencia(item: ConferenciaItem): { tipo: string; detalhe: string } {
    const pct = (f: number) => (f * 100).toLocaleString('pt-BR', { minimumFractionDigits: 1, maximumFractionDigits: 1 });
    if (item.foraPedido) return { tipo: 'Fora do pedido', detalhe: 'Produto não consta no pedido' };
    const aMaior = round3(item.scanned) > round3(item.expected);
    if (item.usaConfPeso) {
      if (item.scanned <= 0) return { tipo: 'Não pesado', detalhe: 'Nenhuma pesagem registrada' };
      const limite = aMaior ? this.toleranciaPeso.acima : this.toleranciaPeso.abaixo;
      const tol =
        limite == null ? 'sem limite de tolerância' : limite === 0 ? 'tolerância 0% — não aceita diferença' : `fora da tolerância de ${pct(limite)}%`;
      return { tipo: aMaior ? 'Peso a maior' : 'Peso a menor', detalhe: tol.charAt(0).toUpperCase() + tol.slice(1) };
    }
    if (item.scanned <= 0) return { tipo: 'Não conferido', detalhe: 'Nenhuma unidade conferida' };
    return aMaior
      ? { tipo: 'Sobra', detalhe: 'Conferido acima do pedido' }
      : { tipo: 'Falta', detalhe: 'Conferido abaixo do pedido' };
  }

  /**
   * SÓ EXIBIÇÃO — linhas do pop-up de divergência. Números na unidade COMERCIALIZADA (a do pedido);
   * pesável fica em KG (unidade base). Quando a comercial difere da base, a base vai numa linha menor.
   */
  readonly linhasDivergenciaModal = computed(() =>
    this.itensDivergentesSessao().map((item) => {
      const comercial =
        !item.usaConfPeso &&
        !!item.unidadeComercial &&
        item.unidadeComercial !== item.unidadePadrao &&
        item.quantidadeComercial != null &&
        item.expected > 0;
      const fator = comercial ? item.quantidadeComercial! / item.expected : 1;
      const un = (comercial ? item.unidadeComercial : item.unidadePadrao) ?? '';
      const casas = item.usaConfPeso ? 3 : 0;
      const pedido = item.expected * fator;
      const conferido = item.scanned * fator;
      const dif = round3(conferido - pedido);
      const sinal = dif > 0 ? '+' : dif < 0 ? '−' : '';
      const pct = item.expected > 0
        ? `${sinal}${Math.abs((dif / pedido) * 100).toLocaleString('pt-BR', { minimumFractionDigits: 1, maximumFractionDigits: 1 })}%`
        : null;
      return {
        item,
        pedido: this.fmtQtdModal(pedido, casas, un),
        conferido: this.fmtQtdModal(conferido, casas, un),
        dif: `${sinal}${this.fmtQtdModal(Math.abs(dif), casas, un)}`,
        pct,
        base: comercial
          ? `${this.fmtQtdModal(item.expected, 0, item.unidadePadrao ?? '')} → ${this.fmtQtdModal(item.scanned, 0, item.unidadePadrao ?? '')}`
          : null,
      };
    }),
  );

  /** pt-BR: pesável com 3 casas fixas; unidade inteira sem ",000" (mas mostra fração se houver, até 3 casas). */
  private fmtQtdModal(n: number, casasMin: number, un: string): string {
    const v = (n ?? 0).toLocaleString('pt-BR', { minimumFractionDigits: casasMin, maximumFractionDigits: 3 });
    return un ? `${v} ${un}` : v;
  }
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
  // ─── "Atualizar com Sankhya": relê o pedido e corrige quantidade/unidade/pesável (virada de sistema) ───
  readonly sincronizando = signal(false);
  /** Resultado da última atualização — abre o pop-up com o que foi corrigido. */
  readonly sincronizacao = signal<SincronizacaoSankhya | null>(null);
  /** true = o pop-up veio da checagem automática ao concluir/finalizar (pedido mudou no Sankhya). */
  readonly sincronizacaoAutomatica = signal(false);
  readonly sincronizacaoDesfeitos = computed(() => this.sincronizacao()?.correcoes.filter((c) => c.conferenciaDesfeita).length ?? 0);

  onSincronizarSankhya(): void {
    const sessaoId = this.sessaoIdAtual;
    if (!sessaoId || this.sincronizando()) return;
    this.sincronizando.set(true);
    this.separacaoService.sincronizarSankhya(this.tenantAtual, sessaoId).subscribe({
      next: (r) => {
        this.sincronizacaoAutomatica.set(false);
        this.aplicarSincronizacao(r, sessaoId, () => this.sincronizando.set(false));
      },
      error: (err) => {
        this.sincronizando.set(false);
        if (err?.error?.codigo === 'LOCK_INVALIDO') return; // interceptor já trava a tela
        this.feedback.trigger('ERRO', { mensagem: err?.error?.erro ?? 'Falha ao atualizar com o Sankhya — tente novamente.' });
      },
    });
  }

  /** Mostra o que mudou e relê a sessão (itens, etapas, UMA) — botão "Atualizar com Sankhya" e checagem ao concluir. */
  private aplicarSincronizacao(r: SincronizacaoSankhya, sessaoId: string, depois?: () => void): void {
    this.sincronizacao.set(r);
    // Item pode ter virado pesável (UMA) ou mudado de etapa — relê o que a tela guarda em memória.
    this.scanBar?.recarregarUma();
    if (this.conferenciaSegmentada) {
      this.separacaoService.buscarEtapas(this.tenantAtual, sessaoId).subscribe({ next: (e) => this.etapasSessao.set(e) });
    }
    this.recarregarItens(sessaoId, depois);
  }

  /** 409 PEDIDO_ALTERADO (concluir etapa/finalizar): a sessão já foi corrigida no backend — mostra e deixa conferir. */
  private tratarPedidoAlterado(err: any): boolean {
    if (err?.status !== 409 || err?.error?.codigo !== 'PEDIDO_ALTERADO' || !this.sessaoIdAtual) return false;
    this.encerrarOperacao();
    this.mostrarModalDivergencia.set(false);
    this.mostrarModalAvisoEtapa.set(false);
    this.feedback.trigger('DIVERGENCIA');
    this.sincronizacaoAutomatica.set(true);
    this.aplicarSincronizacao(err.error.sincronizacao as SincronizacaoSankhya, this.sessaoIdAtual);
    return true;
  }

  fecharSincronizacao(): void {
    this.sincronizacao.set(null);
    this.sincronizacaoAutomatica.set(false);
  }

  // ─── "Ver conferidos": tudo o que já foi conferido na nota (todas as etapas), com check de reconferência ───
  private readonly reconferenciaService = inject(ReconferenciaService);
  readonly mostrarConferidosNota = signal(false);
  readonly conferidosDetalhe = signal<ReconferenciaDetalhe | null>(null);
  readonly conferidosErro = signal<string | null>(null);

  abrirConferidos(): void {
    const sessaoId = this.sessaoIdAtual;
    if (!sessaoId) return;
    this.conferidosDetalhe.set(null);
    this.conferidosErro.set(null);
    this.mostrarConferidosNota.set(true);
    this.reconferenciaService.detalhe(sessaoId).subscribe({
      next: (d) => this.conferidosDetalhe.set(d),
      error: () => this.conferidosErro.set('Não foi possível carregar os conferidos da nota.'),
    });
  }

  fecharConferidos(): void {
    this.mostrarConferidosNota.set(false);
    this.conferidosDetalhe.set(null);
    if (this.voltarAoPainelFinal) {
      this.voltarAoPainelFinal = false;
      this.mostrarPainelFinalizada.set(true);
    }
  }

  /** true = sessão segmentada, mais de uma etapa pendente e nenhuma escolhida — mostra o seletor. */
  readonly precisaEscolherEtapa = computed(() => this.etapaAtual() == null && this.etapasSessao().length > 0);
  /** Etapas pra oferecer no seletor (pendentes primeiro). */
  readonly etapasParaEscolher = computed(() =>
    [...this.etapasSessao()]
      .sort((a, b) => a.tipoSeparacao - b.tipoSeparacao)
      .map((e) => ({
        tipo: e.tipoSeparacao,
        rotulo: rotuloTipoSeparacao(e.tipoSeparacao),
        concluida: e.status === 'C',
        emUso: !!e.emUso,
        emUsoPor: e.emUsoPor ?? null,
      })),
  );
  private concluindoEtapa = false;

  // ─── Lock exclusivo por etapa (V46) ─────────────────────────────────────
  /** Etapa está com outra aba/tablet (ETAPA_EM_USO) ou o lock não pôde ser confirmado. */
  readonly lockBloqueio = signal<string | null>(null);
  /** Mensagem do bloqueio OU da sessão expirada (LOCK_INVALIDO vindo de qualquer chamada) — trava a tela. */
  readonly lockMensagem = computed(() => this.lockBloqueio() ?? this.lockService.invalido());
  /** Buzzer uma vez quando a tela trava (etapa em uso / sessão expirada) — não a cada re-render. */
  private readonly efeitoBloqueio = effect(() => {
    if (this.lockMensagem()) this.feedback.trigger('BLOQUEADO');
  });
  private heartbeatSub?: Subscription;
  /** Etapa cujo lock esta aba tem (null = sessão inteira). undefined = nenhum lock. */
  private lockEtapa: number | null | undefined = undefined;
  private static readonly HEARTBEAT_MS = 60_000;

  /**
   * Assume o lock da etapa atual (ou da sessão inteira, se não segmentada) e mantém com heartbeat. O backend
   * expira o lock após 10 min sem heartbeat; enquanto o heartbeat chega ele é renovado indefinidamente.
   * Chamado ao abrir a conferência, ao escolher etapa e no "Tentar novamente" do bloqueio.
   */
  tentarLock(): void {
    const sessaoId = this.sessaoIdAtual;
    if (!sessaoId) return;
    const etapa = this.conferenciaSegmentada ? this.etapaAtual() : null;
    if (this.conferenciaSegmentada && etapa == null) {
      // Ainda vai escolher a etapa — não há lock nenhum desta aba pra estar inválido.
      this.lockBloqueio.set(null);
      this.lockService.invalido.set(null);
      return;
    }
    // Trocou de etapa nesta aba: solta o lock da anterior.
    if (this.lockEtapa !== undefined && this.lockEtapa !== etapa) this.liberarLock();

    this.lockService.adquirir(this.tenantAtual, sessaoId, etapa).subscribe({
      next: () => {
        this.lockBloqueio.set(null);
        this.lockService.invalido.set(null);
        this.lockEtapa = etapa;
        this.iniciarHeartbeat(sessaoId, etapa);
      },
      error: (err) => {
        this.lockBloqueio.set(
          err?.status === 409
            ? (err?.error?.erro ?? 'Esta etapa já está em uso por outro operador.')
            : 'Não foi possível validar a sessão da etapa. Verifique a conexão e tente novamente.',
        );
      },
    });
  }

  private iniciarHeartbeat(sessaoId: string, etapa: number | null): void {
    this.heartbeatSub?.unsubscribe();
    this.heartbeatSub = interval(ConferenciaComponent.HEARTBEAT_MS)
      .pipe(
        // Falha de rede não derruba: tenta de novo no próximo ciclo. 409 LOCK_INVALIDO já é tratado pelo interceptor.
        switchMap(() => this.lockService.heartbeat(this.tenantAtual, sessaoId, etapa).pipe(catchError(() => of(null)))),
      )
      .subscribe();
  }

  private liberarLock(): void {
    this.heartbeatSub?.unsubscribe();
    const sessaoId = this.sessaoIdAtual;
    if (sessaoId && this.lockEtapa !== undefined) {
      this.lockService.liberar(this.tenantAtual, sessaoId, this.lockEtapa).subscribe({ error: () => {} });
    }
    this.lockEtapa = undefined;
  }

  escolherEtapa(tipo: number): void {
    this.etapaAtual.set(tipo);
    this.tentarLock();
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
      return porNomeProduto(a, b);
    }),
  );

  ngOnInit(): void {
    // Tablet/celular: abre sempre no topo (o foco no campo de bipagem não rola mais a página — preventScroll).
    window.scrollTo(0, 0);
    // O aviso de lock inválido é global (LockService, root) — um 409 da conferência
    // ANTERIOR não pode travar esta. Bug real: "sessões presas" sem sessão nenhuma ativa.
    this.lockService.invalido.set(null);
    const nunota = Number(this.route.snapshot.paramMap.get('nunota'));
    const etapaRaw = Number(this.route.snapshot.queryParamMap.get('etapa'));
    this.etapaParam = Number.isFinite(etapaRaw) && etapaRaw >= 1 && etapaRaw <= 3 ? etapaRaw : null;
    const tarefa = history.state?.tarefa as Tarefa | undefined;
    if (tarefa) {
      this.nf = tarefa.nf;
      this.parceiro = tarefa.cliente;
      this.vendedor = tarefa.responsavel;
      this.motorista = tarefa.motorista ?? null;
      this.ordemCargaTarefa = tarefa.ordemCarga ?? null;
      this.veiculo = tarefa.transporte && tarefa.transporte !== '—' ? tarefa.transporte : null;
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
        if (this.exigeCracha) setTimeout(() => this.inputCrachaOperador?.nativeElement.focus({ preventScroll: true }));
        this.aguardarSessaoPronta(resp.sessaoId);
      },
      error: (err) => {
        this.erro.set(err?.error?.erro ?? 'Falha ao iniciar separação.');
        this.feedback.trigger('ERRO_SANKHYA', { toast: false });
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
        this.feedback.trigger('OPERACAO_NAO_PERMITIDA');
        setTimeout(() => this.inputCrachaOperador?.nativeElement.focus({ preventScroll: true }));
      },
    });
  }

  ngOnDestroy(): void {
    this.sessaoSub?.unsubscribe();
    // Saiu da conferência: libera a etapa pra outro operador (senão só expira em 10 min).
    this.liberarLock();
    this.lockService.invalido.set(null);
  }

  /**
   * O backend já soltou o lock (concluir etapa → liberarEtapa; finalizar →
   * liberarTodos). Para o heartbeat SEM chamar liberar: senão o próximo
   * heartbeat (até 60 s depois, com o painel final ainda aberto) volta 409
   * LOCK_INVALIDO e acende o aviso de "sessão inválida" à toa — que, por ser
   * global, ainda travava a próxima conferência aberta.
   */
  private encerrarLockLocal(): void {
    this.heartbeatSub?.unsubscribe();
    this.heartbeatSub = undefined;
    this.lockEtapa = undefined;
  }

  /**
   * Recarrega itens do backend e re-divide pendentes/conferidos. É a fonte da
   * verdade: a lista de pendentes é sempre o pedido negociado ainda não
   * conferido, nunca o resultado da bipagem. Usado ao abrir e ao devolver.
   */
  /**
   * Status/divergência de um item conferido, aplicando a tolerância de peso da
   * sessão (acima/abaixo, V50): item pesável só é divergente se o peso conferido
   * sair dela; a divergência de peso tem indicador visual próprio (divergenciaPeso).
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
    pesoNaTolerancia: true | undefined;
  } {
    const conferido = estaConferido({ scanned, expected: item.expected });
    // A menor fica em Pendentes (parcial); só a maior fora da tolerância de cima vira divergência de peso.
    const divergePeso = !!item.usaConfPeso && scanned > item.expected && pesoForaDaTolerancia(scanned, item.expected, this.toleranciaPeso);
    const divergeQtd = !item.usaConfPeso && round3(scanned) > round3(item.expected);
    const divergente = divergePeso || divergeQtd;
    return {
      conferido,
      status: divergente ? 'critical' : conferido ? 'ok' : 'pending',
      divergenceReason: divergePeso ? 'PESO FORA DA TOLERÂNCIA' : divergeQtd ? 'QTD. DIVERGENTE' : undefined,
      divergenciaPeso: divergePeso || undefined,
      desvioPesoPct: item.usaConfPeso && scanned > 0 ? desvioPesoPctSigned(scanned, item.expected) : undefined,
      pesoNaTolerancia: pesoNaToleranciaAbaixo(item.usaConfPeso, scanned, item.expected, this.toleranciaPeso) || undefined,
    };
  }

  private recarregarItens(sessaoId: string, aoTerminar?: () => void): void {
    this.separacaoService.buscarItens(this.tenantAtual, sessaoId).subscribe({
      next: (itens) => {
        // Fora do pedido sem nada bipado não aparece em lugar nenhum (foi só
        // identificado e não conferido) — a lista de pendentes é o pedido.
        let mapeados = itens.map((i) => mapearItem(i, this.toleranciaPeso)).filter((i) => !(i.foraPedido && i.scanned === 0));
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
        this.feedback.trigger('ERRO_SANKHYA', { toast: false });
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
            this.feedback.trigger('ERRO_SANKHYA', { toast: false });
            this.carregando.set(false);
            return;
          }
          this.sessaoIdAtual = sessaoId;
          this.obterQtdBalanca = sessao.obterQtdBalanca;
          this.conferenciaSegmentada = sessao.conferenciaSegmentada;
          this.fatAoConcluir = sessao.fatAoConcluir;
          // V50 — tolerância de peso da sessão (%, null = sem limite). Campo ausente = regra de antes.
          this.toleranciaPeso =
            sessao.tolPesoAcimaPct === undefined && sessao.tolPesoAbaixoPct === undefined
              ? TOLERANCIA_PADRAO
              : {
                  acima: sessao.tolPesoAcimaPct == null ? null : sessao.tolPesoAcimaPct / 100,
                  abaixo: sessao.tolPesoAbaixoPct == null ? null : sessao.tolPesoAbaixoPct / 100,
                };
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
                this.tentarLock();
                this.carregarVolume(sessaoId);
                this.recarregarItens(sessaoId, () => this.carregando.set(false));
              },
              error: () => this.recarregarItens(sessaoId, () => this.carregando.set(false)),
            });
          } else {
            this.tentarLock();
            this.carregarVolume(sessaoId);
            this.recarregarItens(sessaoId, () => this.carregando.set(false));
          }
        },
        error: () => {
          this.erro.set('Tempo esgotado aguardando o carregamento da sessão.');
          this.feedback.trigger('ERRO_SANKHYA', { toast: false });
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
      next: (v) => {
        this.volume.set(v.quantidade);
        // Padrão 1 volume (pedido do usuário): contador zerado ao abrir já grava 1 — o operador só
        // mexe quando tem mais de um. Na segmentada vale por etapa (cada etapa sai com ao menos 1).
        if (v.quantidade === 0) this.onVolumeChange(1);
      },
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
                  pesoNaTolerancia: d.pesoNaTolerancia,
                }
              : it,
          ),
        );
        this.lastScan.set(this.conferred()[jaConf]);
        this.dispararResultadoItem(d, false);
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
        this.feedback.trigger('PRODUTO_INCORRETO');
      }
      return;
    }

    const item = this.items()[idx];
    const scannedNovo = Number(resultado.qtdConferidaLocal);
    // scannedNovo = soma de todas as leituras (o backend acumula) — pesável pesado em partes vai
    // somando. Sai de Pendentes quando bate o pedido, pesável ou não (ver estaConferido); o pesável
    // a maior fora da tolerância de cima vira divergência de peso (ver avaliarDivergencia).
    const d = this.avaliarDivergencia(item, scannedNovo);
    const concluido = d.conferido;
    const atualizado: ConferenciaItem = {
      ...item,
      scanned: scannedNovo,
      status: d.status,
      divergenceReason: d.divergenceReason,
      divergenciaPeso: d.divergenciaPeso,
      desvioPesoPct: d.desvioPesoPct,
      pesoNaTolerancia: d.pesoNaTolerancia,
      imagemUrl: this.ultimaImagemIdentificada,
    };

    // Pendentes: sai só quando conclui de verdade; senão fica com o restante atualizado.
    if (concluido) {
      this.items.update((arr) => arr.filter((_, i) => i !== idx));
      // Última pendência bipada — lista zerou.
      if (this.pendingCount() === 0) {
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
    // Som distinto quando zerou os pendentes (mesma ideia do projeto base).
    this.dispararResultadoItem(d, concluido && this.pendingCount() === 0);
  }

  /** UM feedback por confirmação de item — a divergência vence o "ok". */
  private dispararResultadoItem(d: { status: ItemStatus; divergenciaPeso: true | undefined }, zerouPendentes: boolean): void {
    if (d.divergenciaPeso) this.feedback.trigger('PESO_DIVERGENTE');
    else if (d.status === 'critical') this.feedback.trigger('QUANTIDADE_DIVERGENTE');
    else if (zerouPendentes) this.feedback.trigger('TODOS_CONFERIDOS');
    else this.feedback.trigger('ITEM_CONFERIDO');
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
      pesoNaTolerancia: d.pesoNaTolerancia,
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
        this.feedback.trigger('ERRO', { mensagem: 'Falha ao devolver o item — tente novamente.' });
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
      error: () => {
        this.volume.set(anterior);
        this.feedback.trigger('ERRO', { mensagem: 'Falha ao gravar a quantidade de volumes.' });
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
        // Mantém a conferência na tela (antes trocava tudo pela tela de erro de abertura).
        this.feedback.trigger('ERRO_SANKHYA', { mensagem: err?.error?.erro ?? 'Falha ao cancelar o pedido.' });
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
          this.feedback.trigger('FINALIZACAO_DIVERGENTE');
          this.mostrarModalDivergencia.set(true);
        } else {
          this.feedback.trigger('DIVERGENCIA');
          this.mostrarModalAvisoEtapa.set(true);
        }
        return;
      }
      // Só sobrou pesável na tolerância em Pendentes: segue (manterPendente) sem pop-up — na última
      // etapa o backend corta e libera sozinho (finalizar com semCorte=false).
      this.concluirEtapaAgora(this.soPendenteNaTolerancia(), false, false);
      return;
    }
    if (this.temDivergenciaSessao()) {
      this.feedback.trigger('FINALIZACAO_DIVERGENTE');
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
   * Pop-up de divergência (última etapa / conferência sem etapa) — dois caminhos
   * DIFERENTES, igual à tela nativa do Sankhya (29/09):
   * - "Cortar" (semCorte=false): cortar → liberação se a CCO exigir → finaliza; o
   *   ajuste da nota segue a CCO (PROCEDCORTE/GERARPEDCOMPL).
   * - "Finalizar divergente" (semCorte=true): SÓ finalizarConferencia com os eventos
   *   nativos, sem corte — a conferência fica 'D' (Finalizada divergente) e a nota
   *   não é ajustada. Antes os dois botões faziam o "Cortar".
   * "Liberar sozinho"
   * (sem um liberador humano logando) só existe pra item PESÁVEL dentro da
   * tolerância da sessão (ver LiberacaoCorteService.autoLiberarPesoDentroTolerancia)
   * — item não pesável divergente SEMPRE precisa da tela de liberação manual
   * (login do liberador), mesmo quando o operador clica em "Cortar" aqui; uma
   * versão anterior fazia "Cortar" liberar não pesável sozinho com a
   * credencial de serviço — revertida (caso real: 2 itens de secos genuinamente
   * divergentes foram liberados sem nenhum liberador humano revisar). Modal
   * fica aberto (spinner nos botões, ver finalizando()) até a chamada terminar
   * — fechar na hora do clique deixava o "Enviando para o Sankhya" visível só
   * no rodapé, fora do que o usuário estava olhando.
   */
  onConfirmarDivergente(semCorte: boolean): void {
    if (this.modoEtapa()) {
      this.concluirEtapaAgora(true, semCorte);
      return;
    }
    this.executarFinalizacao(semCorte);
  }

  /**
   * Conclui a etapa atual. `409` com pendentes → abre o pop-up adequado
   * (aviso na intermediária, divergência na última). Última etapa → o
   * backend finaliza a nota no Sankhya e devolve a cadeia de corte/faturamento
   * (aposFinalizacao).
   */
  /** `divergente` = pin vermelho da etapa na fila; omitido = segue manterPendente (false só p/ pesável na tolerância). */
  private concluirEtapaAgora(manterPendente: boolean, finalizarSemCorte = false, divergente?: boolean): void {
    const tipo = this.etapaAtual();
    if (!this.sessaoIdAtual || tipo == null || this.concluindoEtapa || this.finalizando()) return;
    this.concluindoEtapa = true;
    this.finalizando.set(true);
    this.feedback.trigger('ENVIANDO_SANKHYA');
    // Foto da etapa ANTES de concluir (volume e itens da tela são os dela): base das etiquetas do pop-up.
    const infoEtapa = {
      tipo,
      rotulo: rotuloTipoSeparacao(tipo),
      volumes: this.volume(),
      pesaveis: this.conferred().some((i) => !!i.usaConfPeso && i.scanned > 0),
    };
    this.operacaoEmCurso = { tipo: 'etapa', etapa: tipo, info: infoEtapa };
    // Quem conclui vem do JWT no backend (call.exigirAuth()), não daqui.
    this.operacaoSub = this.separacaoService
      .concluirEtapa(this.tenantAtual, this.sessaoIdAtual, { tipoSeparacao: tipo, manterPendente, finalizarSemCorte, divergente })
      .subscribe({
        next: (res: ConcluirEtapaResultado) => this.aoConcluirEtapa(res, infoEtapa),
        error: (err) => {
          if (this.tratarPedidoAlterado(err)) return;
          if (err?.status === 409 && typeof err?.error?.pendentes === 'number') {
            this.encerrarOperacao();
            if (this.ehUltimaEtapaPendente()) {
              this.feedback.trigger('FINALIZACAO_DIVERGENTE');
              this.mostrarModalDivergencia.set(true);
            } else {
              this.feedback.trigger('DIVERGENCIA');
              this.mostrarModalAvisoEtapa.set(true);
            }
            return;
          }
          this.verificarConclusaoAntesDoErro(() => {
            this.encerrarOperacao();
            this.mostrarModalDivergencia.set(false);
            this.mostrarModalAvisoEtapa.set(false);
            // Mantém a conferência na tela pra tentar de novo (antes trocava tudo pela tela de erro de abertura).
            this.feedback.trigger('ERRO_SANKHYA', { mensagem: err?.error?.erro ?? 'Falha ao concluir a etapa.' });
          });
        },
      });
  }

  private aoConcluirEtapa(
    res: Pick<ConcluirEtapaResultado, 'conferenciaFinalizada' | 'nuconf'> & { aguardandoCorte: boolean | null },
    infoEtapa: { tipo: number; rotulo: string; volumes: number; pesaveis: boolean },
  ): void {
    this.encerrarLockLocal();
    this.encerrarOperacao();
    this.mostrarModalDivergencia.set(false);
    this.mostrarModalAvisoEtapa.set(false);
    const temEtiqueta = infoEtapa.volumes > 0 || infoEtapa.pesaveis;
    this.etapaImpressao.set(temEtiqueta ? infoEtapa : null);
    if (res.conferenciaFinalizada) {
      this.aposFinalizacao({ ok: true, aguardandoCorte: res.aguardandoCorte, nuconf: res.nuconf });
      return;
    }
    this.feedback.trigger('ETAPA_CONCLUIDA');
    if (temEtiqueta) {
      this.mostrarPainelEtapaConcluida.set(true);
    } else {
      this.router.navigate(['/fila-tarefas']);
    }
  }

  private encerrarOperacao(): void {
    this.operacaoSub?.unsubscribe();
    this.operacaoSub = undefined;
    this.operacaoEmCurso = null;
    this.concluindoEtapa = false;
    this.finalizando.set(false);
  }

  /**
   * O servidor já terminou esta conclusão? (resposta perdida na rede — bug real da nota 58213: tudo foi
   * pro Sankhya, mas o tablet ficou preso em "Enviando…" e o operador reabriu a nota). Se sim, abandona o
   * request pendente e segue como sucesso. Devolve true se recuperou.
   */
  private recuperarConclusao(c: ConclusaoServidor): boolean {
    const op = this.operacaoEmCurso;
    if (!op || !this.finalizando()) return false;
    if (op.tipo === 'etapa' && (c.conferenciaFinalizada || c.etapa === op.etapa)) {
      this.aoConcluirEtapa(c, op.info);
      return true;
    }
    if (op.tipo === 'finalizar' && c.conferenciaFinalizada) {
      this.aoFinalizar({ ok: true, aguardandoCorte: c.aguardandoCorte, nuconf: c.nuconf });
      return true;
    }
    return false;
  }

  /** Antes de mostrar erro de envio: confere uma vez se o servidor concluiu mesmo assim (ex.: conexão caiu). */
  private verificarConclusaoAntesDoErro(mostrarErro: () => void, tentativa = 0): void {
    const sessaoId = this.sessaoIdAtual;
    if (!sessaoId) return mostrarErro();
    this.separacaoService
      .progressoFinalizacao(this.tenantAtual, sessaoId)
      .pipe(catchError(() => of(null)))
      .subscribe((p) => {
        if (p?.concluido && this.recuperarConclusao(p.concluido)) return;
        // Servidor ainda finalizando (timeout do proxy / resposta perdida com o Sankhya lento — pedido 65331):
        // continua "Enviando…" e acompanha o progresso por até ~3 min, em vez de mostrar erro e o operador repetir.
        if (p?.fase && tentativa < 60) {
          setTimeout(() => this.verificarConclusaoAntesDoErro(mostrarErro, tentativa + 1), 3000);
          return;
        }
        mostrarErro();
      });
  }

  onCancelarDivergencia(): void {
    if (this.finalizando()) return;
    this.mostrarModalDivergencia.set(false);
  }

  private executarFinalizacao(semCorte = false): void {
    if (!this.sessaoIdAtual || this.finalizando()) return;
    this.finalizando.set(true);
    this.feedback.trigger('ENVIANDO_SANKHYA');
    this.operacaoEmCurso = { tipo: 'finalizar' };
    this.operacaoSub = this.separacaoService.finalizar(this.tenantAtual, this.sessaoIdAtual, semCorte).subscribe({
      next: (res) => this.aoFinalizar(res),
      error: (err) => {
        if (this.tratarPedidoAlterado(err)) return;
        this.verificarConclusaoAntesDoErro(() => {
          this.encerrarOperacao();
          this.mostrarModalDivergencia.set(false);
          // Mantém a conferência na tela pra tentar de novo (antes trocava tudo pela tela de erro de abertura).
          this.feedback.trigger('ERRO_SANKHYA', { mensagem: err?.error?.erro ?? 'Falha ao finalizar a conferência.' });
        });
      },
    });
  }

  private aoFinalizar(res: Omit<FinalizarResultado, 'aguardandoCorte'> & { aguardandoCorte: boolean | null }): void {
    this.encerrarLockLocal();
    this.encerrarOperacao();
    this.mostrarModalDivergencia.set(false);
    this.aposFinalizacao(res);
  }

  /**
   * Cadeia pós-finalização: liberação de corte → faturamento → painel "finalizada".
   * `aguardandoCorte: null` = desconhecido (recuperado só pelo status da sessão): vai direto ao painel —
   * se houver corte pendente, a nota aparece na tela de Liberação de Corte pelo sync.
   */
  private aposFinalizacao(res: Omit<FinalizarResultado, 'aguardandoCorte'> & { aguardandoCorte: boolean | null }): void {
    if (res.aguardandoCorte == null) {
      this.feedback.trigger('FINALIZACAO');
      this.mostrarPainelFinalizada.set(true);
      return;
    }
    // Corte automático/silencioso (pesável na tolerância) é decidido no backend e
    // chega aqui como finalização normal — nenhum alerta extra por regra operacional.
    if (res.aguardandoCorte && res.nuconf != null) {
      this.feedback.trigger('DIVERGENCIA'); // precisa de liberação manual de corte
      this.nuconfLiberacao = res.nuconf;
      this.mostrarModalLiberacaoCorte.set(true);
      return;
    }
    // A nota NÃO sai aqui: o fluxo é conferir → carregar → nota (usuário, 08/10/2026). O painel final
    // diz o próximo passo; a nota é gerada no card do pedido, na Fila, depois do carregamento.
    this.feedback.trigger('FINALIZACAO');
    this.mostrarPainelFinalizada.set(true);
  }

  onLiberacaoCorteFechada(): void {
    this.mostrarModalLiberacaoCorte.set(false);
    this.mostrarPainelFinalizada.set(true);
  }

  /** O modal consulta as TOPs e mostra o bloqueio (já faturada, corte pendente, recontagem) sozinho. */
  private abrirModalFaturamento(): void {
    if (!this.sessaoIdAtual) {
      this.mostrarPainelFinalizada.set(true);
      return;
    }
    this.mostrarModalFaturamento.set(true);
  }

  /** Nota confirmada nesta tela (pedido sem OC, que fatura direto do painel final). */
  readonly notaConfirmada = signal(false);

  /**
   * Próximo passo do fluxo, mostrado no painel final: com OC = carregar e depois gerar a nota na Fila
   * (dentro da OC); sem OC e CCO com faturamento = gerar a nota já aqui; senão, nada a fazer.
   */
  proximoPasso(): 'carregar' | 'nota' | null {
    if (this.ordemCargaTarefa != null) return 'carregar';
    // Sem OC: a nota sai aqui mesmo (TOP automática, fatura e confirma) — independe da CCO (usuário, 08/10/2026).
    if (!this.notaConfirmada()) return 'nota';
    return null;
  }

  /** NUNOTA do pedido aberto, pro modal de nota. */
  numeroUnicoNum(): number | null {
    const n = Number(this.numeroUnico);
    return n > 0 ? n : null;
  }

  faturarDoPainel(): void {
    this.mostrarPainelFinalizada.set(false);
    this.abrirModalFaturamento();
  }

  /** Volta pra Fila já dentro da OC deste pedido (carregar → gerar nota → fechar OC). */
  irParaOc(): void {
    this.router.navigate(['/fila-tarefas'], { queryParams: { oc: this.ordemCargaTarefa } });
  }

  fecharModalFaturamento(): void {
    this.mostrarModalFaturamento.set(false);
    this.mostrarPainelFinalizada.set(true);
  }

  /** Há item pesável já pesado na sessão — habilita "Imprimir etiqueta de peso" (tela e pop-up de finalização). */
  readonly temPesavelConferido = computed(() => {
    // `todosItensMapeados` só atualiza numa recarga completa dos itens (não a cada bipagem),
    // então o peso recém-pesado só aparece em `conferred` — olha os dois.
    const pesado = (i: ConferenciaItem) => !!i.usaConfPeso && i.scanned > 0;
    return this.conferred().some(pesado) || this.todosItensMapeados().some(pesado);
  });

  /** Etiquetas de volume DESTA etapa (numeração acumulada, sem total da nota). */
  imprimirVolumesEtapa(): void {
    const e = this.etapaImpressao();
    if (!this.sessaoIdAtual || !e) return;
    window.open(`/etiquetas/${this.sessaoIdAtual}?etapa=${e.tipo}`, '_blank');
  }

  /** Etiquetas de peso dos pesáveis DESTA etapa. */
  imprimirPesoEtapa(): void {
    const e = this.etapaImpressao();
    if (!this.sessaoIdAtual || !e) return;
    window.open(`/etiquetas-peso/${this.sessaoIdAtual}?etapa=${e.tipo}`, '_blank');
  }

  // Painel final: 3 botões só (volume, peso, finalizar). Em conferência por etapas os dois
  // primeiros imprimem a ÚLTIMA etapa (as anteriores já imprimiram no pop-up de fim de etapa);
  // sem etapas, imprimem a nota inteira.
  readonly mostrarBotaoVolumeFinal = computed(() => {
    const e = this.etapaImpressao();
    return e ? e.volumes > 0 : true;
  });
  readonly mostrarBotaoPesoFinal = computed(() => {
    const e = this.etapaImpressao();
    return e ? e.pesaveis : this.temPesavelConferido();
  });

  imprimirVolumesFinal(): void {
    if (this.etapaImpressao()) this.imprimirVolumesEtapa();
    else this.imprimirEtiquetas();
  }

  imprimirPesoFinal(): void {
    if (this.etapaImpressao()) this.imprimirPesoEtapa();
    else this.imprimirEtiquetaPeso();
  }

  continuarAposEtapa(): void {
    this.mostrarPainelEtapaConcluida.set(false);
    this.router.navigate(['/fila-tarefas']);
  }

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

  /** OC do pedido (vinda da fila) — pedido com OC passa pelo carregamento. */
  ordemCargaTarefa: number | null = null;

  /** Pop-up final: "✓ Já carregado" — um toque dá baixa no carregamento do pedido inteiro. */
  private voltarAoPainelFinal = false;
  readonly carregadoFinal = signal<'nao' | 'salvando' | 'ok'>('nao');
  irParaCarregamento(): void {
    if (this.carregadoFinal() !== 'nao' || !this.numeroUnico || this.numeroUnico === '—') return;
    this.carregadoFinal.set('salvando');
    this.reconferenciaService.carregarPedidos([this.numeroUnico]).subscribe({
      next: () => this.carregadoFinal.set('ok'),
      error: () => this.carregadoFinal.set('nao'),
    });
  }
}

/** Ordem alfabética pelo nome exibido do produto (pt-BR, sem diferenciar maiúscula/acento, números em ordem natural). */
function porNomeProduto(a: { name: string }, b: { name: string }): number {
  return a.name.localeCompare(b.name, 'pt-BR', { sensitivity: 'base', numeric: true });
}
