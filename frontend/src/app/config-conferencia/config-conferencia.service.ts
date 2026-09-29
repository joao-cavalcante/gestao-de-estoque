import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import { ConfigConferenciaDetalhe, ConfigConferenciaListItem, SincronizarResponse, ToleranciaPeso } from './config-conferencia.model';

@Injectable({ providedIn: 'root' })
export class ConfigConferenciaService {
  private readonly http = inject(HttpClient);

  listar(): Observable<ConfigConferenciaListItem[]> {
    return this.http.get<ConfigConferenciaListItem[]>('/api/config-conferencia');
  }

  detalhe(nucco: number): Observable<ConfigConferenciaDetalhe> {
    return this.http.get<ConfigConferenciaDetalhe>(`/api/config-conferencia/${nucco}`);
  }

  sincronizar(): Observable<SincronizarResponse> {
    return this.http.post<SincronizarResponse>('/api/config-conferencia/sincronizar', {});
  }

  /** V50 — tolerância de peso do WMS pro NUCCO (null = sem limite; configurada=false = padrão). */
  toleranciaPeso(nucco: number): Observable<ToleranciaPeso> {
    return this.http.get<ToleranciaPeso>(`/api/config-conferencia/${nucco}/tolerancia-peso`);
  }

  salvarToleranciaPeso(nucco: number, acimaPct: number | null, abaixoPct: number | null): Observable<ToleranciaPeso> {
    return this.http.put<ToleranciaPeso>(`/api/config-conferencia/${nucco}/tolerancia-peso`, { acimaPct, abaixoPct });
  }

  /** Edição local — não escreve no Sankhya (ver ressalva no backend). */
  atualizar(nucco: number, campos: Record<string, string | null>): Observable<ConfigConferenciaDetalhe> {
    return this.http.patch<ConfigConferenciaDetalhe>(`/api/config-conferencia/${nucco}`, { campos });
  }
}
