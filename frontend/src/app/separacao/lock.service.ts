import { HttpClient } from '@angular/common/http';
import { Injectable, inject, signal } from '@angular/core';
import { Observable } from 'rxjs';

const CHAVE_TOKEN = 'wms-lock-token';

/**
 * Lock exclusivo por etapa da conferência (V46). O token identifica esta ABA/tablet — não o usuário: em
 * conta Stage vários tablets dividem o mesmo login. Fica no sessionStorage, então recarregar a página
 * mantém o mesmo token (e a mesma posse), enquanto outra aba/tablet tem outro e é barrada.
 */
@Injectable({ providedIn: 'root' })
export class LockService {
  private readonly http = inject(HttpClient);
  private readonly baseUrl = '/api/separacao';

  /** Mensagem quando o backend rejeitou uma operação com LOCK_INVALIDO (sessão expirou / outro assumiu). */
  readonly invalido = signal<string | null>(null);

  readonly token: string = this.obterOuCriarToken();

  private obterOuCriarToken(): string {
    try {
      const salvo = sessionStorage.getItem(CHAVE_TOKEN);
      if (salvo) return salvo;
    } catch {
      /* sessionStorage indisponível: o token vale só enquanto o app estiver aberto */
    }
    const novo = typeof crypto !== 'undefined' && 'randomUUID' in crypto ? crypto.randomUUID() : this.uuidFallback();
    try {
      sessionStorage.setItem(CHAVE_TOKEN, novo);
    } catch {
      /* idem */
    }
    return novo;
  }

  private uuidFallback(): string {
    return 'xxxxxxxx-xxxx-4xxx-yxxx-xxxxxxxxxxxx'.replace(/[xy]/g, (c) => {
      const r = (Math.random() * 16) | 0;
      return (c === 'x' ? r : (r & 0x3) | 0x8).toString(16);
    });
  }

  /** Assume a etapa (`etapa` null em sessão não segmentada). 409 ETAPA_EM_USO se outra aba/tablet a tem. */
  adquirir(tenant: string, sessaoId: string, etapa: number | null): Observable<{ ok: boolean }> {
    return this.http.post<{ ok: boolean }>(`${this.baseUrl}/sessoes/${sessaoId}/lock`, this.corpo(etapa), { params: { tenant } });
  }

  /** Renova a atividade do lock. 409 LOCK_INVALIDO = expirou ou foi assumido por outro. */
  heartbeat(tenant: string, sessaoId: string, etapa: number | null): Observable<{ ok: boolean }> {
    return this.http.post<{ ok: boolean }>(`${this.baseUrl}/sessoes/${sessaoId}/heartbeat`, this.corpo(etapa), { params: { tenant } });
  }

  /** Libera ao sair da conferência (best-effort — o que vale mesmo é a expiração de 10 min). */
  liberar(tenant: string, sessaoId: string, etapa: number | null): Observable<{ ok: boolean }> {
    return this.http.post<{ ok: boolean }>(`${this.baseUrl}/sessoes/${sessaoId}/lock/liberar`, this.corpo(etapa), { params: { tenant } });
  }

  private corpo(etapa: number | null): { etapa?: number } {
    return etapa == null ? {} : { etapa };
  }
}
