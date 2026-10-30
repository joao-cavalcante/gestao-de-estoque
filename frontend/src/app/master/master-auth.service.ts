import { HttpClient } from '@angular/common/http';
import { Injectable, inject, signal } from '@angular/core';
import { Router } from '@angular/router';
import { Observable, tap } from 'rxjs';
import { LoginMasterResponse, MasterUsuario } from './master.model';

const CHAVE_TOKEN = 'wms_master_token';
const CHAVE_USUARIO = 'wms_master_usuario';

/**
 * Sessão Master — guardada à parte da sessão do tenant (outras chaves): entrar no painel não derruba o login
 * operacional do mesmo navegador, e o token de um nunca vai nas chamadas do outro (ver authInterceptor).
 */
@Injectable({ providedIn: 'root' })
export class MasterAuthService {
  private readonly http = inject(HttpClient);
  private readonly router = inject(Router);

  readonly master = signal<MasterUsuario | null>(lerSalvo());

  login(email: string, senha: string): Observable<LoginMasterResponse> {
    return this.http.post<LoginMasterResponse>('/api/master/auth/login', { email, senha }).pipe(
      tap((res) => {
        localStorage.setItem(CHAVE_TOKEN, res.token);
        localStorage.setItem(CHAVE_USUARIO, JSON.stringify(res.master));
        this.master.set(res.master);
      }),
    );
  }

  sair(): void {
    localStorage.removeItem(CHAVE_TOKEN);
    localStorage.removeItem(CHAVE_USUARIO);
    this.master.set(null);
    this.router.navigate(['/master/login']);
  }

  obterToken(): string | null {
    return localStorage.getItem(CHAVE_TOKEN);
  }

  estaLogado(): boolean {
    return this.obterToken() !== null;
  }
}

function lerSalvo(): MasterUsuario | null {
  const bruto = localStorage.getItem(CHAVE_USUARIO);
  return bruto ? JSON.parse(bruto) : null;
}
