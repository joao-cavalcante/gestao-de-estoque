import { HttpBackend, HttpClient } from '@angular/common/http';
import { Injectable, NgZone, OnDestroy, inject, signal } from '@angular/core';

export type EstadoConexao = 'online' | 'offline';

/**
 * Indicador de conexão com o servidor do WMS (faixa no topo — OqConexaoBannerComponent).
 * Três fontes: eventos online/offline do navegador; o conexaoInterceptor (toda requisição que falha
 * por rede ou 502/503/504 marca offline, qualquer resposta do servidor marca online); e um teste
 * periódico em /api/health — a cada 20 s online, a cada 5 s offline (pra avisar logo que voltou).
 *
 * Só AVISA: não guarda bipe offline (os bipes continuam indo direto pro servidor).
 */
@Injectable({ providedIn: 'root' })
export class ConexaoService implements OnDestroy {
  /** HttpClient SEM interceptors — o teste não passa por auth/lock nem se realimenta. */
  private readonly http = new HttpClient(inject(HttpBackend));
  private readonly zone = inject(NgZone);

  readonly estado = signal<EstadoConexao>(typeof navigator !== 'undefined' && navigator.onLine === false ? 'offline' : 'online');
  /** true por alguns segundos depois que a conexão volta — faixa verde "restabelecida". */
  readonly acabouDeVoltar = signal(false);
  /** Quando caiu (pra mostrar "há X s"). */
  readonly offlineDesde = signal<number | null>(null);

  private timer: ReturnType<typeof setTimeout> | null = null;
  private timerVoltou: ReturnType<typeof setTimeout> | null = null;
  private testando = false;

  private readonly aoFicarOffline = () => this.zone.run(() => this.marcarOffline());
  private readonly aoFicarOnline = () => this.zone.run(() => this.testarAgora());

  constructor() {
    window.addEventListener('offline', this.aoFicarOffline);
    window.addEventListener('online', this.aoFicarOnline);
    this.agendar();
  }

  ngOnDestroy(): void {
    window.removeEventListener('offline', this.aoFicarOffline);
    window.removeEventListener('online', this.aoFicarOnline);
    if (this.timer) clearTimeout(this.timer);
    if (this.timerVoltou) clearTimeout(this.timerVoltou);
  }

  marcarOffline(): void {
    if (this.estado() === 'offline') return;
    this.estado.set('offline');
    this.offlineDesde.set(Date.now());
    this.acabouDeVoltar.set(false);
    this.agendar();
  }

  marcarOnline(): void {
    if (this.estado() === 'online') return;
    this.estado.set('online');
    this.offlineDesde.set(null);
    this.acabouDeVoltar.set(true);
    if (this.timerVoltou) clearTimeout(this.timerVoltou);
    this.timerVoltou = setTimeout(() => this.acabouDeVoltar.set(false), 4000);
    this.agendar();
  }

  /** Testa o servidor na hora (botão "Tentar agora" e evento `online` do navegador). */
  testarAgora(): void {
    if (this.testando) return;
    this.testando = true;
    this.http.get(`/api/health?t=${Date.now()}`).subscribe({
      next: () => {
        this.testando = false;
        this.marcarOnline();
        this.agendar();
      },
      error: () => {
        this.testando = false;
        this.marcarOffline();
        this.agendar();
      },
    });
  }

  private agendar(): void {
    if (this.timer) clearTimeout(this.timer);
    const intervalo = this.estado() === 'offline' ? 5_000 : 20_000;
    // Fora da zona: o timer não dispara detecção de mudança à toa a cada tick.
    this.zone.runOutsideAngular(() => {
      this.timer = setTimeout(() => this.zone.run(() => this.testarAgora()), intervalo);
    });
  }
}
