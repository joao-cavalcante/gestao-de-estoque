import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import { TarefaArmazenagem } from './armazenar.logic';

/** Tarefas de armazenagem (addon TOR, via backend da Torre — /api/armazenagem). Etapa 3b, 27/11/2026. */
@Injectable({ providedIn: 'root' })
export class ArmazenarService {
  private readonly http = inject(HttpClient);
  private readonly base = '/api/armazenagem/tarefas';

  listar(): Observable<TarefaArmazenagem[]> {
    return this.http.get<TarefaArmazenagem[]>(this.base);
  }

  iniciar(nutar: number): Observable<TarefaArmazenagem> {
    return this.http.post<TarefaArmazenagem>(`${this.base}/${nutar}/iniciar`, {});
  }

  armazenar(nutar: number, endereco: string, qtd: number, motivo: string | null): Observable<TarefaArmazenagem> {
    return this.http.post<TarefaArmazenagem>(`${this.base}/${nutar}/armazenar`, { endereco, qtd, motivo });
  }
}
