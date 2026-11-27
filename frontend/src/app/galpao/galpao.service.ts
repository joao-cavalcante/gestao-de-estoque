import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import {
  DocumentoBusca,
  Alerta,
  AlertasPlanta,
  AnalisesPlanta,
  StatusAlerta,
  IndicadoresPlanta,
  Movimento,
  Conciliacao,
  Contagem,
  EstoqueProduto,
  MovimentoRequest,
  MovimentosPaginados,
  ProdutoBusca,
  ResultadoMovimento,
  GeometriaPlanta,
  Planta,
  PublicacaoOk,
  SalvarPlantaRequest,
  Validacao,
  Versao,
  VersaoResumo,
  VisaoPlanta,
} from './galpao.model';

/** JWT-auth (tenant vem do claim); o authInterceptor injeta o Bearer. Ver wms.backend.galpao.GalpaoRoutes. */
@Injectable({ providedIn: 'root' })
export class GalpaoService {
  private readonly http = inject(HttpClient);
  private readonly base = '/api/galpao';

  disponivel(): Observable<{ disponivel: boolean; podeEditar: boolean }> {
    return this.http.get<{ disponivel: boolean; podeEditar: boolean }>(`${this.base}/disponivel`);
  }

  plantas(): Observable<Planta[]> {
    return this.http.get<Planta[]>(`${this.base}/plantas`);
  }

  criarPlanta(req: SalvarPlantaRequest): Observable<Planta> {
    return this.http.post<Planta>(`${this.base}/plantas`, req);
  }

  atualizarPlanta(id: string, req: SalvarPlantaRequest): Observable<Planta> {
    return this.http.put<Planta>(`${this.base}/plantas/${id}`, req);
  }

  visao(id: string): Observable<VisaoPlanta> {
    return this.http.get<VisaoPlanta>(`${this.base}/plantas/${id}/visao`);
  }

  /** Análises dos produtos (fotografia de 10 min no backend; [atualizar] força nova leitura do Sankhya). */
  analises(id: string, atualizar = false): Observable<AnalisesPlanta> {
    return this.http.get<AnalisesPlanta>(`${this.base}/plantas/${id}/analises`, { params: atualizar ? { atualizar: 'true' } : {} });
  }

  alertas(id: string, atualizar = false): Observable<AlertasPlanta> {
    return this.http.get<AlertasPlanta>(`${this.base}/plantas/${id}/alertas`, { params: atualizar ? { atualizar: 'true' } : {} });
  }

  tratarAlerta(id: string, alertaId: string, req: { status: StatusAlerta; versao: number; atribuido?: string | null }): Observable<Alerta> {
    return this.http.post<Alerta>(`${this.base}/plantas/${id}/alertas/${alertaId}`, req);
  }

  indicadores(id: string): Observable<IndicadoresPlanta> {
    return this.http.get<IndicadoresPlanta>(`${this.base}/plantas/${id}/indicadores`);
  }

  feed(id: string): Observable<Movimento[]> {
    return this.http.get<Movimento[]>(`${this.base}/plantas/${id}/feed`);
  }

  documento(id: string, numero: number): Observable<DocumentoBusca> {
    return this.http.get<DocumentoBusca>(`${this.base}/plantas/${id}/documento/${numero}`);
  }

  versoes(id: string): Observable<VersaoResumo[]> {
    return this.http.get<VersaoResumo[]>(`${this.base}/plantas/${id}/versoes`);
  }

  /** null = planta ainda sem desenho (204). */
  desenho(id: string, versaoId?: string): Observable<Versao | null> {
    return this.http.get<Versao | null>(`${this.base}/plantas/${id}/desenho`, {
      params: versaoId ? { versao: versaoId } : {},
    });
  }

  validar(id: string, geometria: GeometriaPlanta): Observable<Validacao> {
    return this.http.post<Validacao>(`${this.base}/plantas/${id}/validar`, geometria);
  }

  salvarRascunho(id: string, geometria: GeometriaPlanta, observacao: string | null): Observable<VersaoResumo> {
    return this.http.post<VersaoResumo>(`${this.base}/plantas/${id}/rascunho`, { geometria, observacao });
  }

  restaurar(id: string, versaoId: string): Observable<VersaoResumo> {
    return this.http.post<VersaoResumo>(`${this.base}/plantas/${id}/versoes/${versaoId}/restaurar`, {});
  }

  /** Reenvia os endereços publicados ao Sankhya (addon TOR). */
  enviarSankhya(id: string): Observable<{ mensagem: string }> {
    return this.http.post<{ mensagem: string }>(`${this.base}/plantas/${id}/enviar-sankhya`, {});
  }

  publicar(id: string, versaoId: string, confirmacao: string): Observable<PublicacaoOk> {
    return this.http.post<PublicacaoOk>(`${this.base}/plantas/${id}/versoes/${versaoId}/publicar`, { confirmacao });
  }

  // ─── movimentação (fase 4, só ADMINISTRADOR) ──────────────────────────────

  movimentar(id: string, req: MovimentoRequest): Observable<ResultadoMovimento> {
    return this.http.post<ResultadoMovimento>(`${this.base}/plantas/${id}/movimentos`, req);
  }

  movimentos(id: string, filtros: { codprod?: number | null; endereco?: string; tipo?: string; pagina?: number }): Observable<MovimentosPaginados> {
    const params: Record<string, string> = {};
    if (filtros.codprod) params['codprod'] = String(filtros.codprod);
    if (filtros.endereco) params['endereco'] = filtros.endereco;
    if (filtros.tipo) params['tipo'] = filtros.tipo;
    if (filtros.pagina) params['pagina'] = String(filtros.pagina);
    return this.http.get<MovimentosPaginados>(`${this.base}/plantas/${id}/movimentos`, { params });
  }

  contagens(id: string, status?: string): Observable<Contagem[]> {
    return this.http.get<Contagem[]>(`${this.base}/plantas/${id}/contagens`, { params: status ? { status } : {} });
  }

  aprovarContagem(id: string, contagemId: string, observacao: string | null): Observable<ResultadoMovimento> {
    return this.http.post<ResultadoMovimento>(`${this.base}/plantas/${id}/contagens/${contagemId}/aprovar`, { observacao });
  }

  descartarContagem(id: string, contagemId: string, observacao: string | null): Observable<ResultadoMovimento> {
    return this.http.post<ResultadoMovimento>(`${this.base}/plantas/${id}/contagens/${contagemId}/descartar`, { observacao });
  }

  buscarProdutos(id: string, busca: string): Observable<ProdutoBusca[]> {
    return this.http.get<ProdutoBusca[]>(`${this.base}/plantas/${id}/produtos`, { params: { busca } });
  }

  estoqueProduto(id: string, codprod: number): Observable<EstoqueProduto> {
    return this.http.get<EstoqueProduto>(`${this.base}/plantas/${id}/estoque-produto/${codprod}`);
  }

  conciliacao(id: string): Observable<Conciliacao> {
    return this.http.get<Conciliacao>(`${this.base}/plantas/${id}/conciliacao`);
  }
}
