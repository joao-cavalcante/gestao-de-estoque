import { Component, OnInit, computed, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { LiberacaoCorteService } from './liberacao-corte.service';
import { ConferenciaAguardandoCorte } from './liberacao-corte.model';
import { OqLiberacaoCorteModalComponent } from './oq-liberacao-corte-modal/oq-liberacao-corte-modal.component';
import { OqFaturamentoModalComponent } from '../separacao/oq-faturamento-modal.component';
import { OqSkeletonComponent } from '../shared/oq-skeleton/oq-skeleton.component';
import { OqIconComponent } from '../shared/icons/oq-icon.component';
import { OqViewToggleComponent } from '../shared/lista-layout/oq-view-toggle.component';
import { OqPaginacaoComponent } from '../shared/lista-layout/oq-paginacao.component';
import { ITENS_POR_PAGINA, ViewMode, itensValidosPara, lerViewMode, salvarViewMode } from '../shared/lista-layout/view-mode';

const CHAVE_VIEW_MODE = 'liberacao-corte-view-mode';

/**
 * Mesmo layout da Fila de Tarefas (barra com contador, busca, alternador
 * cards/lista, paginação no rodapé — estilos globais em styles/_lista-layout.scss).
 * A busca e a paginação são em memória: a lista de "aguardando corte" já vem
 * inteira do backend (revalidada contra o Sankhya a cada carregar()), então
 * não há por quê ir ao servidor de novo só pra filtrar o que já está na tela.
 */
@Component({
  selector: 'app-liberacao-corte',
  standalone: true,
  imports: [FormsModule, OqLiberacaoCorteModalComponent, OqFaturamentoModalComponent, OqSkeletonComponent, OqIconComponent, OqViewToggleComponent, OqPaginacaoComponent],
  templateUrl: './liberacao-corte.component.html',
  styleUrl: './liberacao-corte.component.scss',
})
export class LiberacaoCorteComponent implements OnInit {
  private readonly service = inject(LiberacaoCorteService);

  readonly lista = signal<ConferenciaAguardandoCorte[]>([]);
  readonly carregando = signal(true);
  readonly erro = signal<string | null>(null);
  readonly selecionada = signal<ConferenciaAguardandoCorte | null>(null);
  /** Faturamento oferecido depois que a liberação fechou todo o corte (CCO FATAOCONCLUIR='S'). */
  readonly faturamento = signal<{ sessaoId: string; rotulo: string } | null>(null);

  /** Busca por NF, Nº Único ou cliente (texto livre, em memória). */
  busca = '';

  /** Cards (grid) ou lista (tabela) — preferência do navegador, só troca a renderização. */
  readonly viewMode = signal<ViewMode>(lerViewMode(CHAVE_VIEW_MODE));
  readonly opcoesItensPorPagina = computed(() => ITENS_POR_PAGINA[this.viewMode()]);
  readonly itensPorPagina = signal(itensValidosPara(this.viewMode(), 20));
  /** Paginação em memória (a lista já vem inteira do backend). 0-based. */
  readonly page = signal(0);

  get totalPaginas(): number {
    return Math.max(Math.ceil(this.listaFiltrada.length / this.itensPorPagina()), 1);
  }

  /** Página atual sempre válida — busca/recarga podem encolher a lista. */
  get paginaIdx(): number {
    return Math.min(this.page(), this.totalPaginas - 1);
  }

  get paginaAtual(): ConferenciaAguardandoCorte[] {
    const p = this.paginaIdx;
    const n = this.itensPorPagina();
    return this.listaFiltrada.slice(p * n, (p + 1) * n);
  }

  onFiltroChange(): void {
    this.page.set(0);
  }

  onItensPorPaginaChange(valor: number): void {
    this.itensPorPagina.set(valor);
    this.page.set(0);
  }

  /** Troca cards ↔ lista mantendo o 1º item visível (se os itens/página mudarem). */
  onViewModeChange(modo: ViewMode): void {
    if (modo === this.viewMode()) return;
    const primeiro = this.paginaIdx * this.itensPorPagina();
    const n = itensValidosPara(modo, this.itensPorPagina());
    this.viewMode.set(modo);
    this.itensPorPagina.set(n);
    this.page.set(Math.floor(primeiro / n));
    salvarViewMode(CHAVE_VIEW_MODE, modo);
  }

  get listaFiltrada(): ConferenciaAguardandoCorte[] {
    const termo = this.busca.trim().toLowerCase();
    if (!termo) return this.lista();
    return this.lista().filter(
      (item) =>
        String(item.numeroNota ?? '').includes(termo) ||
        String(item.nunota).includes(termo) ||
        !!item.nomeParceiro?.toLowerCase().includes(termo),
    );
  }

  ngOnInit(): void {
    this.carregar();
  }

  carregar(): void {
    this.carregando.set(true);
    this.erro.set(null);
    this.service.listar().subscribe({
      next: (l) => {
        this.lista.set(l);
        this.carregando.set(false);
      },
      error: (err) => {
        this.erro.set(err?.error?.erro ?? 'Falha ao carregar a lista.');
        this.carregando.set(false);
      },
    });
  }

  abrir(item: ConferenciaAguardandoCorte): void {
    if (item.nuconf == null) {
      this.erro.set(`Pedido ${item.nunota}: NUCONF não encontrado localmente — a conferência não passou pelo WMS.`);
      return;
    }
    this.erro.set(null);
    this.selecionada.set(item);
  }

  rotulo(item: ConferenciaAguardandoCorte): string {
    return `Pedido ${item.numeroNota ?? item.nunota}${item.nomeParceiro ? ' — ' + item.nomeParceiro : ''}`;
  }

  /** A liberação fechou o corte e a CCO pede faturamento — abre o modal assim que o de liberação fechar. */
  aoLiberarTudo(sessaoId: string, item: ConferenciaAguardandoCorte): void {
    this.faturamento.set({ sessaoId, rotulo: this.rotulo(item) });
  }

  /** houveAcao = pelo menos um item foi liberado/negado — some o card na hora e revalida contra o backend. */
  aoFechar(houveAcao: boolean): void {
    const nunota = this.selecionada()?.nunota;
    this.selecionada.set(null);
    if (houveAcao && nunota != null) {
      this.lista.update((l) => l.filter((c) => c.nunota !== nunota));
    }
    // Revalida: libera parcial / negar mantém a conferência em 'C' → o card volta.
    setTimeout(() => this.carregar(), 600);
  }
}
