import { Component, ElementRef, OnInit, ViewChild, computed, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { AuthService } from '../auth/auth.service';
import { SeparacaoService } from '../separacao/separacao.service';
import { ConferenciaFinalizada } from '../separacao/separacao.model';
import { OqSkeletonComponent } from '../shared/oq-skeleton/oq-skeleton.component';
import { OqIconComponent } from '../shared/icons/oq-icon.component';
import { OqViewToggleComponent } from '../shared/lista-layout/oq-view-toggle.component';
import { OqPaginacaoComponent } from '../shared/lista-layout/oq-paginacao.component';
import { ITENS_POR_PAGINA, ViewMode, itensValidosPara, lerViewMode, salvarViewMode } from '../shared/lista-layout/view-mode';

const CHAVE_VIEW_MODE = 'impressao-etiquetas-view-mode';

/**
 * Lista as conferências finalizadas pelo WMS pra reimpressão de etiquetas —
 * espelha a tela /impressao-etiquetas do fila-de-conferencia. Mesmo layout da
 * Fila de Tarefas (estilos globais em styles/_lista-layout.scss); aqui filtro e
 * paginação são NO SERVIDOR (itens/página vai direto como perPage).
 */
@Component({
  selector: 'app-impressao-etiquetas',
  standalone: true,
  imports: [FormsModule, OqSkeletonComponent, OqIconComponent, OqViewToggleComponent, OqPaginacaoComponent],
  templateUrl: './impressao-etiquetas.component.html',
  styleUrl: './impressao-etiquetas.component.scss',
})
export class ImpressaoEtiquetasComponent implements OnInit {
  private readonly auth = inject(AuthService);
  private readonly separacao = inject(SeparacaoService);

  /** Cards (grid) ou lista (tabela) — preferência do navegador, só troca a renderização. */
  readonly viewMode = signal<ViewMode>(lerViewMode(CHAVE_VIEW_MODE));
  readonly opcoesItensPorPagina = computed(() => ITENS_POR_PAGINA[this.viewMode()]);
  readonly itensPorPagina = signal(itensValidosPara(this.viewMode(), 20));

  /** Área que rola — volta pro topo ao trocar de página. */
  @ViewChild('rolagem') private rolagem?: ElementRef<HTMLElement>;

  readonly itens = signal<ConferenciaFinalizada[]>([]);
  readonly total = signal(0);
  readonly page = signal(0);
  readonly carregando = signal(true);
  readonly erro = signal<string | null>(null);

  filtroNota: number | null = null;
  filtroUnico: number | null = null;
  private debounce?: ReturnType<typeof setTimeout>;

  get tenant(): string {
    return this.auth.obterTenantSlug() ?? '';
  }

  ngOnInit(): void {
    this.buscar();
  }

  buscar(resetPage = true): void {
    if (resetPage) this.page.set(0);
    this.carregando.set(true);
    this.erro.set(null);
    this.separacao
      .listarConferenciasFinalizadas(this.tenant, {
        numnota: this.filtroNota ?? undefined,
        nunota: this.filtroUnico ?? undefined,
        page: this.page(),
        perPage: this.itensPorPagina(),
      })
      .subscribe({
        next: (r) => {
          this.itens.set(r.itens);
          this.total.set(r.total);
          this.carregando.set(false);
        },
        error: (err) => {
          this.erro.set(err?.error?.erro ?? 'Falha ao carregar as conferências.');
          this.carregando.set(false);
        },
      });
  }

  onFiltroChange(): void {
    clearTimeout(this.debounce);
    this.debounce = setTimeout(() => this.buscar(), 400);
  }

  irParaPagina(p: number): void {
    this.page.set(p);
    this.buscar(false);
    this.rolagem?.nativeElement.scrollTo({ top: 0 });
  }

  onItensPorPaginaChange(valor: number): void {
    this.itensPorPagina.set(valor);
    this.buscar();
  }

  /**
   * Troca cards ↔ lista. Com os mesmos itens/página só muda a renderização (sem ir ao servidor);
   * se o modo novo não tem essa opção, busca de novo mantendo o 1º item visível.
   */
  onViewModeChange(modo: ViewMode): void {
    if (modo === this.viewMode()) return;
    const primeiro = this.page() * this.itensPorPagina();
    const n = itensValidosPara(modo, this.itensPorPagina());
    this.viewMode.set(modo);
    salvarViewMode(CHAVE_VIEW_MODE, modo);
    if (n !== this.itensPorPagina()) {
      this.itensPorPagina.set(n);
      this.page.set(Math.floor(primeiro / n));
      this.buscar(false);
    }
  }

  tituloPeso(item: ConferenciaFinalizada): string {
    return item.temPesavel ? 'Imprimir etiqueta de peso' : 'Esta conferência não tem produto pesável — sem etiqueta de peso';
  }

  /** Etiqueta térmica dos itens pesáveis da conferência (reimprime o mesmo número se já existir). */
  imprimirPeso(item: ConferenciaFinalizada): void {
    window.open(`/etiquetas-peso/${item.sessaoId}`, '_blank');
  }

  /** "Secos, Refrigerado" — etapas concluídas de uma conferência parcial. */
  rotuloEtapas(tipos: number[] | undefined): string {
    const nomes: Record<number, string> = { 1: 'Secos', 2: 'Refrigerado', 3: 'Congelado' };
    return (tipos ?? []).map((t) => nomes[t] ?? `Etapa ${t}`).join(', ');
  }

  imprimir(item: ConferenciaFinalizada): void {
    window.open(`/etiquetas/${item.sessaoId}`, '_blank');
  }

  data(iso: string | null): string {
    if (!iso) return '—';
    const d = new Date(iso);
    return isNaN(d.getTime()) ? iso : d.toLocaleDateString('pt-BR');
  }
}
