import { HttpClient } from '@angular/common/http';
import { Component, OnDestroy, OnInit, computed, inject, signal } from '@angular/core';
import { DatePipe } from '@angular/common';
import { ActivatedRoute } from '@angular/router';
import { Subscription, catchError, of, switchMap, timer } from 'rxjs';
import { OqIconComponent, OqIconName } from '../shared/icons/oq-icon.component';
import { OqModalidadePinsComponent } from '../shared/oq-modalidade-pins/oq-modalidade-pins.component';
import { TvConferencia, TvResumo } from './tv.model';

const INTERVALO_API_MS = 15_000;
const INTERVALO_PAGINA_MS = 10_000;
const CARDS_POR_PAGINA = 6;
const FINALIZADOS_POR_PAGINA = 10;
const OC_POR_PAGINA = 6;

const ETAPAS: Record<number, { label: string; icone: OqIconName }> = {
  1: { label: 'Secos', icone: 'seco' },
  2: { label: 'Refrigerado', icone: 'refrigerado' },
  3: { label: 'Congelado', icone: 'congelado' },
};

/**
 * TV de acompanhamento da conferência (/tv) — painel de parede, sem interação. Um único endpoint
 * agregado (GET /api/tv/resumo, só banco local) a cada 15 s via switchMap (requisição anterior é
 * cancelada; sem polling duplicado). Relógio, "há X min" e PARADO andam por um tick local de 1 s.
 * Erro de rede mantém os últimos dados e mostra "ATUALIZAÇÃO PENDENTE". Listas longas rotacionam
 * em páginas a cada 10 s (sem rolagem). Tema claro próprio, independente do tema do app.
 */
@Component({
  selector: 'app-tv',
  standalone: true,
  imports: [OqIconComponent, OqModalidadePinsComponent, DatePipe],
  templateUrl: './tv.component.html',
  styleUrl: './tv.component.scss',
  host: { 'data-theme': 'light' },
})
export class TvComponent implements OnInit, OnDestroy {
  private readonly http = inject(HttpClient);

  /** /tv = entradas e saídas | /tv/saida = só vendas (V/P) | /tv/entrada = só compras (C/O). */
  readonly movimento: 'saida' | 'entrada' | 'todos' = ((): 'saida' | 'entrada' | 'todos' => {
    const m = inject(ActivatedRoute).snapshot.paramMap.get('movimento');
    return m === 'saida' || m === 'entrada' ? m : 'todos';
  })();
  readonly rotuloMovimento = { saida: 'SAÍDAS · VENDAS', entrada: 'ENTRADAS · COMPRAS', todos: 'ENTRADAS E SAÍDAS' }[this.movimento];

  readonly dados = signal<TvResumo | null>(null);
  readonly ultimaOkEm = signal<number | null>(null);
  readonly pendente = signal(false);
  readonly agora = signal(Date.now());
  readonly pagCards = signal(0);
  readonly pagFinalizados = signal(0);
  readonly pagOc = signal(0);
  readonly telaCheia = signal(!!document.fullscreenElement);

  readonly etapas = ETAPAS;

  // ─── Só visual: realce do que acabou de mudar (entra, finaliza, contador muda, chegou dado novo) ───
  /** nunotas que entraram agora em "Em conferência" / "Recém finalizados" (realce de ~4 s). */
  readonly novosConf = signal<ReadonlySet<number>>(new Set());
  readonly novosFin = signal<ReadonlySet<number>>(new Set());
  /** KPIs cujo valor mudou na última resposta (realce de ~1,5 s). */
  readonly kpiMudou = signal<ReadonlySet<string>>(new Set());
  /** Piscada do "AO VIVO" quando chega resposta nova. */
  readonly pulso = signal(false);
  private timersRealce: ReturnType<typeof setTimeout>[] = [];

  private assinatura?: Subscription;
  private relogio?: ReturnType<typeof setInterval>;
  private rotacao?: ReturnType<typeof setInterval>;
  private readonly aoMudarTelaCheia = () => this.telaCheia.set(!!document.fullscreenElement);

  readonly totalPagCards = computed(() => Math.max(1, Math.ceil((this.dados()?.emConferencia.length ?? 0) / CARDS_POR_PAGINA)));
  readonly totalPagFinalizados = computed(() =>
    Math.max(1, Math.ceil((this.dados()?.recemFinalizados.length ?? 0) / FINALIZADOS_POR_PAGINA)),
  );
  readonly cardsVisiveis = computed(() => {
    const lista = this.dados()?.emConferencia ?? [];
    const p = this.pagCards() % this.totalPagCards();
    return lista.slice(p * CARDS_POR_PAGINA, (p + 1) * CARDS_POR_PAGINA);
  });
  readonly finalizadosVisiveis = computed(() => {
    const lista = this.dados()?.recemFinalizados ?? [];
    const p = this.pagFinalizados() % this.totalPagFinalizados();
    return lista.slice(p * FINALIZADOS_POR_PAGINA, (p + 1) * FINALIZADOS_POR_PAGINA);
  });
  readonly totalPagOc = computed(() => Math.max(1, Math.ceil((this.dados()?.ordensCarga?.length ?? 0) / OC_POR_PAGINA)));
  readonly ocVisiveis = computed(() => {
    const lista = this.dados()?.ordensCarga ?? [];
    const p = this.pagOc() % this.totalPagOc();
    return lista.slice(p * OC_POR_PAGINA, (p + 1) * OC_POR_PAGINA);
  });
  readonly paradosCount = computed(() => (this.dados()?.emConferencia ?? []).filter((c) => this.minutosParado(c) != null).length);

  ngOnInit(): void {
    this.assinatura = timer(0, INTERVALO_API_MS)
      .pipe(
        switchMap(() =>
          this.http.get<TvResumo>('/api/tv/resumo', { params: this.movimento === 'todos' ? {} : { movimento: this.movimento } }).pipe(
            catchError(() => {
              this.pendente.set(true);
              return of(null);
            }),
          ),
        ),
      )
      .subscribe((r) => {
        if (!r) return; // mantém a última informação válida
        this.marcarNovidades(this.dados(), r);
        this.dados.set(r);
        this.ultimaOkEm.set(Date.now());
        this.pendente.set(false);
      });
    this.relogio = setInterval(() => this.agora.set(Date.now()), 1000);
    this.rotacao = setInterval(() => {
      this.pagCards.update((p) => (p + 1) % this.totalPagCards());
      this.pagFinalizados.update((p) => (p + 1) % this.totalPagFinalizados());
      this.pagOc.update((p) => (p + 1) % this.totalPagOc());
    }, INTERVALO_PAGINA_MS);
    document.addEventListener('fullscreenchange', this.aoMudarTelaCheia);
  }

  ngOnDestroy(): void {
    this.assinatura?.unsubscribe();
    if (this.relogio) clearInterval(this.relogio);
    if (this.rotacao) clearInterval(this.rotacao);
    document.removeEventListener('fullscreenchange', this.aoMudarTelaCheia);
    this.timersRealce.forEach(clearTimeout);
  }

  /** Compara a resposta nova com a anterior só pra animar o que mudou — não altera dado nenhum. */
  private marcarNovidades(antes: TvResumo | null, depois: TvResumo): void {
    this.realcar(this.pulso, true, false, 900);
    if (!antes) return; // primeira carga: sem animação de "novo"
    const idsConfAntes = new Set(antes.emConferencia.map((c) => c.nunota));
    const idsFinAntes = new Set(antes.recemFinalizados.map((f) => f.nunota));
    const novosConf = depois.emConferencia.filter((c) => !idsConfAntes.has(c.nunota)).map((c) => c.nunota);
    const novosFin = depois.recemFinalizados.filter((f) => !idsFinAntes.has(f.nunota)).map((f) => f.nunota);
    if (novosConf.length) this.realcar(this.novosConf, new Set(novosConf), new Set(), 4000);
    if (novosFin.length) this.realcar(this.novosFin, new Set(novosFin), new Set(), 4000);
    const chaves = Object.keys(depois.resumo) as (keyof TvResumo['resumo'])[];
    const mudou = chaves.filter((k) => antes.resumo[k] !== depois.resumo[k]);
    if (mudou.length) this.realcar(this.kpiMudou, new Set<string>(mudou), new Set<string>(), 1500);
  }

  private realcar<T>(alvo: { set(v: T): void }, valor: T, depois: T, ms: number): void {
    alvo.set(valor);
    const t = setTimeout(() => {
      alvo.set(depois);
      this.timersRealce = this.timersRealce.filter((x) => x !== t);
    }, ms);
    this.timersRealce.push(t);
  }

  /** Etapas aguardando início nas conferências abertas (soma do "Disp." da faixa) — dado já existente. */
  etapasAguardando(d: TvResumo): number {
    return d.porEtapa.reduce((acc, e) => acc + e.disponivel, 0);
  }

  entrarTelaCheia(): void {
    document.documentElement.requestFullscreen?.().catch(() => undefined);
  }

  segundosDesdeAtualizacao(): number {
    const ok = this.ultimaOkEm();
    return ok ? Math.max(0, Math.round((this.agora() - ok) / 1000)) : 0;
  }

  /** "há 8 min" / "há 1 h 05" a partir de um ISO; null = sem horário confiável. */
  ha(iso: string | null): string | null {
    if (!iso) return null;
    const min = Math.max(0, Math.floor((this.agora() - Date.parse(iso)) / 60000));
    if (min < 60) return `há ${String(min).padStart(2, '0')} min`;
    return `há ${Math.floor(min / 60)} h ${String(min % 60).padStart(2, '0')}`;
  }

  /** Minutos parado (sem bipe/atividade além do limite) — null = não está parado. Só visual. */
  minutosParado(c: TvConferencia): number | null {
    const d = this.dados();
    const ref = c.ultimaAtividadeEm ?? c.inicioEm;
    if (!d || !ref) return null;
    const min = Math.floor((this.agora() - Date.parse(ref)) / 60000);
    return min >= d.limiteParadoMin ? min : null;
  }

  progressoPct(c: TvConferencia): number | null {
    if (c.itensTotal == null || c.itensConferidos == null || c.itensTotal <= 0) return null;
    return Math.round((c.itensConferidos / c.itensTotal) * 100);
  }

  numero(n: number): string {
    return String(n).padStart(2, '0');
  }

  /** Peso pra TV: até 999 kg em kg inteiros ("845 kg"); daí pra cima em toneladas com 1 casa ("12,3 t"). */
  peso(kg: number): string {
    if (kg < 1000) return Math.round(kg).toLocaleString('pt-BR') + ' kg';
    return (kg / 1000).toLocaleString('pt-BR', { minimumFractionDigits: 1, maximumFractionDigits: 1 }) + ' t';
  }
}
