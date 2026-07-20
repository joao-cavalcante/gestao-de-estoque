import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import { AtualizarTenantRequest, CriarTenantRequest, ErpConnectionInput, Tenant } from './tenant.model';

@Injectable({ providedIn: 'root' })
export class TenantService {
  private readonly http = inject(HttpClient);
  private readonly baseUrl = '/api/tenants';

  listar(): Observable<Tenant[]> {
    return this.http.get<Tenant[]>(this.baseUrl);
  }

  buscarPorSlug(slug: string): Observable<Tenant> {
    return this.http.get<Tenant>(`${this.baseUrl}/${slug}`);
  }

  criar(req: CriarTenantRequest): Observable<Tenant> {
    return this.http.post<Tenant>(this.baseUrl, req);
  }

  atualizar(slug: string, req: AtualizarTenantRequest): Observable<Tenant> {
    return this.http.patch<Tenant>(`${this.baseUrl}/${slug}`, req);
  }

  adicionarErpConnection(slug: string, conn: ErpConnectionInput): Observable<Tenant> {
    return this.http.post<Tenant>(`${this.baseUrl}/${slug}/erp-connections`, conn);
  }

  remover(slug: string): Observable<void> {
    return this.http.delete<void>(`${this.baseUrl}/${slug}`);
  }
}
