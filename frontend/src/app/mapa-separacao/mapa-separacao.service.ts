import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import { MapaSeparacaoDto, OrdemCargaResumoDto, PedidoSemOrdemCargaDto, PedidoVenda } from './mapa-separacao.model';

@Injectable({ providedIn: 'root' })
export class MapaSeparacaoService {
  private readonly http = inject(HttpClient);
  private readonly baseUrl = '/api/mapa-separacao';

  /** Pedido de Venda de cada Nº Único (impresso junto do mapa). */
  pedidosVenda(nunotas: number[]): Observable<PedidoVenda[]> {
    return this.http.get<PedidoVenda[]>(`${this.baseUrl}/pedidos-venda`, { params: { nunotas: nunotas.join(',') } });
  }

  consultar(ordemCarga: number): Observable<MapaSeparacaoDto> {
    return this.http.get<MapaSeparacaoDto>(`${this.baseUrl}/${ordemCarga}`);
  }

  /** OCs do painel: abertas + fechadas que ainda têm nota na fila de conferência. */
  listarAbertas(): Observable<OrdemCargaResumoDto[]> {
    return this.http.get<OrdemCargaResumoDto[]>(`${this.baseUrl}/abertas`);
  }

  /** Pedidos da fila de conferência SEM Ordem de Carga — painel do filtro "S/ Ordem de Carga". */
  listarSemOrdemCarga(): Observable<PedidoSemOrdemCargaDto[]> {
    return this.http.get<PedidoSemOrdemCargaDto[]>(`${this.baseUrl}/sem-ordem-carga`);
  }

  /** Mapa de UM pedido sem Ordem de Carga (um mapa por Número Único). */
  consultarSemOrdemCarga(nunota: number): Observable<MapaSeparacaoDto> {
    return this.http.get<MapaSeparacaoDto>(`${this.baseUrl}/sem-ordem-carga/${nunota}`);
  }

  /** Registra a impressão (selo IMPRESSO e filtro "Não impressos"). */
  registrarImpressao(ordensCarga: number[], nunotas: number[]): Observable<unknown> {
    return this.http.post(`${this.baseUrl}/impressoes`, { ordensCarga, nunotas });
  }
}
