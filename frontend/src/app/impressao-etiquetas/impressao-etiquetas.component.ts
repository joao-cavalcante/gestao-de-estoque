import { Component, ElementRef, OnInit, ViewChild, computed, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { AuthService } from '../auth/auth.service';
import { SeparacaoService } from '../separacao/separacao.service';
import { ConferenciaFinalizada, ConferenciasFinalizadasResposta } from '../separacao/separacao.model';
import { OqIconComponent } from '../shared/icons/oq-icon.component';
import { OqSpinnerComponent } from '../shared/icons/oq-spinner.component';
import { OqViewToggleComponent } from '../shared/lista-layout/oq-view-toggle.component';
import { OqPaginacaoComponent } from '../shared/lista-layout/oq-paginacao.component';
import { ITENS_POR_PAGINA, ViewMode, itensValidosPara, lerViewMode, salvarViewMode } from '../shared/lista-layout/view-mode';
import { Observable } from 'rxjs';

const CHAVE_VIEW_MODE = 'impressao-etiquetas-view-mode';

interface GrupoDia {
  rotulo: string;
  itens: ConferenciaFinalizada[];
}

/**
 * Balcão de reimpressão de etiquetas (17/11/2026 — antes era a grade de cards da Fila, "usando o layout padrão como
 * muleta"). Uma linha por conferência, agrupada por dia em que terminou; os botões dizem o que sai da impressora
 * ("6 etiquetas de volume", "3 de peso"). Busca única (nº único, depois NF — o filtro é no servidor), Enter com um
 * resultado só já abre a etiqueta. Cards/lista e paginação no servidor continuam. As páginas /etiquetas e
 * /etiquetas-peso (também usadas ao vivo na separação) não mudam.
 */
@Component({
  selector: 'app-impressao-etiquetas',
  standalone: true,
  imports: [FormsModule, OqIconComponent, OqSpinnerComponent, OqViewToggleComponent, OqPaginacaoComponent],
  templateUrl: './impressao-etiquetas.component.html',
  styleUrl: './impressao-etiquetas.component.scss',
})
export class ImpressaoEtiquetasComponent implements OnInit {
  private readonly auth = inject(AuthService);
  private readonly separacao = inject(SeparacaoService);

  /** Lista (linhas do balcão) ou cards (mesmo conteúdo em grade) — preferência do navegador. */
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
  /** A busca achou pela NF (não pelo nº único) — avisado na tela. */
  readonly achouPorNota = signal(false);

  /** Busca única: nº único ou NF (só número). */
  termo = '';
  private debounce?: ReturnType<typeof setTimeout>;

  get tenant(): string {
    return this.auth.obterTenantSlug() ?? '';
  }

  ngOnInit(): void {
    this.buscar();
  }

  private consultar(filtro: { nunota?: number; numnota?: number }): Observable<ConferenciasFinalizadasResposta> {
    return this.separacao.listarConferenciasFinalizadas(this.tenant, { ...filtro, page: this.page(), perPage: this.itensPorPagina() });
  }

  /** Número digitado → tenta como nº único; sem resultado, tenta como NF (o servidor filtra por um OU outro). */
  buscar(resetPage = true, aoTerminar?: () => void): void {
    if (resetPage) this.page.set(0);
    this.carregando.set(true);
    this.erro.set(null);
    this.achouPorNota.set(false);
    const numero = /^\d+$/.test(this.termo.trim()) ? Number(this.termo.trim()) : null;
    const aplicar = (r: ConferenciasFinalizadasResposta) => {
      this.itens.set(r.itens);
      this.total.set(r.total);
      this.carregando.set(false);
      aoTerminar?.();
    };
    const falhar = (err: { error?: { erro?: string } }) => {
      this.erro.set(err?.error?.erro ?? 'Falha ao carregar as conferências.');
      this.carregando.set(false);
    };
    this.consultar(numero != null ? { nunota: numero } : {}).subscribe({
      next: (r) => {
        if (numero != null && r.total === 0) {
          this.consultar({ numnota: numero }).subscribe({
            next: (r2) => {
              this.achouPorNota.set(r2.total > 0);
              aplicar(r2);
            },
            error: falhar,
          });
        } else {
          aplicar(r);
        }
      },
      error: falhar,
    });
  }

  onBuscaChange(): void {
    clearTimeout(this.debounce);
    this.debounce = setTimeout(() => this.buscar(), 400);
  }

  /** Enter: busca na hora e, se sobrou um resultado só, já abre as etiquetas de volume dele. */
  onEnter(): void {
    clearTimeout(this.debounce);
    this.buscar(true, () => {
      const itens = this.itens();
      if (itens.length === 1 && this.termo.trim()) this.imprimir(itens[0]);
    });
  }

  limparBusca(): void {
    this.termo = '';
    this.buscar();
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

  /** Troca cards ↔ lista; se o modo novo não tem esses itens/página, busca de novo mantendo o 1º item visível. */
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

  /** Agrupa a página por dia em que a conferência terminou (Hoje / Ontem / dia da semana dd/mm). */
  readonly grupos = computed<GrupoDia[]>(() => {
    const grupos: GrupoDia[] = [];
    for (const item of this.itens()) {
      const rotulo = this.rotuloDia(item);
      const ultimo = grupos[grupos.length - 1];
      if (ultimo && ultimo.rotulo === rotulo) ultimo.itens.push(item);
      else grupos.push({ rotulo, itens: [item] });
    }
    return grupos;
  });

  private dataFim(item: ConferenciaFinalizada): Date | null {
    const iso = item.finalizadaEm ?? item.dataMovimento;
    if (!iso) return null;
    const d = new Date(iso);
    return isNaN(d.getTime()) ? null : d;
  }

  private rotuloDia(item: ConferenciaFinalizada): string {
    const d = this.dataFim(item);
    if (!d) return 'Sem data';
    const dia = (x: Date) => new Date(x.getFullYear(), x.getMonth(), x.getDate()).getTime();
    const diff = Math.round((dia(new Date()) - dia(d)) / 86_400_000);
    if (diff === 0) return 'Hoje';
    if (diff === 1) return 'Ontem';
    return d.toLocaleDateString('pt-BR', { weekday: 'long', day: '2-digit', month: '2-digit' });
  }

  /** "14:05" — hora em que terminou (a data está no título do grupo). */
  hora(item: ConferenciaFinalizada): string {
    const d = item.finalizadaEm ? new Date(item.finalizadaEm) : null;
    return d && !isNaN(d.getTime()) ? d.toLocaleTimeString('pt-BR', { hour: '2-digit', minute: '2-digit' }) : '';
  }

  /** Dica da linha: o que saiu da linha principal (NF, tipo de operação, vendedor, data do pedido). */
  dica(item: ConferenciaFinalizada): string {
    return [
      item.numeroNota ? `NF ${item.numeroNota}` : null,
      item.descricaoTipoOperacao,
      item.apelidoVendedor ? `Vendedor: ${item.apelidoVendedor}` : null,
      item.dataMovimento ? `Pedido de ${this.data(item.dataMovimento)}` : null,
    ]
      .filter(Boolean)
      .join(' · ');
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
    const nomes: Record<number, string> = { 1: 'Secos', 2: 'Refrigerado', 3: 'Congelado', 4: 'Não pesáveis', 5: 'Pesáveis' };
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
