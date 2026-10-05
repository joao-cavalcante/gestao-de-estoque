import { Component, EventEmitter, Input, OnInit, Output, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { OqSpinnerComponent } from '../shared/icons/oq-spinner.component';
import { ActionFeedbackService } from '../shared/action-feedback/action-feedback.service';
import { AuthService } from '../auth/auth.service';
import { SeparacaoService } from './separacao.service';
import { TopFaturamento } from './separacao.model';

/**
 * Modal "Faturar pedido" (CCO FATAOCONCLUIR='S') — escolha da TOP de destino e
 * SelecaoDocumentoSP.faturar. Usado no fim da conferência e na tela de Liberação
 * de Corte (quando a liberação fecha o corte). O backend recusa nota já faturada,
 * corte pendente e conferência não finalizada no Sankhya; aqui só mostra o motivo.
 */
@Component({
  selector: 'oq-faturamento-modal',
  standalone: true,
  imports: [FormsModule, OqSpinnerComponent],
  template: `
    <div class="oq-modal-backdrop">
      <div class="oq-modal" (click)="$event.stopPropagation()">
        <div class="oq-modal__header">
          <span class="oq-modal__titulo">Faturar pedido{{ rotulo ? ' — ' + rotulo : '' }}</span>
        </div>
        <div class="oq-modal__body">
          @if (carregando()) {
            <span class="oq-form-hint"><oq-spinner [size]="12" /> Consultando a nota no Sankhya…</span>
          } @else if (sucesso()) {
            <span class="oq-form-hint">
              Nota faturada com sucesso.
              @if (notasGeradas().length) { Nota gerada: {{ notasGeradas().join(', ') }}. }
            </span>
          } @else if (bloqueio()) {
            <span class="oq-form-hint oq-form-hint--erro">Não é possível faturar: {{ bloqueio() }}</span>
          } @else {
            <span class="oq-form-hint">Selecione a TOP (tipo de operação) de destino do faturamento.</span>
            <select class="oq-input" [(ngModel)]="codTipOper">
              <option [ngValue]="null" disabled>Selecione uma TOP…</option>
              @for (t of tops(); track t.codTipOper) {
                <option [ngValue]="t.codTipOper">{{ t.codTipOper }} — {{ t.descricao }}</option>
              }
            </select>
            @if (erro()) { <span class="oq-form-hint oq-form-hint--erro">{{ erro() }}</span> }
          }
        </div>
        <div class="oq-modal__footer">
          <button type="button" class="oq-admin-btn oq-admin-btn--outline" [disabled]="faturando()" (click)="fechado.emit()">
            {{ podeFaturar ? 'Pular' : 'Fechar' }}
          </button>
          @if (podeFaturar) {
            <button
              type="button"
              class="oq-admin-btn oq-admin-btn--primary"
              [disabled]="codTipOper === null || faturando()"
              (click)="confirmar()"
            >
              @if (faturando()) {
                <oq-spinner [size]="12" />
                Faturando…
              } @else {
                Faturar
              }
            </button>
          }
        </div>
      </div>
    </div>
  `,
})
export class OqFaturamentoModalComponent implements OnInit {
  private readonly separacaoService = inject(SeparacaoService);
  private readonly authService = inject(AuthService);
  private readonly feedback = inject(ActionFeedbackService);

  @Input({ required: true }) sessaoId!: string;
  /** Texto livre pro cabeçalho (ex.: "Pedido 12345"). */
  @Input() rotulo = '';
  @Output() fechado = new EventEmitter<void>();

  readonly carregando = signal(true);
  readonly tops = signal<TopFaturamento[]>([]);
  codTipOper: number | null = null;
  readonly faturando = signal(false);
  readonly erro = signal<string | null>(null);
  readonly sucesso = signal(false);
  /** NUNOTA das notas geradas pelo faturamento. */
  readonly notasGeradas = signal<number[]>([]);
  /** Faturamento bloqueado (nota já faturada, corte pendente, recontagem…) — só mostra o motivo. */
  readonly bloqueio = signal<string | null>(null);

  private get tenant(): string {
    return this.authService.obterTenantSlug() ?? '';
  }

  get podeFaturar(): boolean {
    return !this.carregando() && !this.sucesso() && !this.bloqueio();
  }

  ngOnInit(): void {
    this.separacaoService.topsFaturamento(this.tenant, this.sessaoId).subscribe({
      next: (tops) => {
        this.tops.set(tops);
        this.codTipOper = tops.length === 1 ? tops[0].codTipOper : null;
        if (!tops.length) this.bloqueio.set('nenhuma TOP ativa disponível para o faturamento desta nota.');
        this.carregando.set(false);
      },
      error: (err) => {
        this.bloqueio.set(err?.error?.erro ?? 'Não foi possível consultar as TOPs de faturamento no Sankhya.');
        this.carregando.set(false);
      },
    });
  }

  confirmar(): void {
    if (this.codTipOper == null || this.faturando()) return;
    this.faturando.set(true);
    this.erro.set(null);
    this.separacaoService.faturar(this.tenant, this.sessaoId, this.codTipOper).subscribe({
      next: (res) => {
        this.faturando.set(false);
        this.notasGeradas.set(res?.notasGeradas ?? []);
        this.sucesso.set(true);
        this.feedback.trigger('SUCESSO_SANKHYA');
      },
      error: (err) => {
        this.faturando.set(false);
        this.erro.set(err?.error?.erro ?? 'Falha ao faturar a nota.');
        this.feedback.trigger('ERRO_SANKHYA', { toast: false });
      },
    });
  }
}
