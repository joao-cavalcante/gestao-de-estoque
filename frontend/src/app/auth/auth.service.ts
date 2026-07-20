import { HttpClient } from '@angular/common/http';
import { Injectable, inject, signal } from '@angular/core';
import { Router } from '@angular/router';
import { Observable, tap } from 'rxjs';
import { LoginResponse, Usuario } from './auth.model';

const CHAVE_TOKEN = 'wms_token';
const CHAVE_USUARIO = 'wms_usuario';

@Injectable({ providedIn: 'root' })
export class AuthService {
  private readonly http = inject(HttpClient);
  private readonly router = inject(Router);

  readonly usuario = signal<Usuario | null>(lerUsuarioSalvo());

  login(email: string, senha: string): Observable<LoginResponse> {
    return this.http.post<LoginResponse>('/api/auth/login', { email, senha }).pipe(
      tap((res) => {
        localStorage.setItem(CHAVE_TOKEN, res.token);
        localStorage.setItem(CHAVE_USUARIO, JSON.stringify(res.usuario));
        this.usuario.set(res.usuario);
      }),
    );
  }

  logout(): void {
    localStorage.removeItem(CHAVE_TOKEN);
    localStorage.removeItem(CHAVE_USUARIO);
    this.usuario.set(null);
    this.router.navigate(['/login']);
  }

  obterToken(): string | null {
    return localStorage.getItem(CHAVE_TOKEN);
  }

  estaLogado(): boolean {
    return this.obterToken() !== null;
  }
}

function lerUsuarioSalvo(): Usuario | null {
  const bruto = localStorage.getItem(CHAVE_USUARIO);
  return bruto ? JSON.parse(bruto) : null;
}
