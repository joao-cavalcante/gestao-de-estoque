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

  /** Liga/desliga a conferência por etapa pras notas deste TOP (só ADMINISTRADOR). */
  definirConferenciaPorEtapa(codtop: number, conferenciaPorEtapa: boolean): Observable<{ ok: boolean }> {
    return this.http.put<{ ok: boolean }>(`/api/tipos-operacao/${codtop}/config`, { conferenciaPorEtapa });
  }
}
