import { Injectable } from '@angular/core';

export type TipoSom = 'ok' | 'erro' | 'atencao' | 'invalido' | 'finalizado';

interface Beep {
  freq: number;
  /** offset do início, em segundos, relativo ao now */
  start: number;
  dur: number;
  vol?: number;
}

/**
 * Feedback sonoro da bipagem — MESMO mapa de som do projeto base
 * (fila-conferencia-frontend/.../separacao.component.ts `playSound`):
 * osciladores `square` + filtro lowpass a 2200 Hz, mesmas frequências,
 * durações e volumes. Um AudioContext por disparo, fechado depois.
 * Sem áudio disponível a bipagem continua funcionando (try/catch).
 */
@Injectable({ providedIn: 'root' })
export class SomFeedbackService {
  private static readonly MAPA: Record<TipoSom, { beeps: Beep[]; fecharMs: number }> = {
    ok: {
      beeps: [
        { freq: 900, start: 0, dur: 0.13 },
        { freq: 1200, start: 0.16, dur: 0.13 },
      ],
      fecharMs: 700,
    },
    erro: {
      beeps: [
        { freq: 300, start: 0, dur: 0.22, vol: 0.8 },
        { freq: 260, start: 0.27, dur: 0.14, vol: 0.7 },
      ],
      fecharMs: 800,
    },
    atencao: {
      beeps: [{ freq: 700, start: 0, dur: 0.18, vol: 0.7 }],
      fecharMs: 500,
    },
    invalido: {
      beeps: [{ freq: 380, start: 0, dur: 0.1, vol: 0.65 }],
      fecharMs: 400,
    },
    finalizado: {
      beeps: [
        { freq: 800, start: 0, dur: 0.12 },
        { freq: 1000, start: 0.15, dur: 0.12 },
        { freq: 1300, start: 0.3, dur: 0.18 },
      ],
      fecharMs: 900,
    },
  };

  tocar(tipo: TipoSom): void {
    try {
      const ctx = new AudioContext();
      const now = ctx.currentTime;
      const { beeps, fecharMs } = SomFeedbackService.MAPA[tipo];
      for (const b of beeps) {
        this.beep(ctx, b.freq, now + b.start, b.dur, b.vol ?? 0.75);
      }
      setTimeout(() => ctx.close(), fecharMs);
    } catch {
      // AudioContext bloqueado / indisponível — bipagem segue sem som.
    }
  }

  private beep(ctx: AudioContext, freq: number, start: number, dur: number, vol: number): void {
    const osc = ctx.createOscillator();
    const gain = ctx.createGain();
    const filter = ctx.createBiquadFilter();
    filter.type = 'lowpass';
    filter.frequency.value = 2200;

    osc.connect(filter);
    filter.connect(gain);
    gain.connect(ctx.destination);

    osc.type = 'square';
    osc.frequency.value = freq;

    gain.gain.setValueAtTime(0, start);
    gain.gain.linearRampToValueAtTime(vol, start + 0.008);
    gain.gain.setValueAtTime(vol, start + dur - 0.015);
    gain.gain.linearRampToValueAtTime(0, start + dur);

    osc.start(start);
    osc.stop(start + dur + 0.02);
  }
}
