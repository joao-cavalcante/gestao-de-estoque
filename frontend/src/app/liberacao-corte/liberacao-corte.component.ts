import { Component, OnDestroy, OnInit, computed, inject, signal } from '@angular/core';
import { ActivatedRoute, Router } from '@angular/router';
import { NgTemplateOutlet } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { LiberacaoCorteService } from './liberacao-corte.service';
import { ConferenciaAguardandoCorte, LiberacaoPendente } from './liberacao-corte.model';
import { OqIconComponent } from '../shared/icons/oq-icon.component';
import { OqSpinnerComponent } from '../shared/icons/oq-spinner.component';
import { ActionFeedbackService } from '../shared/action-feedback/action-feedback.service';
import { OqFechamentoOcModalComponent } from '../fila-tarefas/oq-fechamento-oc-modal/oq-fechamento-oc-modal.component';
import { EntradaService, Recebimento, ddmm } from '../entrada/entrada.service';
import { MesaRecebimentoComponent } from '../entrada/mesa-recebimento.component';

/** Filtro da lista única (19/11/2026). */
type TipoLiberacao = 'todos' | 'corte' | 'recebimento';

/**
 * Liberações (19/11/2026: corte + recebimento numa tela só, pedido do usuário — "as telas de liberação meio que são a
 * mesma coisa"). Lista única com filtro Todos/Corte/Recebimento; o detalhe à direita é o corte (abaixo) ou a mesa do
 * recebimento (oq-mesa-recebimento, só leitura até o addon). O login do liberador vale para a tela inteira.
 *
 * Mesa de liberação de corte (17/11/2026 — antes era a grade de cards da Fila + o pop-up).
 *
 * Fluxo, a pedido do usuário:
 *  1. Ao ENTRAR na tela, login do liberador no Sankhya (LiberacaoLimitesSP.validarSenhaUsuario via
 *     /validar-liberador) — sempre: o operador entra na tela e entrega o tablet ao supervisor. Nada da fila aparece
 *     antes disso. Usuário/senha ficam só na memória deste componente (nunca no navegador) e somem ao sair da tela
 *     ou em "Trocar liberador".
 *  2. Fila de corte à esquerda; o corte aberto à direita (itens de /pendentes).
 *  3. Liberar/Negar mandam as credenciais do liberador (o backend valida de novo no Sankhya a cada envio).
 *
 * O pop-up `oq-liberacao-corte-modal` (usado na Conferência) e `liberacao-corte.service/model` NÃO mudam — a mesa
 * usa os mesmos métodos do serviço e reproduz as mesmas regras do pop-up (Negar só com todos os itens marcados;
 * conferência sem itens pendentes some da fila e a lista é revalidada).
 */
@Component({
  selector: 'app-liberacao-corte',
  standalone: true,
  imports: [NgTemplateOutlet, FormsModule, OqIconComponent, OqSpinnerComponent, OqFechamentoOcModalComponent, MesaRecebimentoComponent],
  templateUrl: './liberacao-corte.component.html',
  styleUrl: './liberacao-corte.component.scss',
})
export class LiberacaoCorteComponent implements OnInit, OnDestroy {
  /** 25/11/2026: a lista carrega sem login; abrir um corte ou recebimento pede o liberador. */
  ngOnInit(): void {
    this.carregar();
  }

  /** Há um corte/recebimento escolhido, mas ainda sem liberador conectado: o painel mostra o login. */
  readonly precisaLogin = computed(() => !this.liberado() && (this.selecionada() != null || this.recebimentoAberto() != null));

  readonly alvoLogin = computed(() => {
    const c = this.selecionada();
    if (c) return `o corte ${c.nunota}`;
    const r = this.recebimentoAberto();
    return r != null ? `o recebimento ${r}` : 'a liberação';
  });

  private readonly service = inject(LiberacaoCorteService);
  private readonly feedback = inject(ActionFeedbackService);
  private readonly entradaService = inject(EntradaService);
  private readonly router = inject(Router);
  readonly ddmm = ddmm;

  // ─── Recebimento (19/11/2026) ───────────────────────────────────────────────
  readonly tipo = signal<TipoLiberacao>('todos');
  readonly recebimentos = signal<Recebimento[]>([]);
  readonly mostrarLiberadas = signal(false);
  /** Recebimento aberto à direita (exclusivo com o corte aberto). Link /liberacoes/recebimento/:nunota abre direto. */
  readonly recebimentoAberto = signal<number | null>(Number(inject(ActivatedRoute).snapshot.paramMap.get('nunota')) || null);

  private filtrarRecebimentos(l: Recebimento[]): Recebimento[] {
    const termo = this.buscaTermo().trim().toLowerCase();
    return termo
      ? l.filter((r) => [r.nunota, r.numeroNota, r.fornecedor].some((v) => v != null && String(v).toLowerCase().includes(termo)))
      : l;
  }

  readonly recebimentosAguardando = computed(() =>
    // Salvaguarda (25/11/2026): compra confirmada com ajuste de peso não emitido continua na lista até o ajuste sair.
    this.filtrarRecebimentos(
      this.recebimentos().filter((r) => r.situacao === 'aguardando_liberacao' || !!r.ajustePendente).sort((a, b) => a.nunota - b.nunota),
    ),
  );
  readonly recebimentosLiberados = computed(() =>
    this.filtrarRecebimentos(this.recebimentos().filter((r) => r.situacao === 'liberada' && !r.ajustePendente).sort((a, b) => b.nunota - a.nunota)),
  );
  readonly qtdRecebimento = computed(() => this.recebimentos().filter((r) => r.situacao === 'aguardando_liberacao' || !!r.ajustePendente).length);
  readonly qtdAguardando = computed(() => this.lista().length + this.qtdRecebimento());

  /** Mensagem da lista vazia conforme o filtro (null = tem o que mostrar). */
  readonly vazioTexto = computed<string | null>(() => {
    const busca = !!this.buscaTermo().trim();
    const cortes = this.listaFiltrada().length;
    const receb = this.recebimentosAguardando().length;
    switch (this.tipo()) {
      case 'corte':
        return cortes ? null : busca ? 'Nenhum corte com essa busca.' : 'Nenhum corte aguardando liberação.';
      case 'recebimento':
        return receb ? null : busca ? 'Nenhum recebimento com essa busca.' : 'Nenhum recebimento aguardando liberação.';
      default:
        return cortes + receb ? null : busca ? 'Nada com essa busca.' : 'Nada aguardando liberação.';
    }
  });

  private carregarRecebimentos(): void {
    this.entradaService.listar().subscribe({
      next: (l) => {
        this.recebimentos.set(l);
        const aberto = this.recebimentoAberto();
        if (aberto && l.some((r) => r.nunota === aberto && r.situacao === 'liberada')) this.mostrarLiberadas.set(true);
      },
      error: (err) => this.erro.set(err?.error?.erro ?? 'Falha ao carregar os recebimentos.'),
    });
  }

  abrirRecebimento(r: Recebimento): void {
    this.fecharCorte();
    this.recebimentoAberto.set(r.nunota);
    this.router.navigate(['/liberacoes/recebimento', r.nunota], { replaceUrl: true });
  }

  fecharRecebimento(): void {
    this.recebimentoAberto.set(null);
    this.router.navigate(['/liberacoes'], { replaceUrl: true });
  }

  // ─── 1) Login do liberador (a cada entrada na tela) ─────────────────────────
  readonly liberado = signal(false);
  usuario = '';
  senha = '';
  mostrarSenha = false;
  readonly autenticando = signal(false);
  readonly erroAutenticacao = signal<string | null>(null);
  /** Nome digitado do liberador, exibido no topo da mesa (a senha nunca é exibida). */
  readonly liberadorNome = signal('');

  get podeEntrar(): boolean {
    return !!this.usuario.trim() && !!this.senha && !this.autenticando();
  }

  entrar(): void {
    if (!this.podeEntrar) return;
    this.autenticando.set(true);
    this.erroAutenticacao.set(null);
    this.service.validarLiberador({ usuario: this.usuario.trim(), senha: this.senha }).subscribe({
      next: () => {
        this.autenticando.set(false);
        this.liberadorNome.set(this.usuario.trim());
        this.liberado.set(true);
        // Abre o que estava escolhido quando o login foi pedido (corte; o recebimento abre sozinho).
        const alvo = this.selecionada();
        if (alvo) this.abrir(alvo);
      },
      error: (err) => {
        this.autenticando.set(false);
        this.erroAutenticacao.set(err?.error?.erro ?? 'Usuário ou senha inválidos.');
        this.feedback.trigger('OPERACAO_NAO_PERMITIDA');
      },
    });
  }

  /** Volta para o login e apaga as credenciais da memória (ex.: supervisor devolve o tablet). */
  trocarLiberador(): void {
    this.limparCredenciais();
    this.liberado.set(false);
    this.selecionada.set(null);
    this.pendentes.set([]);
    this.selecionadas.set(new Set());
    this.erroAcao.set(null);
    this.mensagem.set(null);
    if (this.recebimentoAberto() != null) this.fecharRecebimento();
  }

  ngOnDestroy(): void {
    this.limparCredenciais();
  }

  private limparCredenciais(): void {
    this.usuario = '';
    this.senha = '';
    this.mostrarSenha = false;
    this.liberadorNome.set('');
  }

  // ─── 2) Fila de corte ────────────────────────────────────────────────────────
  readonly lista = signal<ConferenciaAguardandoCorte[]>([]);
  readonly carregando = signal(false);
  readonly erro = signal<string | null>(null);
  readonly buscaTermo = signal('');

  readonly listaFiltrada = computed(() => {
    const termo = this.buscaTermo().trim().toLowerCase();
    if (!termo) return this.lista();
    return this.lista().filter(
      (item) =>
        String(item.numeroNota ?? '').includes(termo) ||
        String(item.nunota).includes(termo) ||
        !!item.nomeParceiro?.toLowerCase().includes(termo) ||
        !!item.descricaoTipoOperacao?.toLowerCase().includes(termo),
    );
  });

  carregar(): void {
    this.carregando.set(true);
    this.erro.set(null);
    this.carregarRecebimentos();
    this.service.listar().subscribe({
      next: (l) => {
        this.lista.set(l);
        this.carregando.set(false);
        const atual = this.selecionada();
        if (atual && !l.some((c) => c.nunota === atual.nunota)) this.fecharCorte();
      },
      error: (err) => {
        this.erro.set(err?.error?.erro ?? 'Falha ao carregar a lista.');
        this.carregando.set(false);
      },
    });
  }

  // ─── 3) Corte aberto ─────────────────────────────────────────────────────────
  readonly selecionada = signal<ConferenciaAguardandoCorte | null>(null);
  readonly pendentes = signal<LiberacaoPendente[]>([]);
  readonly selecionadas = signal<ReadonlySet<number>>(new Set());
  readonly carregandoPendentes = signal(false);
  readonly processando = signal(false);
  readonly erroAcao = signal<string | null>(null);
  readonly mensagem = signal<string | null>(null);
  obs = '';

  /**
   * Faturar depois de liberar (17/11/2026): a liberação fechou todo o corte e a configuração de conferência pede
   * faturamento ao concluir (TGFCCO.FATAOCONCLUIR = 'S', devolvido pelo /liberar como `fatAoConcluir`). Não é
   * parâmetro novo — o pop-up de corte já calculava isso, mas ninguém usava. Abre o MESMO modal de faturamento da
   * Fila ("Gerar nota") — oq-fechamento-oc-modal com [nunota].
   */
  readonly faturarOferta = signal<number | null>(null);
  readonly faturando = signal<number | null>(null);

  gerarNotaAgora(): void {
    const nunota = this.faturarOferta();
    if (nunota == null) return;
    this.faturarOferta.set(null);
    this.faturando.set(nunota);
  }

  /** "Agora não" ou fim do faturamento: tira o pedido da fila e revalida (a nota também sai pela Fila → Gerar nota). */
  encerrarFaturamento(): void {
    const nunota = this.faturando() ?? this.faturarOferta();
    this.faturando.set(null);
    this.faturarOferta.set(null);
    if (nunota != null) this.removerDaFila(nunota, 0);
  }

  abrir(item: ConferenciaAguardandoCorte): void {
    if (this.recebimentoAberto() != null) this.fecharRecebimento();
    this.faturarOferta.set(null);
    this.erroAcao.set(null);
    this.mensagem.set(null);
    this.obs = '';
    this.selecionadas.set(new Set());
    this.pendentes.set([]);
    this.selecionada.set(item);
    if (!this.liberado()) return; // o painel pede o login; depois dele, entrar() reabre este corte
    if (item.nuconf == null) {
      this.erroAcao.set(`Nº único ${item.nunota}: NUCONF não encontrado localmente — a conferência não passou pelo WMS.`);
      return;
    }
    this.carregandoPendentes.set(true);
    const nuconf = item.nuconf;
    this.service.pendentes(nuconf).subscribe({
      next: (itens) => {
        if (this.selecionada()?.nuconf !== nuconf) return; // trocou de pedido no meio da consulta
        this.pendentes.set(itens);
        this.carregandoPendentes.set(false);
        if (itens.length === 0) {
          // Conferência "presa": sem itens para liberar (mesma regra do pop-up) — some da fila e revalida.
          this.mensagem.set('Nenhum item pendente — a conferência já foi liberada. Atualizando…');
          this.removerDaFila(item.nunota);
        }
      },
      error: (err) => {
        this.carregandoPendentes.set(false);
        this.erroAcao.set(err?.error?.erro ?? 'Falha ao carregar os itens pendentes.');
        this.feedback.trigger('ERRO_SANKHYA', { toast: false });
      },
    });
  }

  /** Fecha o corte aberto (no celular, volta para a fila). */
  fecharCorte(): void {
    this.selecionada.set(null);
    this.pendentes.set([]);
    this.selecionadas.set(new Set());
    this.erroAcao.set(null);
    this.mensagem.set(null);
  }

  itemSelecionado(seq: number): boolean {
    return this.selecionadas().has(seq);
  }

  alternarItem(seq: number): void {
    const proximo = new Set(this.selecionadas());
    if (proximo.has(seq)) proximo.delete(seq);
    else proximo.add(seq);
    this.selecionadas.set(proximo);
  }

  get todosMarcados(): boolean {
    return this.pendentes().length > 0 && this.selecionadas().size === this.pendentes().length;
  }

  alternarTodos(): void {
    this.selecionadas.set(this.todosMarcados ? new Set() : new Set(this.pendentes().map((p) => p.sequencia)));
  }

  /**
   * Mesma regra do pop-up: "Negar" só como ÚLTIMA ação — assim que um item é negado o Sankhya manda a conferência
   * para "Aguardando recontagem" e recusa qualquer liberação depois (nota 57251). Só com TODOS os pendentes marcados.
   */
  get podeNegar(): boolean {
    return this.selecionadas().size > 0 && this.selecionadas().size === this.pendentes().length;
  }

  liberarOuNegar(liberar: 'S' | 'N'): void {
    const item = this.selecionada();
    if (!item || item.nuconf == null) return;
    if (liberar === 'N' && !this.podeNegar) return;
    const sequencias = [...this.selecionadas()];
    if (!sequencias.length || this.processando()) return;
    this.processando.set(true);
    this.erroAcao.set(null);
    this.mensagem.set(null);
    this.service
      .liberar({ nuconf: item.nuconf, usuario: this.usuario.trim(), senha: this.senha, liberar, sequencias, obs: this.obs || undefined })
      .subscribe({
        next: (res) => {
          this.processando.set(false);
          const n = res.itensProcessados;
          const verbo = liberar === 'S' ? (n === 1 ? 'liberado' : 'liberados') : n === 1 ? 'negado' : 'negados';
          this.mensagem.set(`${n} ${n === 1 ? 'item' : 'itens'} ${verbo}.`);
          this.feedback.trigger(liberar === 'S' ? 'CORTE_LIBERADO' : 'CORTE_NEGADO');
          this.pendentes.update((arr) => arr.filter((p) => !sequencias.includes(p.sequencia)));
          this.selecionadas.set(new Set());
          this.obs = '';
          if (this.pendentes().length === 0) {
            // Só liberar fecha a conferência; negar manda para recontagem — nada a faturar (mesma regra do pop-up).
            if (liberar === 'S' && res.fatAoConcluir === 'S') this.faturarOferta.set(item.nunota);
            else this.removerDaFila(item.nunota);
          }
        },
        error: (err) => {
          this.processando.set(false);
          this.erroAcao.set(err?.error?.erro ?? 'Falha ao processar a liberação.');
          this.feedback.trigger('ERRO_SANKHYA', { toast: false });
        },
      });
  }

  /** Tira da fila e revalida no backend (libera parcial/negar pode manter a conferência — ela volta). */
  private removerDaFila(nunota: number, esperaMs = 1400): void {
    setTimeout(() => {
      this.lista.update((l) => l.filter((c) => c.nunota !== nunota));
      if (this.selecionada()?.nunota === nunota) this.fecharCorte();
      setTimeout(() => this.carregar(), 600);
    }, esperaMs);
  }

  // ─── Formatação ──────────────────────────────────────────────────────────────
  /** 11.35 → "11,35"; inteiro sem casas. */
  num(v: number | null | undefined): string {
    if (v == null) return '—';
    return v.toLocaleString('pt-BR', { maximumFractionDigits: 3 });
  }

  /** Corte com sinal: −0,65 / +1. */
  dif(v: number | null | undefined): string {
    if (v == null) return '—';
    const s = Math.abs(v).toLocaleString('pt-BR', { maximumFractionDigits: 3 });
    return v < 0 ? `−${s}` : v > 0 ? `+${s}` : '0';
  }

  /** Nada conferido do item: o corte é o item inteiro. */
  itemInteiro(p: LiberacaoPendente): boolean {
    return (p.qtdConferida ?? 0) === 0 && (p.qtdPedido ?? 0) > 0;
  }
}
