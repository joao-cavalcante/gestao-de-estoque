import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import { CriarUsuarioRequest, Usuario } from './usuario.model';

@Injectable({ providedIn: 'root' })
export class UsuarioService {
  private readonly http = inject(HttpClient);
  private readonly baseUrl = '/api/usuarios';

  listar(): Observable<Usuario[]> {
    return this.http.get<Usuario[]>(this.baseUrl);
  }

  criar(req: CriarUsuarioRequest): Observable<Usuario> {
    return this.http.post<Usuario>(this.baseUrl, req);
  }

  atualizar(id: string, req: Partial<Pick<Usuario, 'nome' | 'perfil' | 'ativo' | 'turno' | 'codusuSankhya'>> & { alterarCodusuSankhya?: boolean }): Observable<unknown> {
    return this.http.patch(`${this.baseUrl}/${id}`, req);
  }

  /** Sem senhaAtual: admin alterando a senha de outro usuário (a rota já exige perfil ADMINISTRADOR nesse caso). */
  alterarSenha(id: string, senhaNova: string, senhaAtual?: string): Observable<unknown> {
    return this.http.post(`${this.baseUrl}/${id}/alterar-senha`, { senhaAtual, senhaNova });
  }

  /** null remove o crachá. Único por tenant — 409 se outro usuário já usa o código. */
  definirCracha(id: string, crachaoCodigo: string | null): Observable<unknown> {
    return this.http.post(`${this.baseUrl}/${id}/crachao`, { crachaoCodigo });
  }

  remover(id: string): Observable<{ desativado?: string; mensagem?: string } | null> {
    return this.http.delete<{ desativado?: string; mensagem?: string } | null>(`${this.baseUrl}/${id}`);
  }

  /** Cria o usuário no Sankhya (TSIUSU, modelo do grupo 32) e grava o CODUSU no vínculo. */
  criarNoSankhya(id: string): Observable<{ codusu: string; nomeUsu: string; mensagem: string }> {
    return this.http.post<{ codusu: string; nomeUsu: string; mensagem: string }>(`${this.baseUrl}/${id}/criar-no-sankhya`, {});
  }
}
