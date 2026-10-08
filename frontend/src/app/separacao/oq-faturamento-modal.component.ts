import { Component, EventEmitter, Input, OnInit, Output, inject, signal } from '@angular/core';
import { OqSpinnerComponent } from '../shared/icons/oq-spinner.component';
import { OqIconComponent } from '../shared/icons/oq-icon.component';
import { ActionFeedbackService } from '../shared/action-feedback/action-feedback.service';
import { AuthService } from '../auth/auth.service';
import { SeparacaoService } from './separacao.service';
import { TopFaturamento } from './separacao.model';

/**
 * Modal "Faturar pedido" (CCO FATAOCONCLUIR='S') — escolha da TOP de destino e
 * SelecaoDocumentoSP.faturar. Usado no fim da conferência e na tela de Liberação
 * de Corte (quando a liberação fecha o corte). O backend recusa nota já faturada,
 * corte pendente e conferência não finalizada no Sankhya; aqui só mostra o motivo.
 * TOPs em cartões de um toque (tablet no chão), não select.
 */
@Component({
  selector: 'oq-faturamento-modal',
  standalone: true,
  imports: [OqSpinnerComponent, OqIconComponent],
  template: `
    <div class="oq-modal-backdrop">
      <div class="oq-modal fat" (click)="$event.stopPropagation()">
        <div class="oq-modal__header">
          <span class="fat__cab">
            <oq-icon name="receipt" [size]="16" />
            <span class="oq-modal__titulo">Faturar pedido</span>
            @if (rotulo) { <span class="fat__rotulo">{{ rotulo }}</span> }
          </span>
          <button type="button" class="oq-modal__fechar" aria-label="Fechar" [disabled]="faturando()" (click)="fechado.emit()">
            <oq-icon name="x" [size]="14" />
          </button>
        </div>

        <div class="oq-modal__body">
          @if (carregando()) {
            <div class="fat__carregando"><oq-spinner [size]="18" /> Consultando a nota no Sankhya…</div>
          } @else if (sucesso()) {
            <div class="fat__ok">
              <span class="fat__ok-icone"><oq-icon name="check" [size]="22" /></span>
              <span class="fat__ok-titulo">Pedido faturado</span>
              @if (notasGeradas().length) {
                <span class="fat__ok-rotulo">{{ notasGeradas().length > 1 ? 'Notas geradas' : 'Nota gerada' }}</span>
                <span class="fat__ok-nota">{{ notasGeradas().join(' · ') }}</span>
              }
            </div>
            @if (aviso()) {
              <div class="fat__caixa fat__caixa--aviso">
                <oq-icon name="triangle" [size]="16" />
                <span>{{ aviso() }}</span>
              </div>
            }
          } @else if (notasSemConfirmar().length) {
            <div class="fat__caixa fat__caixa--aviso">
              <oq-icon name="triangle" [size]="16" />
              <span>
                <strong>Nota {{ notasSemConfirmar().join(', ') }} gerada, mas ainda não confirmada no Sankhya.</strong>
                Confirme para liberar o carregamento.
              </span>
            </div>
            @if (erro()) {
              <div class="fat__caixa fat__caixa--erro">
                <oq-icon name="circle-alert" [size]="16" />
                <span>{{ erro() }}</span>
              </div>
            }
          } @else if (bloqueio()) {
            <div class="fat__caixa fat__caixa--erro">
              <oq-icon name="circle-alert" [size]="16" />
              <span><strong>Não é possível faturar.</strong> {{ bloqueio() }}</span>
            </div>
          } @else {
            <span class="fat__pergunta">TOP de destino</span>
            <div class="fat__tops" role="radiogroup" aria-label="TOP de destino">
              @for (t of tops(); track t.codTipOper) {
                <button
                  type="button"
                  role="radio"
                  class="fat__top"
                  [class.fat__top--sel]="codTipOper === t.codTipOper"
                  [attr.aria-checked]="codTipOper === t.codTipOper"
                  [disabled]="faturando()"
                  (click)="codTipOper = t.codTipOper"
                >
                  <span class="fat__top-cod">{{ t.codTipOper }}</span>
                  <span class="fat__top-desc">{{ t.descricao }}</span>
                  @if (t.serie) { <span class="fat__top-serie">Série {{ t.serie }}</span> }
                </button>
              }
            </div>
            @if (erro()) {
              <div class="fat__caixa fat__caixa--erro">
                <oq-icon name="circle-alert" [size]="16" />
                <span>{{ erro() }}</span>
              </div>
            }
          }
          @if (pendente()) {
            <span class="fat__rodape-hint">Sem nota confirmada o pedido fica na lista <strong>Aguardando Nota</strong> e não pode ser carregado.</span>
          }
        </div>

        <div class="oq-modal__footer">
          <button type="button" class="oq-admin-btn oq-admin-btn--outline fat__btn" [disabled]="faturando()" (click)="fechado.emit()">
            {{ pendente() ? 'Faturar depois' : 'Fechar' }}
          </button>
          @if (notasSemConfirmar().length && !sucesso()) {
            <button type="button" class="oq-admin-btn oq-admin-btn--primary fat__btn" [disabled]="faturando()" (click)="confirmarNota()">
              @if (faturando()) {
                <oq-spinner [size]="12" />
                Confirmando…
              } @else {
                <oq-icon name="check" [size]="14" />
                Confirmar nota
              }
            </button>
          }
          @if (podeFaturar) {
            <button
              type="button"
              class="oq-admin-btn oq-admin-btn--primary fat__btn"
              [disabled]="codTipOper === null || faturando()"
              (click)="confirmar()"
            >
              @if (faturando()) {
                <oq-spinner [size]="12" />
                Faturando…
              } @else {
                <oq-icon name="receipt" [size]="14" />
                Faturar{{ codTipOper !== null ? ' na TOP ' + codTipOper : '' }}
              }
            </button>
          }
        </div>
      </div>
    </div>
  `,
  styles: [`
    .fat { max-width: 480px; }
    .fat__cab { display: flex; align-items: center; gap: 8px; min-width: 0; color: var(--oq-brand-accent); }
    .fat__rotulo {
      font-family: var(--oq-font-mono); font-size: 12px; color: var(--oq-text-secondary);
      white-space: nowrap; overflow: hidden; text-overflow: ellipsis;
    }
    .oq-modal__fechar:disabled { opacity: 0.5; cursor: not-allowed; }

    .fat__carregando {
      display: flex; align-items: center; justify-content: center; gap: 10px;
      padding: 28px 0; font-family: var(--oq-font-body); font-size: 13px; color: var(--oq-text-secondary);
    }

    .fat__pergunta {
      font-family: var(--oq-font-display); font-size: 11px; font-weight: 700;
      text-transform: uppercase; letter-spacing: 0.06em; color: var(--oq-text-secondary);
    }
    .fat__tops { display: flex; flex-direction: column; gap: 8px; }
    .fat__top {
      display: grid; grid-template-columns: auto 1fr auto; align-items: center; gap: 12px;
      width: 100%; min-height: 52px; padding: 10px 14px; text-align: left; cursor: pointer;
      background: var(--oq-surface); color: var(--oq-text-primary);
      border: 1px solid var(--oq-border); border-left: 4px solid var(--oq-border);
      border-radius: var(--oq-radius-block);
      transition: border-color 0.12s, background 0.12s;
    }
    .fat__top:hover:not(:disabled) { border-color: var(--oq-brand-accent); }
    .fat__top:disabled { cursor: not-allowed; opacity: 0.6; }
    .fat__top--sel {
      background: var(--oq-surface-2);
      border-color: var(--oq-brand);
      border-left-color: var(--oq-brand);
    }
    .fat__top-cod {
      min-width: 44px; font-family: var(--oq-font-mono); font-size: 16px; font-weight: 700;
      color: var(--oq-brand-accent);
    }
    .fat__top--sel .fat__top-cod { color: var(--oq-brand); }
    .fat__top-desc { font-family: var(--oq-font-body); font-size: 13px; font-weight: 600; line-height: 1.3; }
    .fat__top-serie {
      font-family: var(--oq-font-mono); font-size: 10px; text-transform: uppercase; letter-spacing: 0.05em;
      color: var(--oq-text-secondary); white-space: nowrap;
    }

    .fat__caixa {
      display: flex; align-items: flex-start; gap: 10px; padding: 10px 12px;
      font-family: var(--oq-font-body); font-size: 13px; line-height: 1.45;
      border: 1px solid; border-radius: var(--oq-radius-block);
    }
    .fat__caixa oq-icon { flex: none; margin-top: 1px; }
    .fat__caixa--erro { color: var(--oq-critical-foreground); background: var(--oq-critical-soft); border-color: var(--oq-critical); }
    .fat__caixa--aviso { color: var(--oq-warning-foreground); background: var(--oq-warning-soft); border-color: var(--oq-attention); }

    .fat__ok { display: flex; flex-direction: column; align-items: center; gap: 4px; padding: 12px 0 4px; text-align: center; }
    .fat__ok-icone {
      display: grid; place-items: center; width: 44px; height: 44px; margin-bottom: 6px; border-radius: 50%;
      color: var(--oq-success-foreground); background: var(--oq-success-soft); border: 1px solid var(--oq-success);
    }
    .fat__ok-titulo { font-family: var(--oq-font-display); font-size: 16px; font-weight: 700; color: var(--oq-text-primary); }
    .fat__ok-rotulo {
      margin-top: 8px; font-family: var(--oq-font-display); font-size: 10px; font-weight: 700;
      text-transform: uppercase; letter-spacing: 0.06em; color: var(--oq-text-secondary);
    }
    .fat__ok-nota { font-family: var(--oq-font-mono); font-size: 24px; font-weight: 700; color: var(--oq-brand-accent); }

    .fat__btn { min-height: 40px; padding: 9px 16px; font-size: 12px; }
    .fat__rodape-hint { font-family: var(--oq-font-body); font-size: 12px; line-height: 1.4; color: var(--oq-text-secondary); }
  `],
})
export class OqFaturamentoModalComponent implements OnInit {
  private readonly separacaoService = inject(SeparacaoService);
  private readonly authService = inject(AuthService);
  private readonly feedback = inject(ActionFeedbackService);

  @Input({ required: true }) sessaoId!: string;
  /** Texto livre pro cabeçalho (ex.: "Pedido 12345"). */
  @Input() rotulo = '';
  @Output() fechado = new EventEmitter<void>();
  /** Nota faturada E confirmada — quem abriu o modal libera o carregamento. */
  @Output() notaConfirmada = new EventEmitter<number[]>();

  readonly carregando = signal(true);
  readonly tops = signal<TopFaturamento[]>([]);
  codTipOper: number | null = null;
  readonly faturando = signal(false);
  readonly erro = signal<string | null>(null);
  readonly sucesso = signal(false);
  /** NUNOTA das notas geradas pelo faturamento. */
  readonly notasGeradas = signal<number[]>([]);
  /** Nota gerada mas não confirmada no Sankhya (CACSP.confirmarNota recusou) — motivo vindo do backend. */
  readonly aviso = signal<string | null>(null);
  /** Faturamento bloqueado (nota já faturada, corte pendente, recontagem…) — só mostra o motivo. */
  readonly bloqueio = signal<string | null>(null);
  /** Nota já gerada sem confirmar (409 confirmarNota) — a ação vira "Confirmar nota". */
  readonly notasSemConfirmar = signal<number[]>([]);

  private get tenant(): string {
    return this.authService.obterTenantSlug() ?? '';
  }

  get podeFaturar(): boolean {
    return !this.carregando() && !this.sucesso() && !this.bloqueio() && !this.notasSemConfirmar().length;
  }

  /** Ainda dá pra faturar/confirmar aqui — fechar agora deixa o pedido em Aguardando Nota. */
  pendente(): boolean {
    return this.podeFaturar || (!this.sucesso() && this.notasSemConfirmar().length > 0) || (this.sucesso() && !!this.aviso());
  }

  /** 409 { confirmarNota: true, notas } = nota gerada sem confirmar. */
  private tratarNotaSemConfirmar(err: any): boolean {
    if (err?.status === 409 && err?.error?.confirmarNota) {
      this.notasSemConfirmar.set(err.error.notas ?? []);
      return true;
    }
    return false;
  }

  private aplicarResultado(res: { notasGeradas: number[]; aviso?: string | null } | null): void {
    this.notasGeradas.set(res?.notasGeradas ?? []);
    this.aviso.set(res?.aviso ?? null);
    this.sucesso.set(true);
    if (!res?.aviso && (res?.notasGeradas?.length ?? 0) > 0) this.notaConfirmada.emit(res!.notasGeradas);
  }

  ngOnInit(): void {
    this.separacaoService.topsFaturamento(this.tenant, this.sessaoId).subscribe({
      next: (tops) => {
        this.tops.set(tops);
        // Uma só = ela; senão a sugerida pela regra NF-e/NFC-e do parceiro (o operador pode trocar).
        this.codTipOper = tops.length === 1 ? tops[0].codTipOper : (tops.find((t) => t.sugerida)?.codTipOper ?? null);
        if (!tops.length) this.bloqueio.set('nenhuma TOP de destino cadastrada nas restrições da TOP deste pedido no Sankhya (TGFREP, destino) — cadastre a restrição para poder faturar.');
        this.carregando.set(false);
      },
      error: (err) => {
        if (!this.tratarNotaSemConfirmar(err)) {
          this.bloqueio.set(err?.error?.erro ?? 'Não foi possível consultar as TOPs de faturamento no Sankhya.');
        }
        this.carregando.set(false);
      },
    });
  }

  confirmar(): void {
    if (this.codTipOper == null || this.faturando()) return;
    this.faturando.set(true);
    this.erro.set(null);
    // Série da restrição de destino (TGFREP), quando cadastrada.
    const serie = this.tops().find((t) => t.codTipOper === this.codTipOper)?.serie ?? undefined;
    this.separacaoService.faturar(this.tenant, this.sessaoId, this.codTipOper, serie).subscribe({
      next: (res) => {
        this.faturando.set(false);
        this.aplicarResultado(res);
        if (res?.aviso) this.feedback.trigger('ERRO_SANKHYA', { toast: false });
        else this.feedback.trigger('SUCESSO_SANKHYA');
      },
      error: (err) => {
        this.faturando.set(false);
        if (!this.tratarNotaSemConfirmar(err)) this.erro.set(err?.error?.erro ?? 'Falha ao faturar a nota.');
        this.feedback.trigger('ERRO_SANKHYA', { toast: false });
      },
    });
  }

  confirmarNota(): void {
    if (this.faturando()) return;
    this.faturando.set(true);
    this.erro.set(null);
    this.separacaoService.confirmarNota(this.tenant, this.sessaoId).subscribe({
      next: (res) => {
        this.faturando.set(false);
        if (res?.aviso) {
          this.erro.set(res.aviso);
          this.feedback.trigger('ERRO_SANKHYA', { toast: false });
          return;
        }
        this.notasSemConfirmar.set([]);
        this.aplicarResultado(res);
        this.feedback.trigger('SUCESSO_SANKHYA');
      },
      error: (err) => {
        this.faturando.set(false);
        this.erro.set(err?.error?.erro ?? 'Falha ao confirmar a nota.');
        this.feedback.trigger('ERRO_SANKHYA', { toast: false });
      },
    });
  }
}
