import { AfterViewInit, Component, ElementRef, EventEmitter, HostListener, Input, OnDestroy, Output, ViewChild, inject } from '@angular/core';
import { DecimalPipe } from '@angular/common';
import { ConferenciaItem } from '../conferencia.model';
import { FormsModule } from '@angular/forms';
import { Subscription } from 'rxjs';
import { OqIconComponent } from '../../shared/icons/oq-icon.component';
import { SeparacaoService } from '../../separacao/separacao.service';
import { ItemConferido, Uma } from '../../separacao/separacao.model';
import { BalancaService } from '../../balancas/balanca.service';
import { LocalScaleService, StatusBalanca } from '../../balancas/local-scale.service';
import { Balanca } from '../../balancas/balanca.model';
import { SomFeedbackService } from '../../shared/som-feedback.service';

export interface ProdutoIdentificadoEvento {
  codprod: number;
  descricaoProduto: string | null;
  imagemUrl: string | null;
}

/**
 * Fluxo real (portado do projeto base) — 3 campos encadeados, tudo
 * navegável só com TAB (Enter faz a mesma coisa, pra quem prefere):
 *
 * 1. Código de barras / ID do produto → identifica o produto (POST
 *    /identificar). Decide se o campo 2 vira <select> (produto de
 *    controle por LISTA) ou <input> livre (controle por LOTE — TIPCONTEST='L').
 * 2. Controle/Lote → Tab avança pra quantidade.
 * 3. Quantidade → Tab confirma de verdade (POST /conferir) e volta o foco
 *    pro campo 1, pronto pro próximo bipe.
 */
@Component({
  selector: 'oq-scan-bar',
  standalone: true,
  imports: [FormsModule, OqIconComponent, DecimalPipe],
  templateUrl: './oq-scan-bar.component.html',
  styleUrl: './oq-scan-bar.component.scss',
})
export class OqScanBarComponent implements AfterViewInit, OnDestroy {
  private readonly separacaoService = inject(SeparacaoService);
  private readonly balancaService = inject(BalancaService);
  private readonly localScale = inject(LocalScaleService);
  private readonly som = inject(SomFeedbackService);

  @Input({ required: true }) tenant!: string;
  @Input({ required: true }) sessaoId!: string;
  /** CCO.OBTERQTDBALANCA da sessão — 'N' (ou ausente) desativa o passo de peso, mesmo pra item usaConfPeso. */
  @Input() obterQtdBalanca: string | null = null;
  /** Etapa (tipo de separação) sendo conferida — item fora do pedido nasce nela. null = conferência normal. */
  @Input() etapa: number | null = null;
  /** Itens pendentes da sessão — usados só p/ mostrar "Esperado: X UN (Y CX)" no modal de peso (igual ao legado). */
  @Input() itensPendentes: ConferenciaItem[] = [];

  /** Item pendente correspondente ao produto identificado (p/ exibir unidade padrão + comercial no modal de peso). */
  get itemPendenteAtual(): ConferenciaItem | null {
    if (this.codprodAtual == null) return null;
    return this.itensPendentes.find((it) => it.code === String(this.codprodAtual)) ?? null;
  }

  /** true quando a unidade do pedido (comercial) difere da unidade base do produto — mostra a conversão "(X BI)". */
  get temUnidadeComercialDistinta(): boolean {
    const it = this.itemPendenteAtual;
    return !!it && !!it.unidadeComercial && it.unidadeComercial !== it.unidadePadrao && it.quantidadeComercial != null;
  }

  @Output() conferido = new EventEmitter<ItemConferido>();
  @Output() naoEncontrado = new EventEmitter<string>();
  @Output() identificado = new EventEmitter<ProdutoIdentificadoEvento>();
  /** Erro ao confirmar quantidade (ex.: excede o pendente e a CCO não permite) — mensagem já vem pronta do backend. */
  @Output() erroConferir = new EventEmitter<string>();

  @ViewChild('inputIdentificador') inputIdentificador?: ElementRef<HTMLInputElement>;
  @ViewChild('selectControle') selectControleRef?: ElementRef<HTMLSelectElement>;
  @ViewChild('inputControleLote') inputControleLoteRef?: ElementRef<HTMLInputElement>;
  @ViewChild('inputPeso') inputPesoRef?: ElementRef<HTMLInputElement>;
  @ViewChild('inputQtd') inputQtdRef?: ElementRef<HTMLInputElement>;

  codigo = '';
  controle = '';
  qtd = '1';
  peso = '';

  produtoIdentificado = false;
  controleModoLote = false;
  controlesDisponiveis: string[] = [];
  /** Só true nos 2 casos reais: produto sem controle nenhum, ou controle veio de ESTOQUE (EST) já definido — nunca um "palpite". */
  controleTravado = false;
  carregando = false;

  /** TGFVOL.UTILICONFPESO do produto identificado — rotina de peso portada do projeto base. */
  usaConfPesoAtual = false;
  capturandoPeso = false;

  /** Balanças disponíveis pro operador (listarMinhas) + a escolhida, persistida por estação. */
  balancas: Balanca[] = [];
  balancaSelecionadaId: string | null = null;
  private static readonly LS_BALANCA = 'wms_balanca_conf';
  /** Erro vindo do agente local (ex.: porta COM não configurada) — mostrado no modal de peso. */
  erroBalanca: string | null = null;
  private assinaturaErroBalanca?: Subscription;

  get balancaAtiva(): Balanca | null {
    return this.balancas.find((b) => b.id === this.balancaSelecionadaId) ?? null;
  }

  onBalancaChange(): void {
    if (this.balancaSelecionadaId) {
      try {
        localStorage.setItem(OqScanBarComponent.LS_BALANCA, this.balancaSelecionadaId);
      } catch {
        /* storage indisponível — segue sem lembrar */
      }
    }
    this.erroBalanca = null;
    if (this.mostrarModalPeso && this.modoEntradaPeso === 'balanca') {
      this.pararLeituraAoVivo();
      this.iniciarLeituraAoVivo();
    }
  }

  /** Unidade escanada (VOA) + código bipado — reenviados no /conferir p/ CODVOL/CODBARRA no Sankhya. */
  codvolEscanado: string | null = null;
  codigoBarraEscanado: string | null = null;

  /** UMAs da sessão (carregadas 1x) e as do produto identificado. */
  private umasDaSessao: Uma[] = [];
  umasDoProduto: Uma[] = [];
  /** null = "Sem UMA — qtd = peso direto" (1kg = 1un). */
  codUmaSelecionada: number | null = null;

  get umaSelecionada(): Uma | null {
    return this.umasDoProduto.find((u) => u.coduma === this.codUmaSelecionada) ?? null;
  }

  /**
   * Fórmula peso→qtd do projeto base (calcularQtdPorPeso):
   * com UMA (peso > 0) → round5(peso / uma.peso) ; sem UMA → peso (1kg = 1un).
   * NUNCA combina com fator/divideMultiplica.
   */
  private calcularQtdPorPeso(): number {
    const p = Number((this.peso || '0').replace(',', '.'));
    const up = this.umaSelecionada?.peso ? Number(this.umaSelecionada.peso) : 0;
    return up > 0 ? Number((p / up).toFixed(5)) : p;
  }

  /** Trocar a UMA com peso já capturado recalcula a quantidade. */
  onUmaChange(): void {
    if (this.peso.trim()) this.qtd = String(this.calcularQtdPorPeso());
  }

  /** true = mostra o campo de peso (produto exige E a CCO da sessão pede peso da balança). */
  get precisaPeso(): boolean {
    return this.usaConfPesoAtual && !!this.obterQtdBalanca && this.obterQtdBalanca !== 'N';
  }

  ngAfterViewInit(): void {
    this.focarIdentificador();
    // Busca 1x, fica em memória — evita round-trip a cada bipe. Só importa
    // quando alguma sessão realmente pedir peso (usaConfPesoAtual).
    this.balancaService.listarMinhas().subscribe({
      next: (balancas) => {
        this.balancas = balancas;
        let lembrada: string | null = null;
        try {
          lembrada = localStorage.getItem(OqScanBarComponent.LS_BALANCA);
        } catch {
          /* storage indisponível */
        }
        const existe = balancas.some((b) => b.id === lembrada);
        // Estação lembra a balança escolhida; sem lembrança, só auto-seleciona
        // se houver exatamente uma (com 7, o operador precisa escolher).
        this.balancaSelecionadaId = existe ? lembrada : balancas.length === 1 ? balancas[0].id : null;
      },
      error: () => {
        this.balancas = [];
        this.balancaSelecionadaId = null;
      },
    });
    // UMAs da sessão — 1x, filtradas por produto no identificar.
    this.separacaoService.buscarUma(this.tenant, this.sessaoId).subscribe({
      next: (umas) => (this.umasDaSessao = umas),
      error: () => (this.umasDaSessao = []),
    });
  }

  onQtdInput(valor: string): void {
    this.qtd = valor.replace(/[^\d,.]/g, '');
  }

  /**
   * O operador clicou num item da lista de pendentes — identifica o produto
   * direto por CODPROD, sem digitar nem bipar o código (elimina o passo do
   * código de barras no mobile). O resto do fluxo (controle → qtd/peso) segue
   * igual.
   */
  identificarPorCodprod(codprod: number): void {
    if (this.carregando || this.mostrarModalPeso) return;
    this.resetarTudo();
    this.codprodDaLista = codprod;
    this.codigo = String(codprod);
    this.onIdentificadorTab();
  }

  /** Set != null enquanto uma identificação veio de clique na lista (não de bipe). */
  private codprodDaLista: number | null = null;

  /** Passo 1: identifica o produto a partir do código bipado/digitado (ou do clique na lista). */
  onIdentificadorTab(): void {
    const codigo = this.codigo.trim();
    if (!codigo || this.carregando) return;

    this.carregando = true;
    this.separacaoService
      .identificarProduto(this.tenant, this.sessaoId, codigo, this.codprodDaLista ?? undefined, this.etapa ?? undefined)
      .subscribe({
      next: (resultado) => {
        this.carregando = false;
        this.produtoIdentificado = true;
        this.codprodAtual = resultado.codprod;
        this.controleModoLote = resultado.controleModoLote;
        this.controlesDisponiveis = resultado.controlesDisponiveis;
        this.controleTravado = resultado.controleTravado;
        this.usaConfPesoAtual = resultado.usaConfPeso;
        this.codvolEscanado = resultado.codvol ?? null;
        // Clique na lista não tem código de barras real — não manda um CODPROD como CODBARRA.
        this.codigoBarraEscanado = this.codprodDaLista != null ? null : codigo;

        // UMAs do produto + default na UMA marcada como padrão (casa por CODPROD, igual ao legado).
        this.umasDoProduto = this.umasDaSessao.filter((u) => u.codprod === resultado.codprod);
        this.codUmaSelecionada = (this.umasDoProduto.find((u) => u.padrao) ?? this.umasDoProduto[0])?.coduma ?? null;

        if (resultado.controleAutoSelecionado != null) {
          this.controle = resultado.controleAutoSelecionado;
        } else {
          this.controle = '';
        }

        // Travado (sem controle, ou veio certo do estoque) pula direto pro
        // próximo passo (peso, se exigido, senão quantidade) — não faz
        // sentido focar um campo que o operador não pode mexer.
        if (this.controleTravado) {
          this.avancarAposControle();
        } else {
          this.som.tocar('atencao'); // precisa escolher/digitar o controle — chama atenção do operador
          this.focarControle();
        }

        this.identificado.emit({
          codprod: resultado.codprod,
          descricaoProduto: resultado.descricaoProduto,
          imagemUrl: resultado.imagemBase64,
        });
      },
      error: () => {
        this.carregando = false;
        this.som.tocar('erro');
        this.naoEncontrado.emit(codigo);
        this.resetarTudo();
        this.focarIdentificador();
      },
    });
  }

  /** Passo 2: controle escolhido/digitado — abre o modal de peso (se exigido) ou avança direto pra quantidade. */
  onControleTab(): void {
    this.avancarAposControle();
  }

  private avancarAposControle(): void {
    if (this.precisaPeso && !this.peso.trim()) {
      this.abrirModalPeso();
    } else {
      this.focarQtd();
    }
  }

  // ─── Modal de peso (portado do projeto base) ─────────────────────────────

  mostrarModalPeso = false;
  modoEntradaPeso: 'manual' | 'balanca' = 'manual';
  pesoAoVivo: number | null = null;
  /** Status da conexão com o agente local (badge do painel de balança). */
  statusBalanca: StatusBalanca = 'desconectado';
  /** Captura automática quando o peso estabiliza (2 s) — ligada por padrão, igual ao legado. */
  capturaAutoAtiva = true;
  private assinaturaPesoAoVivo?: Subscription;
  private assinaturaPesoEstavel?: Subscription;
  private assinaturaStatusBalanca?: Subscription;

  abrirModalPeso(): void {
    this.mostrarModalPeso = true;
    this.modoEntradaPeso = this.balancaAtiva && this.balancaAtiva.tipoComunicacao !== 'HTTP' ? 'balanca' : 'manual';
    if (this.modoEntradaPeso === 'balanca') {
      this.iniciarLeituraAoVivo();
    } else {
      this.focarPeso();
    }
  }

  /** Fecha o modal sem capturar (Cancelar / X / Esc) — volta o foco pro fluxo. */
  fecharModalPeso(): void {
    this.pararLeituraAoVivo();
    this.mostrarModalPeso = false;
    if (this.precisaPeso && !this.peso.trim()) this.focarIdentificador();
    else this.focarQtd();
  }

  alternarModoPeso(modo: 'manual' | 'balanca'): void {
    this.modoEntradaPeso = modo;
    if (modo === 'balanca') {
      this.iniciarLeituraAoVivo();
    } else {
      this.pararLeituraAoVivo();
      this.focarPeso();
    }
  }

  /** Assina o peso contínuo do agente local — só faz sentido pra balança não-HTTP (serial/TCP). HTTP é one-shot (obterPeso). */
  private iniciarLeituraAoVivo(): void {
    if (!this.balancaAtiva || this.balancaAtiva.tipoComunicacao === 'HTTP' || !this.balancaAtiva.portaCom) return;
    this.erroBalanca = null;
    this.statusBalanca = this.localScale.obterStatus();
    this.localScale.conectar();
    this.localScale.subscribe(this.balancaAtiva.portaCom);
    this.assinaturaStatusBalanca?.unsubscribe();
    this.assinaturaStatusBalanca = this.localScale.status$.subscribe((s) => {
      this.statusBalanca = s;
      // Reconexão do WS — reassina a porta pra voltar a receber leituras.
      if (s === 'conectado' && this.balancaAtiva?.portaCom) this.localScale.subscribe(this.balancaAtiva.portaCom);
    });
    this.assinaturaErroBalanca?.unsubscribe();
    this.assinaturaErroBalanca = this.localScale.erro$.subscribe((msg) => {
      if (this.mostrarModalPeso && this.modoEntradaPeso === 'balanca') this.erroBalanca = msg;
    });
    this.assinaturaPesoAoVivo?.unsubscribe();
    this.assinaturaPesoAoVivo = this.localScale.peso$.subscribe((leitura) => {
      this.pesoAoVivo = leitura.peso;
      this.erroBalanca = null;
    });
    // Auto-captura por estabilidade (LocalScaleService já debounce 2s / 0.005kg,
    // com tara e auto-untare) — mesma UX do projeto base, mas só com o switch ligado.
    this.assinaturaPesoEstavel?.unsubscribe();
    this.assinaturaPesoEstavel = this.localScale.pesoEstavel$.subscribe((leitura) => {
      if (!this.mostrarModalPeso || this.modoEntradaPeso !== 'balanca' || !this.capturaAutoAtiva) return;
      if (leitura.peso < 0.001) return;
      this.peso = leitura.peso.toFixed(3);
      this.confirmarPeso();
    });
  }

  private pararLeituraAoVivo(): void {
    this.assinaturaPesoAoVivo?.unsubscribe();
    this.assinaturaPesoAoVivo = undefined;
    this.assinaturaPesoEstavel?.unsubscribe();
    this.assinaturaPesoEstavel = undefined;
    this.assinaturaErroBalanca?.unsubscribe();
    this.assinaturaErroBalanca = undefined;
    this.assinaturaStatusBalanca?.unsubscribe();
    this.assinaturaStatusBalanca = undefined;
    if (this.balancaAtiva?.portaCom) this.localScale.unsubscribe(this.balancaAtiva.portaCom);
    this.pesoAoVivo = null;
  }

  /** Botão "atualizar status" / "reconectar" do painel de balança. */
  reconectarBalanca(): void {
    this.pararLeituraAoVivo();
    this.iniciarLeituraAoVivo();
  }

  /** Usa o peso ao vivo exibido no momento — modo balança contínua (serial/TCP). Captura + confirma (deriva a qtd). */
  capturarPesoAoVivo(): void {
    if (this.pesoAoVivo == null) return;
    this.peso = this.pesoAoVivo.toFixed(3);
    this.som.tocar('atencao');
    this.confirmarPeso();
  }

  tararBalanca(): void {
    if (this.pesoAoVivo == null) return;
    this.localScale.tarar(this.pesoAoVivo);
  }

  /** "Obter da balança" — só pra HTTP (one-shot, lido pelo servidor). Serial/TCP usa a leitura contínua (capturarPesoAoVivo). */
  obterPeso(): void {
    if (!this.balancaAtiva || this.balancaAtiva.tipoComunicacao !== 'HTTP' || this.capturandoPeso) return;
    this.capturandoPeso = true;
    this.balancaService.capturarPeso(this.balancaAtiva.id).subscribe({
      next: (r) => {
        this.capturandoPeso = false;
        this.peso = String(r.peso);
        this.som.tocar('atencao');
      },
      error: () => (this.capturandoPeso = false),
    });
  }

  /**
   * Captura o peso: deriva a quantidade (peso / uma.peso, ou peso direto),
   * fecha o modal e JÁ CONFERE o item — o operador não passa pelo campo de
   * quantidade num item pesável (mesma UX do projeto base). Só para quando
   * ainda falta o Nº do lote (controle livre).
   */
  confirmarPeso(): void {
    if (!this.peso.trim()) return;
    this.qtd = String(this.calcularQtdPorPeso());
    this.pararLeituraAoVivo();
    this.mostrarModalPeso = false;

    if (this.controleModoLote && !this.controle.trim()) {
      this.focarControle();
      return;
    }
    this.onQtdTab();
  }

  /** Passo 4 (final): confirma a quantidade de verdade. */
  onQtdTab(): void {
    const qtdN = Number(this.qtd.replace(',', '.'));
    if (!this.produtoIdentificado || this.codprodAtual == null || !qtdN || qtdN <= 0) {
      // Item pesável: qtd inválida = peso inválido → reabre o modal de peso
      // (o campo de qtd é readonly, não adianta focar nele).
      if (this.precisaPeso) this.abrirModalPeso();
      else this.focarQtd();
      return;
    }
    if (this.controleModoLote && !this.controle.trim()) {
      this.focarControle();
      return;
    }
    if (this.precisaPeso && !this.peso.trim()) {
      this.abrirModalPeso();
      return;
    }
    if (this.carregando) return;

    const pesoN = this.precisaPeso ? Number(this.peso.replace(',', '.')) : undefined;
    this.carregando = true;
    this.separacaoService
      .conferir(
        this.tenant,
        this.sessaoId,
        this.codprodAtual,
        this.controle,
        qtdN,
        pesoN,
        this.codvolEscanado,
        this.codigoBarraEscanado,
      )
      .subscribe({
      next: (resultado) => {
        this.carregando = false;
        this.som.tocar('ok');
        this.conferido.emit(resultado);
        this.resetarTudo();
        this.focarIdentificador();
      },
      error: (err) => {
        this.carregando = false;
        this.som.tocar('invalido');
        this.erroConferir.emit(err?.error?.erro ?? 'Falha ao confirmar a quantidade.');
        this.resetarTudo();
        this.focarIdentificador();
      },
    });
  }

  private codprodAtual: number | null = null;

  ngOnDestroy(): void {
    this.pararLeituraAoVivo();
  }

  // ─── Atalhos de teclado + sniffer do leitor físico (portados do projeto base) ──

  private bufferSniffer = '';
  private ultimoKeydownSniffer = 0;
  private static readonly JANELA_SNIFFER_MS = 100;

  /**
   * Atalhos globais (F2/F3/F9/Esc) + sniffer do leitor físico de código de
   * barras. O sniffer só entra em ação quando o foco NÃO está num dos nossos
   * próprios campos de digitação (controle/peso/qtd) — evita atropelar
   * digitação manual legítima nesses campos; cobre o caso real que importa:
   * o leitor bipar enquanto o foco ficou em botão/body por qualquer motivo
   * (ex.: depois de fechar um popup).
   */
  @HostListener('window:keydown', ['$event'])
  onWindowKeydown(event: KeyboardEvent): void {
    if (event.ctrlKey || event.altKey || event.metaKey) return;

    switch (event.key) {
      case 'F2':
        event.preventDefault();
        this.focarIdentificador();
        return;
      case 'F3':
        event.preventDefault();
        if (this.produtoIdentificado) this.focarQtd();
        return;
      case 'F9':
        event.preventDefault();
        this.onQtdTab();
        return;
      case 'Escape':
        event.preventDefault();
        if (this.mostrarModalPeso) {
          this.pararLeituraAoVivo();
          this.mostrarModalPeso = false;
          return;
        }
        this.resetarTudo();
        this.focarIdentificador();
        return;
    }

    const alvo = event.target as HTMLElement | null;
    const emCampoProprio =
      alvo === this.inputIdentificador?.nativeElement || // já trata o próprio Enter — evita processar 2x
      alvo === this.inputPesoRef?.nativeElement ||
      alvo === this.inputQtdRef?.nativeElement ||
      alvo === this.selectControleRef?.nativeElement ||
      alvo === this.inputControleLoteRef?.nativeElement;
    if (emCampoProprio) return; // digitação manual legítima nesses campos — não interceptar

    const agora = Date.now();
    const diff = agora - this.ultimoKeydownSniffer;
    this.ultimoKeydownSniffer = agora;

    if (event.key === 'Enter') {
      // Só trata como leitura de scanner se o buffer foi montado por
      // digitação RÁPIDA (< 100ms entre teclas) — digitação humana normal
      // não sustenta essa cadência, então não é confundida com bipe.
      if (this.bufferSniffer.length >= 3 && diff < OqScanBarComponent.JANELA_SNIFFER_MS) {
        event.preventDefault();
        this.codigo = this.bufferSniffer;
        this.bufferSniffer = '';
        this.onIdentificadorTab();
      } else {
        this.bufferSniffer = '';
      }
      return;
    }

    if (event.key.length === 1) {
      this.bufferSniffer = diff < OqScanBarComponent.JANELA_SNIFFER_MS ? this.bufferSniffer + event.key : event.key;
    }
  }

  private resetarTudo(): void {
    this.codigo = '';
    this.controle = '';
    this.qtd = '1';
    this.peso = '';
    this.produtoIdentificado = false;
    this.controleModoLote = false;
    this.controlesDisponiveis = [];
    this.controleTravado = false;
    this.usaConfPesoAtual = false;
    this.codprodAtual = null;
    this.codvolEscanado = null;
    this.codigoBarraEscanado = null;
    this.codprodDaLista = null;
    this.umasDoProduto = [];
    this.codUmaSelecionada = null;
    if (this.mostrarModalPeso) {
      this.pararLeituraAoVivo();
      this.mostrarModalPeso = false;
    }
  }

  private focarIdentificador(): void {
    setTimeout(() => this.inputIdentificador?.nativeElement.focus());
  }

  private focarControle(): void {
    setTimeout(() => {
      if (this.controleModoLote) {
        this.inputControleLoteRef?.nativeElement.focus();
      } else {
        this.selectControleRef?.nativeElement.focus();
      }
    });
  }

  private focarPeso(): void {
    setTimeout(() => {
      this.inputPesoRef?.nativeElement.focus();
      this.inputPesoRef?.nativeElement.select();
    });
  }

  private focarQtd(): void {
    setTimeout(() => {
      this.inputQtdRef?.nativeElement.focus();
      this.inputQtdRef?.nativeElement.select();
    });
  }
}
