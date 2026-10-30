import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import {
  CampoPersonalizado,
  Diagnostico,
  Instalacao,
  ParametrosTenant,
  RegistroAuditoria,
  Tenant,
  TenantMaster,
  UsuarioTenant,
} from './master.model';

export interface IntegracaoForm {
  baseUrl: string;
  gatewayPath: string | null;
  dialect: string | null;
  ativo: boolean;
  clientId?: string | null;
  clientSecret?: string | null;
  xToken?: string | null;
}

/**
 * API da plataforma. Tenant SEMPRE pelo id (resolvido no backend); as alterações críticas levam `confirmacao`
 * (o slug digitado pelo Master), conferida no servidor.
 */
@Injectable({ providedIn: 'root' })
export class MasterService {
  private readonly http = inject(HttpClient);
  private readonly base = '/api/master';

  instalacao(): Observable<Instalacao> {
    return this.http.get<Instalacao>(`${this.base}/instalacao`);
  }

  camposPersonalizados(): Observable<CampoPersonalizado[]> {
    return this.http.get<CampoPersonalizado[]>(`${this.base}/campos-personalizados`);
  }

  listar(): Observable<TenantMaster[]> {
    return this.http.get<TenantMaster[]>(`${this.base}/tenants`);
  }

  buscar(id: string): Observable<TenantMaster> {
    return this.http.get<TenantMaster>(`${this.base}/tenants/${id}`);
  }

  criar(slug: string, nome: string): Observable<Tenant> {
    return this.http.post<Tenant>(`${this.base}/tenants`, { slug, nome });
  }

  salvarDadosGerais(id: string, nome: string): Observable<Tenant> {
    return this.http.put<Tenant>(`${this.base}/tenants/${id}/dados-gerais`, { nome });
  }

  alterarStatus(id: string, status: string, motivo: string, confirmacao: string): Observable<Tenant> {
    return this.http.put<Tenant>(`${this.base}/tenants/${id}/status`, { status, motivo, confirmacao });
  }

  salvarIntegracao(id: string, form: IntegracaoForm, confirmacao: string): Observable<Tenant> {
    return this.http.put<Tenant>(`${this.base}/tenants/${id}/integracao`, { ...form, confirmacao });
  }

  testarIntegracao(id: string): Observable<{ autenticado: string; erro: string | null }> {
    return this.http.post<{ autenticado: string; erro: string | null }>(`${this.base}/tenants/${id}/integracao/testar`, {});
  }

  salvarModulos(id: string, modulos: string[], confirmacao: string): Observable<Tenant> {
    return this.http.put<Tenant>(`${this.base}/tenants/${id}/modulos`, { modulos, confirmacao });
  }

  salvarParametros(id: string, parametros: ParametrosTenant, confirmacao: string): Observable<ParametrosTenant> {
    return this.http.put<ParametrosTenant>(`${this.base}/tenants/${id}/parametros`, { parametros, confirmacao });
  }

  /** usuario/senha null = remover a credencial. */
  salvarLiberador(id: string, usuario: string | null, senha: string | null, confirmacao: string): Observable<{ configurado: string; codusu?: string }> {
    return this.http.put<{ configurado: string; codusu?: string }>(`${this.base}/tenants/${id}/liberador`, { usuario, senha, confirmacao });
  }

  usuarios(id: string): Observable<UsuarioTenant[]> {
    return this.http.get<UsuarioTenant[]>(`${this.base}/tenants/${id}/usuarios`);
  }

  diagnostico(id: string): Observable<Diagnostico> {
    return this.http.get<Diagnostico>(`${this.base}/tenants/${id}/diagnostico`);
  }

  auditoria(id: string): Observable<RegistroAuditoria[]> {
    return this.http.get<RegistroAuditoria[]>(`${this.base}/tenants/${id}/auditoria`);
  }
}
