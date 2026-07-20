import { Component, computed, inject, OnDestroy, OnInit, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { Router } from '@angular/router';
import { Subscription } from 'rxjs';
import { SyncTickService } from '../shared/app-header/sync-tick.service';
import { OqKpiBarComponent } from './oq-kpi-bar/oq-kpi-bar.component';
import { OqToolbarComponent } from './oq-toolbar/oq-toolbar.component';
import { OqTaskCardComponent } from './oq-task-card/oq-task-card.component';
import { OqEmptyStateComponent } from './oq-empty-state/oq-empty-state.component';
import { ConferenciasService } from './conferencias.service';
import { FiltroStatus, FiltrosAvancados, OpcaoComCodigo, Tarefa } from './tarefa.model';

@Component({
  selector: 'app-fila-tarefas',
  standalone: true,
  imports: [FormsModule, OqKpiBarComponent, OqToolbarComponent, OqTaskCardComponent, OqEmptyStateComponent],
  templateUrl: './fila-tarefas.component.html',
  styleUrl: './fila-tarefas.component.scss',
})
export class FilaTarefasComponent implements OnInit, OnDestroy {
  private readonly conferenciasService = inject(ConferenciasService);
  private readonly router = inject(Router);
  private readonly syncTick = inject(SyncTickService);
  private syncSub?: Subscription;

  // TODO: ainda não existe login/JWT amarrando o usuário a um tenant (Fase 0/1
  // do motor de tarefas) — por enquanto fixo em 'modial' pra validar a
  // integração real com o Sankhya.
  private readonly tenantAtual = 'modial';

  carregando = signal(true);
  erro = signal<string | null>(null);

  private readonly tarefas = signal<Tarefa[]>([]);

  filtroAtivo = signal<FiltroStatus>('todos');
  termoBusca = signal('');
  paginaAtual = signal(1);
  itensPorPagina = signal(20);

  dropdownFiltrosAberto = signal(false);
  filtrosAvancados = signal<FiltrosAvancados>({ codigoParceiro: null, codigoVendedor: null, codigoTipoOperacao: null });

  ngOnInit(): void {
    this.carregarFila();
    this.syncSub = this.syncTick.onTick.subscribe(() => this.recarregarSilencioso());
  }

  ngOnDestroy(): void {
    this.syncSub?.unsubscribe();
  }

  carregarFila(): void {
    this.carregando.set(true);
    this.erro.set(null);
    this.conferenciasService.listarFila(this.tenantAtual).subscribe({
      next: (tarefas) => {
        this.tarefas.set(tarefas);
        this.carregando.set(false);
      },
      error: (err) => {
        this.erro.set(err?.error?.erro ?? 'Falha ao buscar a fila no Sankhya.');
        this.carregando.set(false);
      },
    });
  }

  /**
   * Chamado a cada ciclo do SyncTickService (sincronizado com o intervalo
   * real do job de sync do backend) — recarrega sem passar por
   * `carregando`, pra não piscar a tela inteira a cada 60s.
   */
  recarregarSilencioso(): void {
    this.conferenciasService.listarFila(this.tenantAtual).subscribe({
      next: (tarefas) => this.tarefas.set(tarefas),
      error: () => {
        // Falha num refresh silencioso não deve derrubar a tela — a próxima
        // tentativa (próximo ciclo) resolve sozinha.
      },
    });
  }

  readonly kpis = computed(() => {
    const todas = this.tarefas();
    return {
      total: todas.length,
      aguardando: todas.filter((t) => t.status === 'aguardando').length,
      andamento: todas.filter((t) => t.status === 'andamento').length,
      concluido: todas.filter((t) => t.status === 'concluido').length,
      atencao: todas.filter((t) => t.alerta !== null).length,
    };
  });

  /** Opções "cód - descrição" de cada select do painel avançado — só o que já aparece na fila carregada. */
  readonly opcoesParceiros = computed(() => this.opcoesComCodigo((t) => t.codigoCliente, (t) => t.cliente));
  readonly opcoesVendedores = computed(() => this.opcoesComCodigo((t) => t.codigoResponsavel, (t) => t.responsavel));
  readonly opcoesTiposOperacao = computed(() => this.opcoesComCodigo((t) => t.codigoTipoOperacao, (t) => t.tipoOperacao));

  private opcoesComCodigo(
    extrairCodigo: (t: Tarefa) => string | null,
    extrairLabel: (t: Tarefa) => string,
  ): OpcaoComCodigo[] {
    const vistos = new Map<string, string>();
    for (const t of this.tarefas()) {
      const codigo = extrairCodigo(t);
      const label = extrairLabel(t);
      if (codigo && label && label !== '—' && !vistos.has(codigo)) vistos.set(codigo, label);
    }
    return [...vistos.entries()]
      .map(([codigo, label]) => ({ codigo, label }))
      .sort((a, b) => a.label.localeCompare(b.label));
  }

  readonly totalFiltrosAvancadosAtivos = computed(() => {
    const f = this.filtrosAvancados();
    return [f.codigoParceiro, f.codigoVendedor, f.codigoTipoOperacao].filter((v) => v !== null).length;
  });

  readonly tarefasFiltradas = computed(() => {
    const filtro = this.filtroAtivo();
    const termo = this.termoBusca().trim().toLowerCase();
    const avancados = this.filtrosAvancados();

    return this.tarefas().filter((t) => {
      const passaFiltro =
        filtro === 'todos' ||
        (filtro === 'atencao' ? t.alerta !== null : t.status === filtro);

      const passaBusca =
        !termo ||
        t.cliente.toLowerCase().includes(termo) ||
        t.pedido.toLowerCase().includes(termo) ||
        t.nf.toLowerCase().includes(termo) ||
        t.numeroUnico.toLowerCase().includes(termo);

      const passaAvancados =
        (!avancados.codigoParceiro || t.codigoCliente === avancados.codigoParceiro) &&
        (!avancados.codigoVendedor || t.codigoResponsavel === avancados.codigoVendedor) &&
        (!avancados.codigoTipoOperacao || t.codigoTipoOperacao === avancados.codigoTipoOperacao);

      return passaFiltro && passaBusca && passaAvancados;
    });
  });

  readonly totalPaginas = computed(() =>
    Math.max(1, Math.ceil(this.tarefasFiltradas().length / this.itensPorPagina())),
  );

  readonly tarefasPaginadas = computed(() => {
    const inicio = (this.paginaAtual() - 1) * this.itensPorPagina();
    return this.tarefasFiltradas().slice(inicio, inicio + this.itensPorPagina());
  });

  readonly intervaloPagina = computed(() => {
    const total = this.tarefasFiltradas().length;
    if (total === 0) return { inicio: 0, fim: 0 };
    const inicio = (this.paginaAtual() - 1) * this.itensPorPagina() + 1;
    const fim = Math.min(inicio + this.itensPorPagina() - 1, total);
    return { inicio, fim };
  });

  irParaPagina(pagina: number): void {
    this.paginaAtual.set(Math.min(Math.max(1, pagina), this.totalPaginas()));
  }

  onFiltroChange(filtro: FiltroStatus): void {
    this.filtroAtivo.set(filtro);
    this.paginaAtual.set(1);
  }

  onBuscaChange(termo: string): void {
    this.termoBusca.set(termo);
    this.paginaAtual.set(1);
  }

  onItensPorPaginaChange(valor: number): void {
    this.itensPorPagina.set(valor);
    this.paginaAtual.set(1);
  }

  abrirFiltrosAvancados(): void {
    this.dropdownFiltrosAberto.update((v) => !v);
  }

  aplicarFiltrosAvancados(filtros: FiltrosAvancados): void {
    this.filtrosAvancados.set(filtros);
    this.paginaAtual.set(1);
    this.dropdownFiltrosAberto.set(false);
  }

  onVerDetalhes(tarefa: Tarefa): void {
    console.log('Ver detalhes:', tarefa.id);
  }

  onConferir(tarefa: Tarefa): void {
    this.router.navigate(['/conferencia', tarefa.numeroUnico], { state: { tarefa } });
  }
}
