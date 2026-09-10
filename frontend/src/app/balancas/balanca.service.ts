import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import { Balanca, SalvarBalancaRequest } from './balanca.model';

@Injectable({ providedIn: 'root' })
export class BalancaService {
  private readonly http = inject(HttpClient);
  private readonly baseUrl = '/api/balancas';

  listar(): Observable<Balanca[]> {
    return this.http.get<Balanca[]>(this.baseUrl);
  }

  /** Balanças vinculadas ao usuário logado (fallback: todas as ativas, se sem vínculo). */
  listarMinhas(): Observable<Balanca[]> {
    return this.http.get<Balanca[]>(`${this.baseUrl}/minhas`);
  }

  criar(req: SalvarBalancaRequest): Observable<Balanca> {
    return this.http.post<Balanca>(this.baseUrl, req);
  }

  atualizar(id: string, req: SalvarBalancaRequest): Observable<unknown> {
    return this.http.patch(`${this.baseUrl}/${id}`, req);
  }

  remover(id: string): Observable<unknown> {
    return this.http.delete(`${this.baseUrl}/${id}`);
  }

  /** Só funciona para tipoComunicacao='HTTP' — as demais são lidas pelo LocalScaleService, no navegador. */
  capturarPeso(id: string): Observable<{ peso: number }> {
    return this.http.get<{ peso: number }>(`${this.baseUrl}/${id}/capturar-peso`);
  }
}
