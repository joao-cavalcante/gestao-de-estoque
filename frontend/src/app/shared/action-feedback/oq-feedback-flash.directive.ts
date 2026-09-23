import { Directive, ElementRef, OnDestroy, effect, inject } from '@angular/core';
import { ActionFeedbackService } from './action-feedback.service';

const DURACAO_FLASH_MS = 420;

/**
 * Pisca o elemento marcado com a cor do último feedback disparado (tom do
 * evento na config): leitura = azul rápido, conferido = verde, divergência =
 * âmbar, erro = vermelho. Estilo em styles.scss (.oq-fb-flash--*), porque a
 * diretiva não tem folha de estilo própria.
 *
 *   <oq-scan-bar oqFeedbackFlash ... />
 */
@Directive({
  selector: '[oqFeedbackFlash]',
  standalone: true,
})
export class OqFeedbackFlashDirective implements OnDestroy {
  private readonly el = inject(ElementRef<HTMLElement>);
  private readonly feedback = inject(ActionFeedbackService);
  private timer?: ReturnType<typeof setTimeout>;
  private classeAtual: string | null = null;
  private seqVisto = this.feedback.ultimo()?.seq ?? 0;

  constructor() {
    effect(() => {
      const u = this.feedback.ultimo();
      if (!u || u.seq === this.seqVisto) return;
      this.seqVisto = u.seq;
      if (u.tom) this.piscar(`oq-fb-flash--${u.tom}`);
    });
  }

  private piscar(classe: string): void {
    const host = this.el.nativeElement;
    clearTimeout(this.timer);
    if (this.classeAtual) host.classList.remove('oq-fb-flash', this.classeAtual);
    // Força reflow pra reiniciar a animação quando o mesmo tom repete em sequência.
    void host.offsetWidth;
    host.classList.add('oq-fb-flash', classe);
    this.classeAtual = classe;
    this.timer = setTimeout(() => {
      host.classList.remove('oq-fb-flash', classe);
      this.classeAtual = null;
    }, DURACAO_FLASH_MS);
  }

  ngOnDestroy(): void {
    clearTimeout(this.timer);
  }
}
