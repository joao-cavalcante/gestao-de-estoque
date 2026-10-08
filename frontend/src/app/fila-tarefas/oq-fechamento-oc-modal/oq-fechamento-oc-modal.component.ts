import { Component, EventEmitter, Input, OnInit, Output, inject, signal } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { OqIconComponent } from '../../shared/icons/oq-icon.component';
import { OqSpinnerComponent } from '../../shared/icons/oq-spinner.component';
import { ActionFeedbackService } from '../../shared/action-feedback/action-feedback.service';

interface FechamentoPedido {
  nunota: number;
  numeroNota: number | null;
  cliente: string | null;
  /** true NFC-e (parceiro CODTIPPARC 10401002) · false NF-e · null não consultado. */
  nfce: boolean | null;
  codTipOper: number | null;
  descricaoTop: string | null;
  situacao: 'faturar' | 'pronto' | 'bloqueado';
  motivo: string | null;
  notasGeradas: number[];
  ok: boolean | null;
  erro: string | null;
}

interface FechamentoOc {
  oc: number;
  pedidos: FechamentoPedido[];
  podeFechar: boolean;
  ocFechada: boolean;
  mensagem: string | null;
}

/**
 * "Fechar OC" (usuário, 08/10/2026): a nota sai aqui, não pedido a pedido. Mostra a prévia (cada pedido com
 * NF-e/NFC-e e a TOP de destino escolhida pela regra do parceiro) e, ao confirmar, o backend fatura + confirma
 * cada pedido e fecha a OC no Sankhya só se todos saíram com nota. Falha num pedido não para os outros.
 */
@Component({
  selector: 'oq-fechamento-oc-modal',
  standalone: true,
  imports: [OqIconComponent, OqSpinnerComponent],
  template: `
    <div class="oq-modal-backdrop">
      <div class="oq-modal oq-modal--wide fec" (click)="$event.stopPropagation()">
        <div class="oq-modal__header">
          <span class="fec__cab">
            <oq-icon name="entrega" [size]="16" />
            <span class="oq-modal__titulo">Fechar OC {{ oc }}</span>
          </span>
          <button type="button" class="oq-modal__fechar" aria-label="Fechar" [disabled]="executando()" (click)="fechado.emit()">
            <oq-icon name="x" [size]="14" />
          </button>
        </div>

        <div class="oq-modal__body">
          @if (carregando()) {
            <div class="fec__carregando"><oq-spinner [size]="18" /> Montando o fechamento (pedidos, NF-e / NFC-e e TOP)…</div>
          } @else if (erro()) {
            <div class="fec__caixa fec__caixa--erro"><oq-icon name="circle-alert" [size]="16" /><span>{{ erro() }}</span></div>
          } @else {
            @if (dados(); as d) {
            <ol class="fec__passos" aria-label="O que o fechamento faz">
              <li [class.fec__passo--feito]="executado()">1. Faturar os pedidos</li>
              <li [class.fec__passo--feito]="executado()">2. Confirmar as notas</li>
              <li [class.fec__passo--feito]="d.ocFechada">3. Fechar a OC no Sankhya</li>
            </ol>

            <div class="fec__tabela-wrap">
              <table class="fec__tabela">
                <thead>
                  <tr>
                    <th>Pedido</th>
                    <th>Cliente</th>
                    <th>Nota</th>
                    <th>TOP de destino</th>
                    <th>Situação</th>
                  </tr>
                </thead>
                <tbody>
                  @for (p of d.pedidos; track p.nunota) {
                    <tr [class.fec__linha--erro]="p.ok === false || p.situacao === 'bloqueado'">
                      <td class="fec__mono">{{ p.nunota }}</td>
                      <td class="fec__cliente" [title]="p.cliente ?? ''">{{ p.cliente ?? '—' }}</td>
                      <td>
                        @if (p.nfce === true) { <span class="fec__tipo fec__tipo--nfce">NFC-e</span> }
                        @else if (p.nfce === false) { <span class="fec__tipo">NF-e</span> }
                        @else { — }
                      </td>
                      <td class="fec__mono">{{ p.codTipOper ? p.codTipOper + ' — ' + p.descricaoTop : '—' }}</td>
                      <td class="fec__sit">
                        @if (p.ok === true) {
                          <span class="fec__ok"><oq-icon name="check" [size]="12" /> Nota {{ p.notasGeradas.join(', ') }}</span>
                        } @else if (p.ok === false) {
                          <span class="fec__falha" [title]="p.erro ?? ''"><oq-icon name="circle-alert" [size]="12" /> {{ p.erro }}</span>
                        } @else if (p.situacao === 'faturar') {
                          <span class="fec__neutro">a faturar</span>
                        } @else if (p.situacao === 'pronto') {
                          <span class="fec__ok fec__ok--leve"><oq-icon name="check" [size]="12" /> já tem nota / sem faturamento</span>
                        } @else {
                          <span class="fec__falha"><oq-icon name="circle-alert" [size]="12" /> {{ p.motivo }}</span>
                        }
                      </td>
                    </tr>
                  }
                </tbody>
              </table>
            </div>

            @if (d.mensagem) {
              <div class="fec__caixa" [class.fec__caixa--erro]="falhas() > 0 || !d.podeFechar" [class.fec__caixa--aviso]="falhas() === 0 && d.podeFechar && !d.ocFechada">
                <oq-icon [name]="falhas() > 0 || !d.podeFechar ? 'circle-alert' : 'triangle'" [size]="16" />
                <span>{{ d.mensagem }}</span>
              </div>
            } @else if (!d.podeFechar) {
              <div class="fec__caixa fec__caixa--erro">
                <oq-icon name="circle-alert" [size]="16" />
                <span>Há pedidos bloqueados — resolva antes de fechar a OC.</span>
              </div>
            }
            }
          }
        </div>

        <div class="oq-modal__footer">
          <button type="button" class="oq-admin-btn oq-admin-btn--outline fec__btn" [disabled]="executando()" (click)="fechado.emit()">
            {{ executado() ? 'Concluir' : 'Cancelar' }}
          </button>
          @if (dados(); as d) {
            @if (d.podeFechar && !d.ocFechada) {
              <button type="button" class="oq-admin-btn oq-admin-btn--primary fec__btn" [disabled]="executando()" (click)="executar()">
                @if (executando()) {
                  <oq-spinner [size]="12" /> Faturando e fechando…
                } @else {
                  {{ executado() ? 'Tentar de novo' : 'Faturar notas e fechar OC' }}
                }
              </button>
            }
          }
        </div>
      </div>
    </div>
  `,
  styles: [`
    .fec { max-width: 920px; }
    .fec__cab { display: flex; align-items: center; gap: 8px; color: var(--oq-brand-accent); }
    .fec__carregando { display: flex; align-items: center; justify-content: center; gap: 10px; padding: 28px 0; font-size: 13px; color: var(--oq-text-secondary); }
    .fec__passos { display: flex; flex-wrap: wrap; gap: 6px 18px; margin: 0; padding: 0; list-style: none;
      font-family: var(--oq-font-display); font-size: 11px; font-weight: 600; text-transform: uppercase; letter-spacing: 0.05em; color: var(--oq-text-secondary); }
    .fec__passo--feito { color: var(--oq-success-foreground); }
    .fec__tabela-wrap { max-height: 46vh; overflow: auto; border: 1px solid var(--oq-border); border-radius: var(--oq-radius-block); }
    .fec__tabela { width: 100%; border-collapse: collapse; font-size: 12px; }
    .fec__tabela th { position: sticky; top: 0; padding: 7px 10px; background: var(--oq-surface-2); text-align: left;
      font-family: var(--oq-font-display); font-size: 10px; font-weight: 700; text-transform: uppercase; letter-spacing: 0.06em; color: var(--oq-text-secondary); }
    .fec__tabela td { padding: 7px 10px; border-top: 1px solid var(--oq-border); color: var(--oq-text-primary); vertical-align: middle; }
    .fec__linha--erro td { background: color-mix(in srgb, var(--oq-critical-soft) 55%, transparent); }
    .fec__mono { font-family: var(--oq-font-mono); white-space: nowrap; }
    .fec__cliente { max-width: 240px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
    .fec__tipo { padding: 1px 7px; border: 1px solid var(--oq-border-strong); border-radius: 999px; font-family: var(--oq-font-mono); font-size: 10px; font-weight: 700; }
    .fec__tipo--nfce { border-color: var(--oq-brand-accent); color: var(--oq-brand-accent); }
    .fec__sit { min-width: 200px; }
    .fec__ok, .fec__falha { display: inline-flex; align-items: center; gap: 5px; }
    .fec__ok { color: var(--oq-success-foreground); font-weight: 600; }
    .fec__ok--leve { font-weight: 400; }
    .fec__falha { color: var(--oq-critical-foreground); }
    .fec__neutro { color: var(--oq-text-secondary); }
    .fec__caixa { display: flex; align-items: flex-start; gap: 10px; padding: 10px 12px; font-size: 13px; line-height: 1.45;
      border: 1px solid var(--oq-border); border-radius: var(--oq-radius-block); color: var(--oq-success-foreground); background: var(--oq-success-soft); }
    .fec__caixa oq-icon { flex: none; margin-top: 1px; }
    .fec__caixa--erro { color: var(--oq-critical-foreground); background: var(--oq-critical-soft); border-color: var(--oq-critical); }
    .fec__caixa--aviso { color: var(--oq-warning-foreground); background: var(--oq-warning-soft); border-color: var(--oq-attention); }
    .fec__btn { min-height: 40px; padding: 9px 16px; font-size: 12px; }
  `],
})
export class OqFechamentoOcModalComponent implements OnInit {
  private readonly http = inject(HttpClient);
  private readonly feedback = inject(ActionFeedbackService);

  @Input({ required: true }) oc!: number;
  @Output() fechado = new EventEmitter<void>();

  readonly carregando = signal(true);
  readonly executando = signal(false);
  readonly executado = signal(false);
  readonly erro = signal<string | null>(null);
  readonly dados = signal<FechamentoOc | null>(null);

  falhas(): number {
    return this.dados()?.pedidos.filter((p) => p.ok === false).length ?? 0;
  }

  ngOnInit(): void {
    this.http.get<FechamentoOc>(`/api/ordens-carga/${this.oc}/fechamento`).subscribe({
      next: (d) => {
        this.dados.set(d);
        this.carregando.set(false);
      },
      error: (err) => {
        this.erro.set(err?.error?.erro ?? 'Não foi possível montar o fechamento da OC.');
        this.carregando.set(false);
      },
    });
  }

  executar(): void {
    if (this.executando()) return;
    this.executando.set(true);
    this.http.post<FechamentoOc>(`/api/ordens-carga/${this.oc}/fechar`, {}).subscribe({
      next: (d) => {
        this.dados.set(d);
        this.executando.set(false);
        this.executado.set(true);
        this.feedback.trigger(d.pedidos.some((p) => p.ok === false) ? 'ERRO_SANKHYA' : 'SUCESSO_SANKHYA', { toast: false });
      },
      error: (err) => {
        this.executando.set(false);
        this.erro.set(err?.error?.erro ?? 'Falha ao fechar a OC.');
        this.feedback.trigger('ERRO_SANKHYA', { toast: false });
      },
    });
  }
}
