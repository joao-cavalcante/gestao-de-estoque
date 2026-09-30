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

  /**
   * Balanças que o usuário pode usar (sem usuário vinculado = de todos). `sessaoId` = conferência aberta:
   * em conta de estação o backend considera o operador do crachá dessa sessão.
   */
  listarMinhas(sessaoId?: string | null): Observable<Balanca[]> {
    return this.http.get<Balanca[]>(`${this.baseUrl}/minhas`, { params: sessaoId ? { sessao: sessaoId } : {} });
  }

  /** Usuários autorizados da balança (admin) — lista vazia = sem restrição. */
  listarUsuarios(id: string): Observable<{ usuarioIds: string[] }> {
    return this.http.get<{ usuarioIds: string[] }>(`${this.baseUrl}/${id}/usuarios`);
  }

  definirUsuarios(id: string, usuarioIds: string[]): Observable<{ usuarioIds: string[] }> {
    return this.http.put<{ usuarioIds: string[] }>(`${this.baseUrl}/${id}/usuarios`, { usuarioIds });
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
  capturarPeso(id: string, sessaoId?: string | null): Observable<{ peso: number }> {
    return this.http.get<{ peso: number }>(`${this.baseUrl}/${id}/capturar-peso`, { params: sessaoId ? { sessao: sessaoId } : {} });
  }
}
