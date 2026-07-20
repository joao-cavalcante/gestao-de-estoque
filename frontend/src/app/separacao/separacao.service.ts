import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import {
  CodigoBarra,
  IdentificarProdutoResultado,
  IniciarSeparacaoResposta,
  ItemConferido,
  ItemResolvido,
  ItemSeparacao,
  SessaoSeparacao,
} from './separacao.model';

/**
 * Espelha SeparacaoRoutes.kt: iniciar responde na hora (sessão criada
 * localmente), o carregamento real dos itens do Sankhya acontece em
 * background — o front dá polling em `buscarSessao` até status='pronta'.
 */
@Injectable({ providedIn: 'root' })
export class SeparacaoService {
  private readonly http = inject(HttpClient);
  private readonly baseUrl = '/api/separacao';

  iniciar(tenant: string, nunota: number): Observable<IniciarSeparacaoResposta> {
    return this.http.post<IniciarSeparacaoResposta>(`${this.baseUrl}/iniciar`, { nunota }, { params: { tenant } });
  }

  buscarSessao(tenant: string, sessaoId: string): Observable<SessaoSeparacao> {
    return this.http.get<SessaoSeparacao>(`${this.baseUrl}/sessoes/${sessaoId}`, { params: { tenant } });
  }

  buscarItens(tenant: string, sessaoId: string): Observable<ItemSeparacao[]> {
    return this.http.get<ItemSeparacao[]>(`${this.baseUrl}/sessoes/${sessaoId}/itens`, { params: { tenant } });
  }

  buscarCodigosBarra(tenant: string, sessaoId: string): Observable<CodigoBarra[]> {
    return this.http.get<CodigoBarra[]>(`${this.baseUrl}/sessoes/${sessaoId}/codigos-barra`, { params: { tenant } });
  }

  /** Resolução de verdade (5 modos do Sankhya) — roda no backend, não mais no front. */
  resolverCodigoBarras(tenant: string, sessaoId: string, codigoBarra: string): Observable<ItemResolvido> {
    return this.http.post<ItemResolvido>(
      `${this.baseUrl}/sessoes/${sessaoId}/resolver-codigo-barras`,
      { codigoBarra },
      { params: { tenant } },
    );
  }

  /** Passo 1 do fluxo por Tab: identifica o produto e como o campo de controle deve se comportar. */
  identificarProduto(tenant: string, sessaoId: string, codigoBarra: string): Observable<IdentificarProdutoResultado> {
    return this.http.post<IdentificarProdutoResultado>(
      `${this.baseUrl}/sessoes/${sessaoId}/identificar`,
      { codigoBarra },
      { params: { tenant } },
    );
  }

  /** Passo 2 (final): produto+controle já resolvidos — só grava a quantidade. */
  conferir(tenant: string, sessaoId: string, codprod: number, controle: string, qtd: number): Observable<ItemConferido> {
    return this.http.post<ItemConferido>(
      `${this.baseUrl}/sessoes/${sessaoId}/conferir`,
      { codprod, controle, qtd: String(qtd) },
      { params: { tenant } },
    );
  }

  /** Desfaz tudo que foi conferido pra esse produto+controle — corrige bipe errado. */
  devolverItem(tenant: string, sessaoId: string, codprod: number, controle: string): Observable<unknown> {
    return this.http.post(
      `${this.baseUrl}/sessoes/${sessaoId}/devolver-item`,
      { codprod, controle },
      { params: { tenant } },
    );
  }
}
