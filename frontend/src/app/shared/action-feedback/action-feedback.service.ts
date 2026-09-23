import { Injectable, inject, signal } from '@angular/core';
import { ActionFeedbackAudio } from './action-feedback.audio';
import { FEEDBACK_CONFIG, JANELA_REPETICAO_MS, MAX_TOASTS } from './action-feedback.config';
import { FeedbackEvento, FeedbackOpcoes, ToastAtivo, UltimoFeedback } from './action-feedback.types';

/**
 * true = a chamada nem chegou a ser respondida pelo backend (rede, timeout,
 * 5xx) — é falha de comunicação, não uma recusa de regra de negócio (4xx).
 */
export function ehFalhaComunicacao(err: unknown): boolean {
  const status = (err as { status?: number } | null)?.status;
  return status == null || status === 0 || status >= 500;
}

/**
 * Ponto ÚNICO de feedback de ação (som + visual + mensagem) da aplicação:
 *
 *   feedback.trigger('ITEM_CONFERIDO')
 *   feedback.trigger('ERRO_SANKHYA', { mensagem: err.error.erro })
 *   feedback.trigger('CORTE_AUTOMATICO')          // mudo por config
 *   feedback.trigger('PESAGEM_OK', { som: false })  // silêncio pontual
 *
 * O que cada evento faz está em action-feedback.config.ts. Telas só dizem
 * QUE aconteceu; nunca escolhem som, cor ou duração.
 *
 * Visual:
 * - `ultimo` (signal) → diretiva [oqFeedbackFlash] pisca o elemento marcado.
 * - `toasts` (signal) → <oq-action-feedback-host> (1x no app.component).
 */
@Injectable({ providedIn: 'root' })
export class ActionFeedbackService {
  private readonly audio = inject(ActionFeedbackAudio);

  readonly ultimo = signal<UltimoFeedback | null>(null);
  readonly toasts = signal<ToastAtivo[]>([]);

  private seq = 0;
  private proximoToastId = 1;
  private readonly timersToast = new Map<number, ReturnType<typeof setTimeout>>();
  private ultimoEvento: FeedbackEvento | null = null;
  private ultimoEventoEm = 0;

  constructor() {
    this.audio.preload();
  }

  trigger(evento: FeedbackEvento, opcoes: FeedbackOpcoes = {}): void {
    const def = FEEDBACK_CONFIG[evento];

    // Mesmo evento repetido em rajada (leitor duplicando o Enter, duplo clique)
    // vira um disparo só — senão vira uma sequência de beeps indistinguível.
    const agora = performance.now();
    const repetido = evento === this.ultimoEvento && agora - this.ultimoEventoEm < JANELA_REPETICAO_MS;
    this.ultimoEvento = evento;
    this.ultimoEventoEm = agora;
    if (repetido && !opcoes.mensagem) return;

    if (def.som && opcoes.som !== false) {
      this.audio.tocar(def.som, def.volume ?? 1, def.prioridade);
    }

    this.ultimo.set({ evento, tom: def.tom, seq: ++this.seq });

    const querToast = opcoes.toast ?? !!def.toast;
    if (querToast) {
      this.mostrarToast({
        evento,
        tom: def.tom ?? 'info',
        titulo: opcoes.mensagem ?? def.toast?.titulo ?? evento,
        detalhe: opcoes.detalhe,
        bloqueante: def.bloqueante,
        duracaoMs: def.toast?.duracaoMs ?? 4500,
      });
    }
  }

  fecharToast(id: number): void {
    clearTimeout(this.timersToast.get(id));
    this.timersToast.delete(id);
    this.toasts.update((lista) => lista.filter((t) => t.id !== id));
  }

  private mostrarToast(t: Omit<ToastAtivo, 'id'> & { duracaoMs: number }): void {
    // Mesma mensagem já na tela: não empilha, só renova o tempo.
    const existente = this.toasts().find((x) => x.evento === t.evento && x.titulo === t.titulo && x.detalhe === t.detalhe);
    const id = existente?.id ?? this.proximoToastId++;
    if (!existente) {
      const { duracaoMs: _, ...toast } = t;
      this.toasts.update((lista) => {
        const nova = [...lista, { ...toast, id }];
        const excesso = nova.length - MAX_TOASTS;
        nova.slice(0, Math.max(0, excesso)).forEach((v) => {
          clearTimeout(this.timersToast.get(v.id));
          this.timersToast.delete(v.id);
        });
        return excesso > 0 ? nova.slice(excesso) : nova;
      });
    }
    clearTimeout(this.timersToast.get(id));
    // Bloqueante (ou duração 0) fica até o operador fechar.
    if (!t.bloqueante && t.duracaoMs > 0) {
      this.timersToast.set(id, setTimeout(() => this.fecharToast(id), t.duracaoMs));
    }
  }
}
