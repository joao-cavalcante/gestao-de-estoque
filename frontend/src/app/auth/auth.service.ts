import { HttpClient } from '@angular/common/http';
import { Injectable, inject, signal } from '@angular/core';
import { Router } from '@angular/router';
import { Observable, tap } from 'rxjs';
import { LoginResponse, Usuario } from './auth.model';

const CHAVE_TOKEN = 'wms_token';
const CHAVE_USUARIO = 'wms_usuario';
const CHAVE_TENANT = 'wms_tenant_slug';

@Injectable({ providedIn: 'root' })
export class AuthService {
  private readonly http = inject(HttpClient);
  private readonly router = inject(Router);

  readonly usuario = signal<Usuario | null>(lerUsuarioSalvo());
  readonly tenantSlug = signal<string | null>(localStorage.getItem(CHAVE_TENANT));

  login(email: string, senha: string): Observable<LoginResponse> {
    return this.http.post<LoginResponse>('/api/auth/login', { email, senha }).pipe(
      tap((res) => {
        localStorage.setItem(CHAVE_TOKEN, res.token);
        localStorage.setItem(CHAVE_USUARIO, JSON.stringify(res.usuario));
        localStorage.setItem(CHAVE_TENANT, res.tenantSlug);
        this.usuario.set(res.usuario);
        this.tenantSlug.set(res.tenantSlug);
      }),
    );
  }

  logout(): void {
    localStorage.removeItem(CHAVE_TOKEN);
    localStorage.removeItem(CHAVE_USUARIO);
    localStorage.removeItem(CHAVE_TENANT);
    this.usuario.set(null);
    this.tenantSlug.set(null);
    this.router.navigate(['/login']);
  }

  obterToken(): string | null {
    return localStorage.getItem(CHAVE_TOKEN);
  }

  obterTenantSlug(): string | null {
    return this.tenantSlug();
  }

  estaLogado(): boolean {
    return this.obterToken() !== null;
  }
}

function lerUsuarioSalvo(): Usuario | null {
  const bruto = localStorage.getItem(CHAVE_USUARIO);
  return bruto ? JSON.parse(bruto) : null;
}
