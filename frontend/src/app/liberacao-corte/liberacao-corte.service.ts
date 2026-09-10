import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import {
  ConferenciaAguardandoCorte,
  LiberacaoPendente,
  LiberarCorteParams,
  LiberarCorteResposta,
  ValidarLiberadorParams,
} from './liberacao-corte.model';

/** JWT-auth (tenant vem do claim); o authInterceptor injeta o Bearer. */
@Injectable({ providedIn: 'root' })
export class LiberacaoCorteService {
  private readonly http = inject(HttpClient);
  private readonly baseUrl = '/api/liberacao-corte';

  listar(): Observable<ConferenciaAguardandoCorte[]> {
    return this.http.get<ConferenciaAguardandoCorte[]>(this.baseUrl);
  }

  pendentes(nuconf: number): Observable<LiberacaoPendente[]> {
    return this.http.get<LiberacaoPendente[]>(`${this.baseUrl}/pendentes`, { params: { nuconf: String(nuconf) } });
  }

  validarLiberador(body: ValidarLiberadorParams): Observable<{ ok: boolean }> {
    return this.http.post<{ ok: boolean }>(`${this.baseUrl}/validar-liberador`, body);
  }

  liberar(body: LiberarCorteParams): Observable<LiberarCorteResposta> {
    return this.http.post<LiberarCorteResposta>(`${this.baseUrl}/liberar`, body);
  }
}
