import { HttpClient } from '@angular/common/http';
import { Injectable, OnDestroy, inject, signal } from '@angular/core';
import { Subject } from 'rxjs';
import { AuthService } from '../../auth/auth.service';

/**
 * Mesmo intervalo do job de sync real (`tenancy.sync_estado.intervalo_segundos`,
 * default 60s — ver V3__tarefas_sync.sql / SyncWorkerPool.kt). Se o intervalo
 * do tenant mudar no banco, ajustar aqui também (não há endpoint ainda pra
 * ler esse valor dinamicamente do backend).
 *
 * Contador global (era local ao <oq-header> antes dele virar componente
 * global) — assim qualquer página (ex.: FilaTarefasComponent) pode reagir
 * a `onTick` pra recarregar dados, mesmo sem o header estar renderizado
 * na mesma árvore de componentes dela.
 */
const CICLO_SYNC_SEGUNDOS = 60;

@Injectable({ providedIn: 'root' })
export class SyncTickService implements OnDestroy {
  readonly segundosDesdeSync = signal(0);
  /** true enquanto um "forçar sync" está em andamento no backend. */
  readonly sincronizando = signal(false);

  private readonly http = inject(HttpClient);
  private readonly auth = inject(AuthService);
  private readonly tick$ = new Subject<void>();
  readonly onTick = this.tick$.asObservable();
  private readonly intervalId: ReturnType<typeof setInterval>;

  constructor() {
    this.intervalId = setInterval(() => {
      const novo = this.segundosDesdeSync() + 1;
      if (novo >= CICLO_SYNC_SEGUNDOS) {
        this.segundosDesdeSync.set(0);
        this.tick$.next();
      } else {
        this.segundosDesdeSync.set(novo);
      }
    }, 1000);
  }

  ngOnDestroy(): void {
    clearInterval(this.intervalId);
  }

  get proximaSegundos(): number {
    return CICLO_SYNC_SEGUNDOS - this.segundosDesdeSync();
  }

  /** Ícone de sync só gira brevemente no início de cada ciclo (ou enquanto força). */
  get girando(): boolean {
    return this.sincronizando() || this.segundosDesdeSync() < 2;
  }

  /**
   * Força o ciclo de sync no backend AGORA (POST /api/tarefas/sincronizar) e,
   * ao terminar, reinicia o contador e dispara `onTick` pra todas as telas
   * recarregarem. Falha não trava a UI — dispara o tick assim mesmo.
   */
  forcarSync(): void {
    if (this.sincronizando()) return;
    const tenant = this.auth.obterTenantSlug();
    if (!tenant) return;
    this.sincronizando.set(true);
    this.http.post('/api/tarefas/sincronizar', null, { params: { tenant } }).subscribe({
      next: () => this.finalizarForcado(),
      error: () => this.finalizarForcado(),
    });
  }

  private finalizarForcado(): void {
    this.sincronizando.set(false);
    this.segundosDesdeSync.set(0);
    this.tick$.next();
  }
}
