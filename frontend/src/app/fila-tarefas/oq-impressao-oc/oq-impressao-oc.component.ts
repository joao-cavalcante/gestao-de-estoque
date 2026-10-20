import { Component, Input, OnChanges, OnDestroy, inject, signal } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { OqIconComponent } from '../../shared/icons/oq-icon.component';
import { ActionFeedbackService } from '../../shared/action-feedback/action-feedback.service';

interface ImpressaoOc {
  id: string;
  ordemCarga: number;
  status: 'aguardando_sefaz' | 'pronto' | 'imprimindo' | 'impresso' | 'erro';
  motivo: string;
  notas: number;
  erro: string | null;
  agente: string | null;
  solicitadoPor: string | null;
  criadoEm: string;
  impressoEm: string | null;
}

const ROTULO: Record<ImpressaoOc['status'], string> = {
  aguardando_sefaz: 'Aguardando SEFAZ',
  pronto: 'Na fila da impressora',
  imprimindo: 'Imprimindo',
  impresso: 'Impresso',
  erro: 'Erro na impressão',
};

/** Enquanto o job não chega no fim, consulta a situação de novo. */
const EM_ANDAMENTO = new Set<ImpressaoOc['status']>(['aguardando_sefaz', 'pronto', 'imprimindo']);

/**
 * Situação da impressão automática (romaneio + DANFEs) da OC fechada — vai no bloco "OC fechada" da Fila
 * (centralizar na Fila, sem tela nova). O PDF é montado no servidor e impresso pelo agente local da balança.
 */
@Component({
  selector: 'oq-impressao-oc',
  standalone: true,
  imports: [OqIconComponent],
  template: `
    <span class="imp" [class]="'imp imp--' + (job()?.status ?? 'nenhum')">
      <oq-icon name="impressora" [size]="12" />
      @if (job(); as j) {
        <span class="imp__status" [title]="j.erro ?? ''">
          {{ rotulo(j) }}@if (j.notas) { · {{ j.notas }} nota(s) }@if (j.status === 'impresso' && j.agente) { em {{ j.agente }} }
        </span>
        @if (j.erro && j.status !== 'impresso') { <span class="imp__erro" [title]="j.erro">{{ j.erro }}</span> }
        @if (j.status === 'pronto' || j.status === 'imprimindo' || j.status === 'impresso' || (j.status === 'erro' && j.notas)) {
          <button type="button" class="imp__link" (click)="verPdf(j)">Ver PDF</button>
        }
      } @else {
        <span class="imp__status">Impressão automática não registrada</span>
      }
      <button type="button" class="imp__link" [disabled]="enviando()" (click)="reimprimir()">Reimprimir</button>
    </span>
  `,
  styles: [`
    .imp { display: inline-flex; align-items: center; gap: 8px; flex-wrap: wrap; font-size: 12px; color: var(--oq-ink-2, #555); }
    .imp__status { font-weight: 600; }
    .imp--impresso .imp__status { color: var(--oq-ok, #2e9e5b); }
    .imp--erro .imp__status { color: var(--oq-danger, #d64545); }
    .imp__erro { max-width: 360px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
    .imp__link { border: 0; background: none; padding: 0; color: var(--oq-brand-accent); text-decoration: underline; cursor: pointer; font-size: 12px; }
    .imp__link:disabled { opacity: .5; cursor: default; }
  `],
})
export class OqImpressaoOcComponent implements OnChanges, OnDestroy {
  @Input({ required: true }) oc!: string;

  private readonly http = inject(HttpClient);
  private readonly feedback = inject(ActionFeedbackService);
  readonly job = signal<ImpressaoOc | null>(null);
  readonly enviando = signal(false);
  private timer: ReturnType<typeof setTimeout> | null = null;

  ngOnChanges(): void {
    this.job.set(null);
    this.consultar();
  }

  ngOnDestroy(): void {
    if (this.timer) clearTimeout(this.timer);
  }

  rotulo(j: ImpressaoOc): string {
    return ROTULO[j.status] ?? j.status;
  }

  private consultar(): void {
    if (this.timer) clearTimeout(this.timer);
    this.http.get<ImpressaoOc[]>(`/api/impressao/oc`, { params: { ocs: this.oc } }).subscribe({
      next: (lista) => {
        const j = lista[0] ?? null;
        this.job.set(j);
        if (j && EM_ANDAMENTO.has(j.status)) this.timer = setTimeout(() => this.consultar(), 5000);
      },
      error: () => { this.timer = setTimeout(() => this.consultar(), 15000); },
    });
  }

  reimprimir(): void {
    this.enviando.set(true);
    this.http.post<ImpressaoOc>(`/api/impressao/oc/${this.oc}/reimprimir`, {}).subscribe({
      next: (j) => {
        this.enviando.set(false);
        this.job.set(j);
        this.feedback.trigger('SUCESSO_SANKHYA', { mensagem: `OC ${this.oc}: reimpressão enviada para a fila.`, som: false });
        this.consultar();
      },
      error: () => { this.enviando.set(false); this.feedback.trigger('ERRO', { mensagem: 'Não foi possível pedir a reimpressão.' }); },
    });
  }

  /** Abre o PDF numa aba nova (download autenticado: o interceptor põe o token). */
  verPdf(j: ImpressaoOc): void {
    const aba = window.open('', '_blank');
    this.http.get(`/api/impressao/oc/job/${j.id}/pdf`, { responseType: 'blob' }).subscribe({
      next: (blob) => {
        const url = URL.createObjectURL(blob);
        if (aba) aba.location.href = url; else window.open(url, '_blank');
        setTimeout(() => URL.revokeObjectURL(url), 60_000);
      },
      error: () => { aba?.close(); this.feedback.trigger('ERRO', { mensagem: 'PDF ainda não disponível.' }); },
    });
  }
}
