import { Component, OnInit, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { LiberacaoCorteService } from './liberacao-corte.service';
import { ConferenciaAguardandoCorte } from './liberacao-corte.model';
import { OqLiberacaoCorteModalComponent } from './oq-liberacao-corte-modal/oq-liberacao-corte-modal.component';
import { OqSkeletonComponent } from '../shared/oq-skeleton/oq-skeleton.component';
import { OqIconComponent } from '../shared/icons/oq-icon.component';

/**
 * Mesmo modelo visual de ImpressaoEtiquetasComponent (header com contador +
 * filtros por NF/Nº Único) — só que os filtros aqui são em memória: a lista
 * de "aguardando corte" já vem inteira do backend (revalidada contra o
 * Sankhya a cada carregar()), não pagina, então não há por quê ir ao
 * servidor de novo só pra filtrar o que já está na tela.
 */
@Component({
  selector: 'app-liberacao-corte',
  standalone: true,
  imports: [FormsModule, OqLiberacaoCorteModalComponent, OqSkeletonComponent, OqIconComponent],
  templateUrl: './liberacao-corte.component.html',
  styleUrl: './liberacao-corte.component.scss',
})
export class LiberacaoCorteComponent implements OnInit {
  private readonly service = inject(LiberacaoCorteService);

  readonly lista = signal<ConferenciaAguardandoCorte[]>([]);
  readonly carregando = signal(true);
  readonly erro = signal<string | null>(null);
  readonly selecionada = signal<ConferenciaAguardandoCorte | null>(null);

  filtroNota: number | null = null;
  filtroUnico: number | null = null;

  /** Getter (não computed()) de propósito — filtroNota/filtroUnico são campos
   * simples com [(ngModel)] (mesmo padrão de ImpressaoEtiquetasComponent), não
   * signals; o template já reavalia a cada ciclo de CD, lista é pequena. */
  /** Paginação em memória (a lista já vem inteira do backend) — mesmo tamanho da Impressão de Etiquetas. */
  readonly PER_PAGE = 12;
  readonly page = signal(0);

  get totalPaginas(): number {
    return Math.max(Math.ceil(this.listaFiltrada.length / this.PER_PAGE), 1);
  }

  /** Página atual sempre válida — filtro/recarga podem encolher a lista. */
  get paginaIdx(): number {
    return Math.min(this.page(), this.totalPaginas - 1);
  }

  get paginaAtual(): ConferenciaAguardandoCorte[] {
    const p = this.paginaIdx;
    return this.listaFiltrada.slice(p * this.PER_PAGE, (p + 1) * this.PER_PAGE);
  }

  proxima(): void {
    if (this.paginaIdx < this.totalPaginas - 1) this.page.set(this.paginaIdx + 1);
  }

  anterior(): void {
    this.page.set(Math.max(0, this.paginaIdx - 1));
  }

  onFiltroChange(): void {
    this.page.set(0);
  }

  get listaFiltrada(): ConferenciaAguardandoCorte[] {
    const nota = this.filtroNota;
    const unico = this.filtroUnico;
    return this.lista().filter((item) => {
      if (nota != null && item.numeroNota !== nota) return false;
      if (unico != null && item.nunota !== unico) return false;
      return true;
    });
  }

  ngOnInit(): void {
    this.carregar();
  }

  carregar(): void {
    this.carregando.set(true);
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
