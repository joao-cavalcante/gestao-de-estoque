import { Component, OnInit, computed, inject, signal } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { ActivatedRoute, Router } from '@angular/router';
import { OqIconComponent } from '../../shared/icons/oq-icon.component';
import { GalpaoService } from '../galpao.service';
import {
  Alerta,
  AlertasPlanta,
  Planta,
  ROTULO_CODIGO_ALERTA,
  ROTULO_SEVERIDADE,
  ROTULO_STATUS_ALERTA,
  SeveridadeAlerta,
  StatusAlerta,
} from '../galpao.model';
import { mensagemErro } from '../plantas-lista/plantas-lista.component';
import { ColunaExportacao, exportarCsv, exportarExcel, nomeArquivo } from '../exportar-tabela';

const POR_PAGINA = 50;
const ORDEM_SEV: Record<SeveridadeAlerta, number> = { CRITICO: 0, ATENCAO: 1, INFO: 2 };

/**
 * Alertas da operação (Planta Log: "Evidências, prioridade e tratamento das ocorrências"). Gerados a cada
 * leitura do Sankhya; aqui se reconhece, atribui, resolve ou reabre. Nada grava no Sankhya.
 */
@Component({
  selector: 'app-planta-alertas',
  standalone: true,
  imports: [CommonModule, FormsModule, OqIconComponent],
  templateUrl: './planta-alertas.component.html',
  styleUrl: './planta-alertas.component.scss',
})
export class PlantaAlertasComponent implements OnInit {
  private readonly service = inject(GalpaoService);
  private readonly route = inject(ActivatedRoute);
  private readonly router = inject(Router);

  readonly plantaId = this.route.snapshot.paramMap.get('id') ?? '';
  readonly rotuloCodigo = ROTULO_CODIGO_ALERTA;
  readonly rotuloSev = ROTULO_SEVERIDADE;
  readonly rotuloStatus = ROTULO_STATUS_ALERTA;

  readonly planta = signal<Planta | null>(null);
  readonly dados = signal<AlertasPlanta | null>(null);
  readonly carregando = signal(true);
  readonly erro = signal<string | null>(null);
  readonly feedback = signal<{ texto: string; erro: boolean } | null>(null);
  readonly salvando = signal<string | null>(null);
  readonly exportando = signal(false);
  readonly selecionado = signal<Alerta | null>(null);
  atribuidoEdicao = '';

  readonly filtroStatus = signal<'EM_ABERTO' | StatusAlerta | ''>('EM_ABERTO');
  readonly filtroSev = signal<SeveridadeAlerta | ''>('');
  readonly filtroCodigo = signal('');
  readonly busca = signal('');
  readonly somenteVigentes = signal(false);
  readonly pagina = signal(1);

  readonly alertas = computed(() => this.dados()?.alertas ?? []);

  /** Contagem por código (só os em aberto) — chips de filtro rápido. */
  readonly porCodigo = computed(() => {
    const m = new Map<string, { codigo: string; total: number; criticos: number }>();
    for (const a of this.alertas()) {
      if (a.status === 'RESOLVIDO') continue;
      const x = m.get(a.codigo) ?? { codigo: a.codigo, total: 0, criticos: 0 };
      x.total++;
      if (a.severidade === 'CRITICO') x.criticos++;
      m.set(a.codigo, x);
    }
    return [...m.values()].sort((a, b) => b.criticos - a.criticos || b.total - a.total);
  });

  readonly resumo = computed(() => {
    const abertos = this.alertas().filter((a) => a.status !== 'RESOLVIDO');
    return {
      criticos: abertos.filter((a) => a.severidade === 'CRITICO').length,
      atencao: abertos.filter((a) => a.severidade === 'ATENCAO').length,
      info: abertos.filter((a) => a.severidade === 'INFO').length,
      reconhecidos: abertos.filter((a) => a.status === 'RECONHECIDO').length,
      slaVencido: abertos.filter((a) => a.slaVencido).length,
    };
  });

  readonly linhas = computed(() => {
    const st = this.filtroStatus();
    const sev = this.filtroSev();
    const cod = this.filtroCodigo();
    const termo = this.busca().trim().toLowerCase();
    const vig = this.somenteVigentes();
    return this.alertas()
      .filter(
        (a) =>
          (st === '' || (st === 'EM_ABERTO' ? a.status !== 'RESOLVIDO' : a.status === st)) &&
          (!sev || a.severidade === sev) &&
          (!cod || a.codigo === cod) &&
          (!vig || a.vigente) &&
          (!termo ||
            String(a.codprod ?? '') === termo ||
            (a.produto ?? '').toLowerCase().includes(termo) ||
            (a.endereco ?? '').toLowerCase().includes(termo) ||
            (a.controle ?? '').toLowerCase() === termo ||
            a.mensagem.toLowerCase().includes(termo) ||
            (a.atribuido ?? '').toLowerCase().includes(termo)),
      )
      .sort((a, b) => ORDEM_SEV[a.severidade] - ORDEM_SEV[b.severidade] || Number(b.slaVencido) - Number(a.slaVencido) || b.criadoEm.localeCompare(a.criadoEm));
  });
  readonly totalPaginas = computed(() => Math.max(1, Math.ceil(this.linhas().length / POR_PAGINA)));
  readonly paginaAtual = computed(() => {
    const p = Math.min(this.pagina(), this.totalPaginas());
    return this.linhas().slice((p - 1) * POR_PAGINA, p * POR_PAGINA);
  });

  ngOnInit(): void {
    this.service.plantas().subscribe({ next: (l) => this.planta.set(l.find((p) => p.id === this.plantaId) ?? null), error: () => undefined });
    this.carregar(false);
  }

  carregar(atualizar: boolean): void {
    this.carregando.set(true);
    this.service.alertas(this.plantaId, atualizar).subscribe({
      next: (d) => {
        this.dados.set(d);
        this.erro.set(null);
        this.carregando.set(false);
        const sel = this.selecionado();
        if (sel) this.selecionar(d.alertas.find((a) => a.id === sel.id) ?? null);
      },
      error: (e) => {
        this.erro.set(mensagemErro(e, 'Não foi possível carregar os alertas.'));
        this.carregando.set(false);
      },
    });
  }

  voltar(): void {
    this.router.navigate(['/galpao', this.plantaId]);
  }

  filtrarCodigo(c: string): void {
    this.filtroCodigo.set(this.filtroCodigo() === c ? '' : c);
    this.pagina.set(1);
  }

  selecionar(a: Alerta | null): void {
    this.selecionado.set(a);
    this.atribuidoEdicao = a?.atribuido ?? '';
  }

  tratar(a: Alerta, status: StatusAlerta): void {
    this.salvando.set(a.id);
    this.feedback.set(null);
    const atribuido = this.selecionado()?.id === a.id ? this.atribuidoEdicao : a.atribuido;
    this.service.tratarAlerta(this.plantaId, a.id, { status, versao: a.versao, atribuido }).subscribe({
      next: (novo) => {
        this.salvando.set(null);
        this.dados.update((d) => (d ? { ...d, alertas: d.alertas.map((x) => (x.id === novo.id ? novo : x)) } : d));
        if (this.selecionado()?.id === novo.id) this.selecionar(novo);
        this.feedback.set({ texto: `Alerta ${this.rotuloStatus[status].toLowerCase()}.`, erro: false });
      },
      error: (e) => {
        this.salvando.set(null);
        this.feedback.set({ texto: mensagemErro(e, 'Não foi possível tratar o alerta.'), erro: true });
        if (e?.status === 409) this.carregar(false);
      },
    });
  }

  evidencias(a: Alerta): [string, string][] {
    return Object.entries(a.evidencia ?? {}).map(([k, v]) => [k, v == null ? 'N/D' : typeof v === 'number' ? v.toLocaleString('pt-BR', { maximumFractionDigits: 3 }) : String(v)]);
  }

  async exportar(tipo: 'csv' | 'xlsx'): Promise<void> {
    const colunas: ColunaExportacao<Alerta>[] = [
      { titulo: 'Severidade', valor: (a) => this.rotuloSev[a.severidade] },
      { titulo: 'Código', valor: (a) => a.codigo },
      { titulo: 'Ocorrência', valor: (a) => this.rotuloCodigo[a.codigo] ?? a.mensagem, largura: 30 },
      { titulo: 'Mensagem', valor: (a) => a.mensagem, largura: 40 },
      { titulo: 'Produto', valor: (a) => a.codprod },
      { titulo: 'Descrição', valor: (a) => a.produto, largura: 40 },
      { titulo: 'Controle', valor: (a) => a.controle },
      { titulo: 'Endereço', valor: (a) => a.endereco },
      { titulo: 'Status', valor: (a) => this.rotuloStatus[a.status] },
      { titulo: 'Atribuído', valor: (a) => a.atribuido },
      { titulo: 'SLA (h)', valor: (a) => a.slaHoras },
      { titulo: 'SLA vencido', valor: (a) => a.slaVencido },
      { titulo: 'Vigente', valor: (a) => a.vigente },
      { titulo: 'Criado em', valor: (a) => a.criadoEm },
      { titulo: 'Evidência', valor: (a) => JSON.stringify(a.evidencia), largura: 60 },
    ];
    const nome = nomeArquivo('alertas', this.planta()?.nome);
    if (tipo === 'csv') return exportarCsv(this.linhas(), colunas, nome);
    this.exportando.set(true);
    try {
      await exportarExcel(this.linhas(), colunas, nome, 'Alertas');
    } finally {
      this.exportando.set(false);
    }
  }

  sevCss(s: SeveridadeAlerta): string {
    return s.toLowerCase();
  }
}
