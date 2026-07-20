import { Injectable, OnDestroy, signal } from '@angular/core';
import { Subject } from 'rxjs';

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

  /** Ícone de sync só gira brevemente no início de cada ciclo. */
  get girando(): boolean {
    return this.segundosDesdeSync() < 2;
  }
}
