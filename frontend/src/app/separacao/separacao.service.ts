import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import {
  CodigoBarra,
  ConcluirEtapaResultado,
  ConferenciasFinalizadasResposta,
  EtiquetaDados,
  FinalizarResultado,
  IdentificarProdutoResultado,
  IniciarSeparacaoResposta,
  ItemConferido,
  ItemResolvido,
  ItemSeparacao,
  SessaoEtapa,
  SessaoSeparacao,
  TopFaturamento,
  Uma,
  Volume,
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
  identificarProduto(
    tenant: string,
    sessaoId: string,
    codigoBarra: string,
    codprod?: number,
    etapa?: number,
  ): Observable<IdentificarProdutoResultado> {
    const body: Record<string, unknown> = { codigoBarra };
    if (codprod != null) body['codprod'] = codprod;
    if (etapa != null) body['etapa'] = etapa;
    return this.http.post<IdentificarProdutoResultado>(
      `${this.baseUrl}/sessoes/${sessaoId}/identificar`,
      body,
      { params: { tenant } },
    );
  }

  /** Etapas da conferência segmentada (V29). Vazio quando a sessão não é segmentada. */
  buscarEtapas(tenant: string, sessaoId: string): Observable<SessaoEtapa[]> {
    return this.http.get<SessaoEtapa[]>(`${this.baseUrl}/sessoes/${sessaoId}/etapas`, { params: { tenant } });
  }

  /** Conclui uma etapa; a última dispara o finalizar real no Sankhya. */
  concluirEtapa(
    tenant: string,
    sessaoId: string,
    body: { tipoSeparacao: number; manterPendente: boolean; operador: string },
  ): Observable<ConcluirEtapaResultado> {
    return this.http.post<ConcluirEtapaResultado>(
      `${this.baseUrl}/sessoes/${sessaoId}/concluir-etapa`,
      body,
      { params: { tenant } },
    );
  }

  /** Passo 2 (final): produto+controle já resolvidos — só grava a quantidade (+ peso opcional, rotina de peso portada do projeto base). */
  conferir(
    tenant: string,
    sessaoId: string,
    codprod: number,
    controle: string,
    qtd: number,
    peso?: number,
    codvol?: string | null,
    codigoBarra?: string | null,
  ): Observable<ItemConferido> {
    return this.http.post<ItemConferido>(
      `${this.baseUrl}/sessoes/${sessaoId}/conferir`,
      {
        codprod,
        controle,
        qtd: String(qtd),
        peso: peso != null ? String(peso) : null,
        codvol: codvol ?? null,
        codigoBarra: codigoBarra ?? null,
      },
      { params: { tenant } },
    );
  }

  /** Imagem do produto — buscada à parte do /identificar pra não travar o primeiro Tab da bipagem. */
  buscarImagemProduto(tenant: string, codprod: number): Observable<{ imagemBase64: string | null }> {
    return this.http.get<{ imagemBase64: string | null }>(
      `${this.baseUrl}/produtos/${codprod}/imagem`,
      { params: { tenant } },
    );
  }

  /** UMAs dos produtos pesáveis da sessão. */
  buscarUma(tenant: string, sessaoId: string): Observable<Uma[]> {
    return this.http.get<Uma[]>(`${this.baseUrl}/sessoes/${sessaoId}/uma`, { params: { tenant } });
  }

  /** Desfaz tudo que foi conferido pra esse produto+controle — corrige bipe errado. */
  devolverItem(tenant: string, sessaoId: string, codprod: number, controle: string): Observable<unknown> {
    return this.http.post(
      `${this.baseUrl}/sessoes/${sessaoId}/devolver-item`,
      { codprod, controle },
      { params: { tenant } },
    );
  }

  /** Fecha a conferência DE VERDADE no Sankhya (corte de estoque + financeiro). */
  finalizar(tenant: string, sessaoId: string): Observable<FinalizarResultado> {
    return this.http.post<FinalizarResultado>(`${this.baseUrl}/sessoes/${sessaoId}/finalizar`, {}, { params: { tenant } });
  }

  /** TOPs de destino pro faturamento (só quando a CCO tem FATAOCONCLUIR='S'). */
  topsFaturamento(tenant: string, sessaoId: string): Observable<TopFaturamento[]> {
    return this.http.get<TopFaturamento[]>(`${this.baseUrl}/sessoes/${sessaoId}/tops-faturamento`, { params: { tenant } });
  }

  /** Fatura a nota da sessão na TOP escolhida. */
  faturar(tenant: string, sessaoId: string, codTipOper: number, serie?: string): Observable<{ ok: boolean }> {
    return this.http.post<{ ok: boolean }>(
      `${this.baseUrl}/sessoes/${sessaoId}/faturar`,
      { codTipOper, serie: serie ?? null },
      { params: { tenant } },
    );
  }

  /** Dados pra etiqueta de volume da sessão. */
  dadosEtiqueta(tenant: string, sessaoId: string): Observable<EtiquetaDados> {
    return this.http.get<EtiquetaDados>(`${this.baseUrl}/sessoes/${sessaoId}/etiquetas`, { params: { tenant } });
  }

  /** Dados pra etiqueta por número da nota (reimpressão fora da conferência). */
  dadosEtiquetaPorNota(tenant: string, nunota: number): Observable<EtiquetaDados> {
    return this.http.get<EtiquetaDados>(`${this.baseUrl}/etiquetas`, { params: { tenant, nunota: String(nunota) } });
  }

  /** Conferências finalizadas pelo WMS — pra tela de reimpressão de etiquetas. */
  listarConferenciasFinalizadas(
    tenant: string,
    filtros: { nunota?: number; numnota?: number; page?: number; perPage?: number },
  ): Observable<ConferenciasFinalizadasResposta> {
    const params: Record<string, string> = { tenant };
    if (filtros.nunota) params['nunota'] = String(filtros.nunota);
    if (filtros.numnota) params['numnota'] = String(filtros.numnota);
    params['page'] = String(filtros.page ?? 0);
    params['perPage'] = String(filtros.perPage ?? 15);
    return this.http.get<ConferenciasFinalizadasResposta>(`${this.baseUrl}/conferencias-finalizadas`, { params });
  }

  /** Desiste do pedido inteiro (não só devolve 1 item) — só local por enquanto, ver SeparacaoService.cancelar no backend. */
  cancelar(tenant: string, sessaoId: string): Observable<{ ok: boolean }> {
    return this.http.post<{ ok: boolean }>(`${this.baseUrl}/sessoes/${sessaoId}/cancelar`, {}, { params: { tenant } });
  }

  /** Recontagem — zera o que foi bipado e reabre a sessão do zero. */
  recontar(tenant: string, sessaoId: string): Observable<{ ok: boolean }> {
    return this.http.post<{ ok: boolean }>(`${this.baseUrl}/sessoes/${sessaoId}/recontar`, {}, { params: { tenant } });
  }

  buscarVolume(tenant: string, sessaoId: string, etapa?: number | null): Observable<Volume> {
    const params: Record<string, string> = { tenant };
    if (etapa != null) params['etapa'] = String(etapa);
    return this.http.get<Volume>(`${this.baseUrl}/sessoes/${sessaoId}/volume`, { params });
  }

  definirVolume(tenant: string, sessaoId: string, quantidade: number, etapa?: number | null): Observable<Volume> {
    const params: Record<string, string> = { tenant };
    if (etapa != null) params['etapa'] = String(etapa);
    return this.http.put<Volume>(`${this.baseUrl}/sessoes/${sessaoId}/volume`, { quantidade }, { params });
  }
}
