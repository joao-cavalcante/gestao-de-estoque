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

  atualizar(id: string, req: Partial<Pick<Usuario, 'nome' | 'perfil' | 'ativo'>>): Observable<unknown> {
    return this.http.patch(`${this.baseUrl}/${id}`, req);
  }

  /** Sem senhaAtual: admin alterando a senha de outro usuário (a rota já exige perfil ADMINISTRADOR nesse caso). */
  alterarSenha(id: string, senhaNova: string, senhaAtual?: string): Observable<unknown> {
    return this.http.post(`${this.baseUrl}/${id}/alterar-senha`, { senhaAtual, senhaNova });
  }

  remover(id: string): Observable<unknown> {
    return this.http.delete(`${this.baseUrl}/${id}`);
  }
}
