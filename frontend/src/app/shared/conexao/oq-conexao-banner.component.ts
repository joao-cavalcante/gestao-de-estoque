import { Component, OnDestroy, inject, signal } from '@angular/core';
import { OqIconComponent } from '../icons/oq-icon.component';
import { ConexaoService } from './conexao.service';

/** Faixa fixa no topo, em qualquer tela (inclusive a conferência): sem conexão = vermelha; voltou = verde por 4 s. */
@Component({
  selector: 'oq-conexao-banner',
  standalone: true,
  imports: [OqIconComponent],
  template: `
    @if (conexao.estado() === 'offline') {
      <div class="cx cx--off" role="alert" aria-live="assertive">
        <oq-icon name="circle-alert" [size]="16" />
        <span class="cx__txt">
          <strong>SEM CONEXÃO COM O SERVIDOR</strong>
          <span class="cx__sub">Os bipes não estão sendo gravados — aguarde a conexão voltar antes de continuar.{{ tempo() }}</span>
        </span>
        <button type="button" class="cx__btn" (click)="conexao.testarAgora()">Tentar agora</button>
      </div>
    } @else if (conexao.acabouDeVoltar()) {
      <div class="cx cx--ok" role="status" aria-live="polite">
        <oq-icon name="check" [size]="16" />
        <span class="cx__txt"><strong>CONEXÃO RESTABELECIDA</strong></span>
      </div>
    }
  `,
  styles: [
    `
      .cx {
        position: fixed;
        top: 0;
        left: 0;
        right: 0;
        z-index: 1000;
        display: flex;
        align-items: center;
        gap: 10px;
        padding: 8px 16px;
        color: #fff;
        font-family: var(--oq-font-body);
        font-size: 12px;
        box-shadow: 0 2px 8px rgba(0, 0, 0, 0.2);
      }
      .cx--off {
        background: var(--oq-critical);
      }
      .cx--ok {
        background: var(--oq-success);
      }
      .cx__txt {
        flex: 1;
        min-width: 0;
        display: flex;
        flex-wrap: wrap;
        align-items: baseline;
        gap: 2px 10px;
      }
      .cx__txt strong {
        font-family: var(--oq-font-display);
        letter-spacing: 0.06em;
      }
      .cx__btn {
        flex: none;
        height: 30px;
        padding: 0 12px;
        border: 1px solid rgba(255, 255, 255, 0.8);
        border-radius: var(--oq-radius-input);
        background: transparent;
        color: #fff;
        font-family: var(--oq-font-display);
        font-size: 11px;
        font-weight: 700;
        text-transform: uppercase;
        letter-spacing: 0.06em;
        cursor: pointer;
      }
      .cx__btn:hover {
        background: rgba(255, 255, 255, 0.15);
      }
    `,
  ],
})
export class OqConexaoBannerComponent implements OnDestroy {
  readonly conexao = inject(ConexaoService);
  private readonly agora = signal(Date.now());
  private readonly relogio = setInterval(() => this.agora.set(Date.now()), 1000);

  ngOnDestroy(): void {
    clearInterval(this.relogio);
  }

  tempo(): string {
    const desde = this.conexao.offlineDesde();
    if (!desde) return '';
    const s = Math.max(0, Math.round((this.agora() - desde) / 1000));
    return s < 60 ? ` (há ${s}s)` : ` (há ${Math.floor(s / 60)} min)`;
  }
}
