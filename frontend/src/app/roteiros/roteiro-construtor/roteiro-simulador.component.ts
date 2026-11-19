import { Component, OnDestroy, computed, effect, inject, signal } from '@angular/core';
import { OqIconComponent } from '../../shared/icons/oq-icon.component';
import { Cenario, PassoSimulacao, condicoesUsadas } from '../roteiro-logica';
import { CONDICOES, CondicaoId, Etapa, definicao } from '../roteiros.model';
import { RoteiroEditorStore } from './roteiro-editor.store';

/** Pedido fictício da simulação — não existe no Sankhya. */
export const PEDIDO_FICTICIO = 57568;
const PAUSA_AUTOMATICA_MS = 900;

/**
 * PROTÓTIPO — simulador: mostra, como o operador veria, a sequência de um pedido fictício pelo roteiro
 * e destaca o caminho no canvas. Não chama backend nem Sankhya.
 */
@Component({
  selector: 'app-roteiro-simulador',
  standalone: true,
  imports: [OqIconComponent],
  templateUrl: './roteiro-simulador.component.html',
  styleUrl: './roteiro-simulador.component.scss',
})
export class RoteiroSimuladorComponent implements OnDestroy {
  readonly store = inject(RoteiroEditorStore);
  readonly pedido = PEDIDO_FICTICIO;
  readonly condicoes = CONDICOES;

  readonly cenario = signal<Cenario>({ PESAVEIS: true, DIVERGENCIAS: false });
  private timer: ReturnType<typeof setTimeout> | null = null;

  readonly perguntas = computed<CondicaoId[]>(() => {
    const r = this.store.roteiro();
    return r ? condicoesUsadas(r) : [];
  });

  readonly estado = this.store.simulacao;
  readonly passos = this.store.passosMostrados;
  readonly atual = this.store.passoAtual;

  readonly etapaAtual = computed<Etapa | null>(() => this.etapaDe(this.atual()));

  readonly terminou = computed(() => {
    const s = this.estado();
    return !!s && s.mostrados >= s.resultado.passos.length;
  });

  /** Etapas que o operador executa (sem Início, Fim, decisões e etapas ignoradas). */
  readonly totalExecutaveis = computed(() => this.estado()?.resultado.passos.filter((p) => this.contaComoEtapa(p)).length ?? 0);
  readonly executadas = computed(() => this.passos().filter((p) => this.contaComoEtapa(p)).length);

  /** O passo atual espera o clique do operador? */
  readonly aguardaOperador = computed(() => {
    const p = this.atual();
    const e = this.etapaAtual();
    return !!p && !!e && p.resultado === 'EXECUTADA' && e.tipo !== 'INICIO' && e.config.aoConcluir === 'AGUARDAR';
  });

  constructor() {
    // Passos automáticos (Início, decisões, etapas ignoradas ou "avançar automaticamente") seguem sozinhos.
    effect(() => {
      const s = this.estado();
      this.limparTimer();
      if (!s || this.terminou() || this.aguardaOperador()) return;
      this.timer = setTimeout(() => this.store.avancarSimulacao(), PAUSA_AUTOMATICA_MS);
    });
  }

  ngOnDestroy(): void {
    this.limparTimer();
  }

  private limparTimer(): void {
    if (this.timer) clearTimeout(this.timer);
    this.timer = null;
  }

  private contaComoEtapa(p: PassoSimulacao): boolean {
    const e = this.etapaDe(p);
    return p.resultado === 'EXECUTADA' && !!e && e.tipo !== 'INICIO';
  }

  etapaDe(p: PassoSimulacao | null): Etapa | null {
    const r = this.store.roteiro();
    return p && r ? (r.etapas.find((e) => e.id === p.etapaId) ?? null) : null;
  }

  nomeDe(p: PassoSimulacao): string {
    return this.etapaDe(p)?.nome ?? '?';
  }

  iconeDe(p: PassoSimulacao) {
    const e = this.etapaDe(p);
    return e ? definicao(e.tipo).icone : 'circle';
  }

  instrucao(e: Etapa): string {
    return e.config.descricao?.trim() || definicao(e.tipo).instrucao;
  }

  resposta(c: CondicaoId): boolean {
    return !!this.cenario()[c];
  }

  definirResposta(c: CondicaoId, valor: boolean): void {
    this.cenario.update((x) => ({ ...x, [c]: valor }));
    if (this.estado()) this.iniciar();
  }

  iniciar(): void {
    this.store.iniciarSimulacao(this.cenario());
  }

  concluirEtapa(): void {
    this.store.avancarSimulacao();
  }

  executarTudo(): void {
    this.store.concluirSimulacao();
  }

  encerrar(): void {
    this.store.encerrarSimulacao();
  }
}
