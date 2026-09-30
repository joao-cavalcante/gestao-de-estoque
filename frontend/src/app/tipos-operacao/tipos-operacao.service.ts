import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import { SincronizarTipoOperacaoResponse, TipoOperacao } from './tipos-operacao.model';

@Injectable({ providedIn: 'root' })
export class TiposOperacaoService {
  private readonly http = inject(HttpClient);

  listar(): Observable<TipoOperacao[]> {
    return this.http.get<TipoOperacao[]>('/api/tipos-operacao');
  }

  sincronizar(): Observable<SincronizarTipoOperacaoResponse> {
    return this.http.post<SincronizarTipoOperacaoResponse>('/api/tipos-operacao/sincronizar', {});
  }

  /** Usuários autorizados da TOP (admin) — lista vazia = sem restrição. */
  listarUsuarios(codtop: number): Observable<{ usuarioIds: string[] }> {
    return this.http.get<{ usuarioIds: string[] }>(`/api/tipos-operacao/${codtop}/usuarios`);
  }

  definirUsuarios(codtop: number, usuarioIds: string[]): Observable<{ usuarioIds: string[] }> {
    return this.http.put<{ usuarioIds: string[] }>(`/api/tipos-operacao/${codtop}/usuarios`, { usuarioIds });
  }

  /** Liga/desliga a conferência por etapa pras notas deste TOP (só ADMINISTRADOR). */
  definirConferenciaPorEtapa(codtop: number, conferenciaPorEtapa: boolean): Observable<{ ok: boolean }> {
    return this.http.put<{ ok: boolean }>(`/api/tipos-operacao/${codtop}/config`, { conferenciaPorEtapa });
  }
}
