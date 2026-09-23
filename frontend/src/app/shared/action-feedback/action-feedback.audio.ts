import { Injectable } from '@angular/core';
import { SONS_USADOS } from './action-feedback.config';
import { Prioridade, SomId } from './action-feedback.types';

/**
 * Motor de áudio da biblioteca de feedback — substitui o antigo
 * SomFeedbackService (que abria um AudioContext NOVO a cada bipe).
 *
 * - UM AudioContext pra app inteira, criado no preload. Navegador deixa ele
 *   'suspended' até o 1º gesto; o 1º keydown/pointerdown (o próprio leitor de
 *   código de barras gera keydown) retoma.
 * - Arquivos baixados e decodificados UMA vez (preload); cada disparo só cria
 *   um AudioBufferSourceNode (nó descartável, barato — é o uso previsto da API).
 * - Concorrência: um som por vez. Um som só interrompe outro de prioridade
 *   menor ou igual; alerta/erro tocando não é atropelado por um "ok" em seguida.
 * - Sem áudio disponível (bloqueado, arquivo faltando) tudo segue sem som —
 *   feedback nunca pode travar a conferência.
 */
@Injectable({ providedIn: 'root' })
export class ActionFeedbackAudio {
  private ctx: AudioContext | null = null;
  private mestre: GainNode | null = null;
  private readonly buffers = new Map<SomId, AudioBuffer>();
  private preloadIniciado = false;

  private atual: AudioBufferSourceNode | null = null;
  private prioridadeAtual: Prioridade = 0;
  private fimAtual = 0;

  /** Baixa e decodifica todos os sons usados. Idempotente. */
  preload(): void {
    if (this.preloadIniciado) return;
    this.preloadIniciado = true;
    const ctx = this.contexto();
    if (!ctx) return;

    const retomar = () => {
      if (ctx.state === 'suspended') ctx.resume().catch(() => {});
    };
    window.addEventListener('keydown', retomar, { capture: true, passive: true });
    window.addEventListener('pointerdown', retomar, { capture: true, passive: true });

    for (const id of SONS_USADOS) {
      fetch(`assets/sounds/${id}.wav`)
        .then((r) => (r.ok ? r.arrayBuffer() : Promise.reject(r.status)))
        .then((dados) => ctx.decodeAudioData(dados))
        .then((buffer) => this.buffers.set(id, buffer))
        .catch(() => {
          /* som faltando — esse evento fica mudo, o resto funciona */
        });
    }
  }

  tocar(id: SomId, volume: number, prioridade: Prioridade): void {
    const ctx = this.ctx;
    const buffer = this.buffers.get(id);
    if (!ctx || !this.mestre || !buffer) return;

    const agora = ctx.currentTime;
    const tocando = this.atual !== null && agora < this.fimAtual;
    if (tocando && prioridade < this.prioridadeAtual) return;

    try {
      if (tocando) this.atual!.stop();
      if (ctx.state === 'suspended') ctx.resume().catch(() => {});

      const fonte = ctx.createBufferSource();
      fonte.buffer = buffer;
      if (volume < 1) {
        const ganho = ctx.createGain();
        ganho.gain.value = volume;
        fonte.connect(ganho).connect(this.mestre);
      } else {
        fonte.connect(this.mestre);
      }
      fonte.onended = () => {
        if (this.atual === fonte) this.atual = null;
        fonte.disconnect();
      };
      fonte.start();

      this.atual = fonte;
      this.prioridadeAtual = prioridade;
      this.fimAtual = agora + buffer.duration;
    } catch {
      /* contexto fechado/indisponível — segue sem som */
    }
  }

  private contexto(): AudioContext | null {
    if (this.ctx) return this.ctx;
    try {
      this.ctx = new AudioContext({ latencyHint: 'interactive' });
      this.mestre = this.ctx.createGain();
      this.mestre.gain.value = 1;
      this.mestre.connect(this.ctx.destination);
    } catch {
      this.ctx = null;
    }
    return this.ctx;
  }
}
