import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import { ConsultaProdutosResposta } from './consulta-produtos.model';

/** JWT-auth (tenant vem do claim); o authInterceptor injeta o Bearer. */
@Injectable({ providedIn: 'root' })
export class ConsultaProdutosService {
  private readonly http = inject(HttpClient);

  /** Catálogo inteiro com saldo. [atualizar] = ignora a leitura de estoque guardada no servidor. */
  listar(atualizar = false): Observable<ConsultaProdutosResposta> {
    return this.http.get<ConsultaProdutosResposta>('/api/consulta-produtos', {
      params: atualizar ? { atualizar: 'true' } : {},
    });
  }
}
