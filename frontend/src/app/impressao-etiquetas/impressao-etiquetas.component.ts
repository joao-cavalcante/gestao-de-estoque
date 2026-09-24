import { Component, ElementRef, OnInit, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { AuthService } from '../auth/auth.service';
import { SeparacaoService } from '../separacao/separacao.service';
import { ConferenciaFinalizada } from '../separacao/separacao.model';
import { OqSkeletonComponent } from '../shared/oq-skeleton/oq-skeleton.component';

/**
 * Lista as conferências finalizadas pelo WMS pra reimpressão de etiquetas —
 * espelha a tela /impressao-etiquetas do fila-de-conferencia.
 */
@Component({
  selector: 'app-impressao-etiquetas',
  standalone: true,
  imports: [FormsModule, OqSkeletonComponent],
  templateUrl: './impressao-etiquetas.component.html',
  styleUrl: './impressao-etiquetas.component.scss',
})
export class ImpressaoEtiquetasComponent implements OnInit {
  private readonly auth = inject(AuthService);
  private readonly separacao = inject(SeparacaoService);
  private readonly host = inject(ElementRef<HTMLElement>);

  readonly PER_PAGE = 24;

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

  get totalPaginas(): number {
    return Math.max(Math.ceil(this.total() / this.PER_PAGE), 1);
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
        perPage: this.PER_PAGE,
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

  proxima(): void {
    if ((this.page() + 1) * this.PER_PAGE < this.total()) {
      this.page.update((p) => p + 1);
      this.buscar(false);
      this.voltarAoTopo();
    }
  }

  anterior(): void {
    if (this.page() > 0) {
      this.page.update((p) => p - 1);
      this.buscar(false);
      this.voltarAoTopo();
    }
  }

  /** Trocou de página pelo controle do rodapé: volta a lista pro começo. */
  private voltarAoTopo(): void {
    this.host.nativeElement.scrollTo({ top: 0 });
  }

  /** Etiqueta térmica dos itens pesáveis da conferência (reimprime o mesmo número se já existir). */
  imprimirPeso(item: ConferenciaFinalizada): void {
    window.open(`/etiquetas-peso/${item.sessaoId}`, '_blank');
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
