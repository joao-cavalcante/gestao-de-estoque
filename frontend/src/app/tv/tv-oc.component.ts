import { HttpClient } from '@angular/common/http';
import { Component, OnDestroy, OnInit, computed, inject, signal } from '@angular/core';
import { DatePipe } from '@angular/common';
import { Subscription, catchError, of, switchMap, timer } from 'rxjs';
import { TvCarga, TvOc } from './tv.model';

const INTERVALO_API_MS = 15_000;
const INTERVALO_PAGINA_MS = 12_000;
const OCS_POR_PAGINA = 9;

/**
 * TV exclusiva de Ordens de Carga (/tv/carga) — painel de parede, sem interação. GET /api/tv/carga a cada
 * 15 s (só banco local + cache de transporte). Uma OC por cartão: conferência (pedidos) e carregamento
 * (itens do checklist), peso a separar e o que está aguardando liberação. OC conferida E carregada sai.
 */
@Component({
  selector: 'app-tv-oc',
  standalone: true,
  imports: [DatePipe],
  host: { 'data-theme': 'light' },
  templateUrl: './tv-oc.component.html',
  styleUrl: './tv-oc.component.scss',
})
export class TvOcComponent implements OnInit, OnDestroy {
  private readonly http = inject(HttpClient);

  readonly dados = signal<TvCarga | null>(null);
  readonly pendente = signal(false);
  readonly ultimaOkEm = signal<number | null>(null);
  readonly agora = signal(Date.now());
  readonly pagina = signal(0);
  readonly telaCheia = signal(!!document.fullscreenElement);

  private assinatura?: Subscription;
  private relogio?: ReturnType<typeof setInterval>;
  private rotacao?: ReturnType<typeof setInterval>;
  private readonly aoMudarTelaCheia = () => this.telaCheia.set(!!document.fullscreenElement);

  readonly totalPaginas = computed(() => Math.max(1, Math.ceil((this.dados()?.ocs.length ?? 0) / OCS_POR_PAGINA)));
  readonly visiveis = computed(() => {
    const lista = this.dados()?.ocs ?? [];
    const p = this.pagina() % this.totalPaginas();
    return lista.slice(p * OCS_POR_PAGINA, (p + 1) * OCS_POR_PAGINA);
  });

  ngOnInit(): void {
    this.assinatura = timer(0, INTERVALO_API_MS)
      .pipe(
        switchMap(() =>
          this.http.get<TvCarga>('/api/tv/carga').pipe(
            catchError(() => {
              this.pendente.set(true);
              return of(null);
            }),
          ),
        ),
      )
      .subscribe((r) => {
        if (!r) return; // mantém a última informação válida
        this.dados.set(r);
        this.ultimaOkEm.set(Date.now());
        this.pendente.set(false);
      });
    this.relogio = setInterval(() => this.agora.set(Date.now()), 1000);
    this.rotacao = setInterval(() => this.pagina.update((p) => (p + 1) % this.totalPaginas()), INTERVALO_PAGINA_MS);
    document.addEventListener('fullscreenchange', this.aoMudarTelaCheia);
  }

  ngOnDestroy(): void {
    this.assinatura?.unsubscribe();
    if (this.relogio) clearInterval(this.relogio);
    if (this.rotacao) clearInterval(this.rotacao);
    document.removeEventListener('fullscreenchange', this.aoMudarTelaCheia);
  }

  entrarTelaCheia(): void {
    document.documentElement.requestFullscreen?.().catch(() => undefined);
  }

  segundosDesdeAtualizacao(): number {
    const ok = this.ultimaOkEm();
    return ok ? Math.max(0, Math.round((this.agora() - ok) / 1000)) : 0;
  }

  pct(feito: number, total: number): number {
    return total > 0 ? Math.round((feito / total) * 100) : 0;
  }

  numero(n: number): string {
    return String(n).padStart(2, '0');
  }

  /** Até 999 kg em kg inteiros ("845 kg"); daí pra cima em toneladas com 1 casa ("12,3 t"). */
  peso(kg: number): string {
    if (kg < 1000) return Math.round(kg).toLocaleString('pt-BR') + ' kg';
    return (kg / 1000).toLocaleString('pt-BR', { minimumFractionDigits: 1, maximumFractionDigits: 1 }) + ' t';
  }

  rotuloFase(o: TvOc): string {
    return o.fase === 'A_CARREGAR' ? 'A CARREGAR' : 'CONFERINDO';
  }
}
