import { HttpClient } from '@angular/common/http';
import { Component, OnDestroy, OnInit, computed, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { OqPanelSectionComponent } from '../conferencia/oq-panel-section/oq-panel-section.component';
import { OqIconComponent } from '../shared/icons/oq-icon.component';
import { ActionFeedbackService } from '../shared/action-feedback/action-feedback.service';

type StatusImpressao = 'aguardando_sefaz' | 'pronto' | 'imprimindo' | 'impresso' | 'erro';

interface JobImpressao {
  id: string;
  ordemCarga: number;
  status: StatusImpressao;
  motivo: string;
  notas: number;
  erro: string | null;
  agente: string | null;
  solicitadoPor: string | null;
  criadoEm: string;
  impressoEm: string | null;
  atualizadoEm: string | null;
}

interface AgenteImpressao {
  nome: string;
  vistoEm: string;
  online: boolean;
}

interface PainelImpressao {
  agentes: AgenteImpressao[];
  jobs: JobImpressao[];
}

const ROTULO: Record<StatusImpressao, string> = {
  aguardando_sefaz: 'Aguardando SEFAZ',
  pronto: 'Na fila',
  imprimindo: 'Imprimindo',
  impresso: 'Impresso',
  erro: 'Erro',
};

/**
 * Tela exclusiva da impressão automática (fica aberta no computador da expedição): OCs fechadas chegando,
 * situação de cada impressão (romaneio + DANFEs) e se o agente local está online. Quem imprime é o agente
 * (silencioso); a tela acompanha, mostra o PDF e pede reimpressão.
 */
@Component({
  selector: 'app-impressao-oc',
  standalone: true,
  imports: [FormsModule, OqPanelSectionComponent, OqIconComponent],
  templateUrl: './impressao-oc.component.html',
  styleUrl: './impressao-oc.component.scss',
})
export class ImpressaoOcComponent implements OnInit, OnDestroy {
  private readonly http = inject(HttpClient);
  private readonly feedback = inject(ActionFeedbackService);

  readonly painel = signal<PainelImpressao | null>(null);
  readonly falhaConexao = signal(false);
  readonly enviando = signal<number | null>(null);
  ocManual = '';
  private timer: ReturnType<typeof setTimeout> | null = null;

  readonly agentesOnline = computed(() => (this.painel()?.agentes ?? []).filter((a) => a.online));
  readonly nomesOnline = computed(() => this.agentesOnline().map((a) => a.nome).join(', '));
  readonly contagem = computed(() => {
    const jobs = this.painel()?.jobs ?? [];
    const conta = (...s: StatusImpressao[]) => jobs.filter((j) => s.includes(j.status)).length;
    return {
      fila: conta('aguardando_sefaz', 'pronto', 'imprimindo'),
      impressas: conta('impresso'),
      erros: conta('erro'),
    };
  });

  ngOnInit(): void {
    this.atualizar();
  }

  ngOnDestroy(): void {
    if (this.timer) clearTimeout(this.timer);
  }

  rotulo(s: StatusImpressao): string {
    return ROTULO[s] ?? s;
  }

  /** Horário de Brasília (o servidor manda ISO em UTC). */
  hora(iso: string | null): string {
    if (!iso) return '';
    return new Date(iso).toLocaleString('pt-BR', { timeZone: 'America/Sao_Paulo', day: '2-digit', month: '2-digit', hour: '2-digit', minute: '2-digit' });
  }

  atualizar(): void {
    if (this.timer) clearTimeout(this.timer);
    this.http.get<PainelImpressao>('/api/impressao/painel').subscribe({
      next: (p) => {
        this.painel.set(p);
        this.falhaConexao.set(false);
        this.timer = setTimeout(() => this.atualizar(), 5000);
      },
      error: () => {
        this.falhaConexao.set(true);
        this.timer = setTimeout(() => this.atualizar(), 10000);
      },
    });
  }

  reimprimir(oc: number | string): void {
    const numero = Number(String(oc).trim());
    if (!numero) return;
    this.enviando.set(numero);
    this.http.post(`/api/impressao/oc/${numero}/reimprimir`, {}).subscribe({
      next: () => {
        this.enviando.set(null);
        this.ocManual = '';
        this.feedback.trigger('SUCESSO_SANKHYA', { mensagem: `OC ${numero} enviada para a impressora.`, som: false });
        this.atualizar();
      },
      error: () => {
        this.enviando.set(null);
        this.feedback.trigger('ERRO', { mensagem: `Não foi possível imprimir a OC ${numero}.` });
      },
    });
  }

  /** PDF numa aba nova (download autenticado: o interceptor põe o token). */
  verPdf(job: JobImpressao): void {
    const aba = window.open('', '_blank');
    this.http.get(`/api/impressao/oc/job/${job.id}/pdf`, { responseType: 'blob' }).subscribe({
      next: (blob) => {
        const url = URL.createObjectURL(blob);
        if (aba) aba.location.href = url; else window.open(url, '_blank');
        setTimeout(() => URL.revokeObjectURL(url), 60_000);
      },
      error: () => {
        aba?.close();
        this.feedback.trigger('ERRO', { mensagem: 'PDF ainda não disponível.' });
      },
    });
  }

  temPdf(job: JobImpressao): boolean {
    return job.status === 'pronto' || job.status === 'imprimindo' || job.status === 'impresso' || (job.status === 'erro' && job.notas > 0 && !job.erro?.startsWith('SEFAZ'));
  }
}
