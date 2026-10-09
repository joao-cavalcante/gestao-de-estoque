import { Component, OnDestroy, OnInit, signal } from '@angular/core';
import { OqIconComponent } from '../icons/oq-icon.component';

/** Nome do bundle principal (com hash) de uma index.html — muda a cada deploy. */
function bundleDe(html: string): string | null {
  return /<script[^>]+src="(main[^"]*\.js)"/.exec(html)?.[1] ?? null;
}

/**
 * Atualização OBRIGATÓRIA (usuário, 09/10/2026): tablet com a tela aberta desde antes do deploy seguia rodando a
 * versão antiga (abria o modal antigo de faturamento pedindo a TOP). A cada 2 min — e quando a tela volta a ficar
 * visível — compara o bundle da index.html do servidor com o carregado; mudou = bloqueia a tela até atualizar.
 * "Atualizar agora" limpa o Cache Storage do navegador e recarrega (a index.html vem sem cache — nginx). O login
 * (localStorage) é mantido.
 */
@Component({
  selector: 'oq-nova-versao',
  standalone: true,
  imports: [OqIconComponent],
  template: `
    @if (novaVersao()) {
      <div class="nv" role="alertdialog" aria-modal="true" aria-labelledby="nv-titulo">
        <div class="nv__caixa">
          <span class="nv__icone"><oq-icon name="sync" [size]="26" /></span>
          <strong id="nv-titulo" class="nv__titulo">Nova versão do sistema</strong>
          <span class="nv__texto">
            O WMS foi atualizado. Para continuar, atualize a tela — o que você já conferiu está salvo e o login continua.
          </span>
          <button type="button" class="nv__btn" [disabled]="atualizando()" (click)="atualizar()">
            {{ atualizando() ? 'Atualizando…' : 'Atualizar agora' }}
          </button>
        </div>
      </div>
    }
  `,
  styles: [
    `
      .nv {
        position: fixed;
        inset: 0;
        z-index: 2000;
        display: grid;
        place-items: center;
        padding: 16px;
        background: rgba(24, 24, 27, 0.72);
      }
      .nv__caixa {
        display: flex;
        flex-direction: column;
        align-items: center;
        gap: 12px;
        width: 100%;
        max-width: 420px;
        padding: 28px 24px;
        text-align: center;
        background: var(--oq-surface);
        border: 1px solid var(--oq-border);
        border-top: 4px solid var(--oq-brand);
        border-radius: var(--oq-radius-block);
        box-shadow: 0 16px 40px rgba(0, 0, 0, 0.3);
      }
      .nv__icone {
        display: grid;
        place-items: center;
        width: 52px;
        height: 52px;
        border-radius: 50%;
        background: color-mix(in srgb, var(--oq-brand) 12%, transparent);
        color: var(--oq-brand);
      }
      .nv__titulo {
        font-family: var(--oq-font-display);
        font-size: 18px;
        color: var(--oq-text-primary);
      }
      .nv__texto {
        font-family: var(--oq-font-body);
        font-size: 14px;
        line-height: 1.45;
        color: var(--oq-text-secondary);
      }
      .nv__btn {
        width: 100%;
        min-height: 48px;
        margin-top: 4px;
        border: 0;
        border-radius: var(--oq-radius-input);
        background: var(--oq-brand);
        color: var(--oq-brand-ink);
        font-family: var(--oq-font-display);
        font-size: 14px;
        font-weight: 700;
        text-transform: uppercase;
        letter-spacing: 0.06em;
        cursor: pointer;
      }
      .nv__btn:disabled {
        opacity: 0.7;
        cursor: default;
      }
    `,
  ],
})
export class OqNovaVersaoComponent implements OnInit, OnDestroy {
  readonly novaVersao = signal(false);
  readonly atualizando = signal(false);

  /** Bundle que ESTA aba carregou (null em dev / sem hash: aí nunca acusa versão nova). */
  private readonly atual = this.bundleCarregado();
  private timer: ReturnType<typeof setInterval> | null = null;
  private readonly aoVoltar = () => {
    if (document.visibilityState === 'visible') this.verificar();
  };

  ngOnInit(): void {
    if (!this.atual) return;
    this.timer = setInterval(() => this.verificar(), 120_000);
    document.addEventListener('visibilitychange', this.aoVoltar);
  }

  ngOnDestroy(): void {
    if (this.timer) clearInterval(this.timer);
    document.removeEventListener('visibilitychange', this.aoVoltar);
  }

  private bundleCarregado(): string | null {
    const src = Array.from(document.scripts)
      .map((s) => s.getAttribute('src') ?? '')
      .find((s) => /(^|\/)main[^/]*\.js$/.test(s));
    const nome = src?.split('/').pop() ?? null;
    // Só bundle com hash (build de produção) — "main.js" do dev não muda de nome.
    return nome && /main-[A-Z0-9]+\.js/i.test(nome) ? nome : null;
  }

  private async verificar(): Promise<void> {
    if (this.novaVersao()) return;
    try {
      const resp = await fetch(`/index.html?v=${Date.now()}`, { cache: 'no-store' });
      if (!resp.ok) return;
      const servidor = bundleDe(await resp.text());
      if (servidor && servidor !== this.atual) this.novaVersao.set(true);
    } catch {
      /* sem rede: o banner de conexão já avisa; tenta no próximo ciclo */
    }
  }

  async atualizar(): Promise<void> {
    this.atualizando.set(true);
    try {
      if ('caches' in window) {
        const nomes = await caches.keys();
        await Promise.all(nomes.map((n) => caches.delete(n)));
      }
    } catch {
      /* Cache Storage indisponível: o reload com index.html sem cache já resolve */
    }
    window.location.reload();
  }
}
