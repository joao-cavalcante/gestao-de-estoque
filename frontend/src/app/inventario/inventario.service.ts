import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import {
  AprovarResponse,
  EscopoTipo,
  FiltrosInventario,
  InventarioCriado,
  InventarioDetalhe,
  InventariosPaginados,
  ItemInventario,
  StatusInventario,
} from './inventario.model';

/** Contrato HTTP real, desde o início — sem motivo pra mockar de novo (o backend já nasce funcional). */
@Injectable({ providedIn: 'root' })
export class InventarioService {
  private readonly http = inject(HttpClient);

  abrir(descricao: string, escopoTipo: EscopoTipo, escopoValores: string[]): Observable<InventarioCriado> {
    return this.http.post<InventarioCriado>('/api/inventarios', { descricao, escopoTipo, escopoValores });
  }

  listar(filtros: FiltrosInventario = {}): Observable<InventariosPaginados> {
    const params: Record<string, string> = {};
    if (filtros.status) params['status'] = filtros.status;
    if (filtros.busca) params['busca'] = filtros.busca;
    params['pagina'] = String(filtros.pagina ?? 1);
    return this.http.get<InventariosPaginados>('/api/inventarios', { params });
  }

  detalhe(id: string): Observable<InventarioDetalhe> {
    return this.http.get<InventarioDetalhe>(`/api/inventarios/${id}`);
  }

  mudarStatus(id: string, status: StatusInventario): Observable<void> {
    return this.http.patch<void>(`/api/inventarios/${id}/status`, { status });
  }

  /** Erros (404 código não reconhecido / 400 quantidade / 409 encerrado) chegam como HttpErrorResponse. */
  registrarContagem(id: string, local: string, codigoLido: string, quantidade?: number): Observable<ItemInventario> {
    return this.http.post<ItemInventario>(`/api/inventarios/${id}/contagem`, {
      local,
      codigoLido,
      quantidade: quantidade != null ? String(quantidade) : undefined,
    });
  }

  divergencias(id: string): Observable<ItemInventario[]> {
    return this.http.get<ItemInventario[]>(`/api/inventarios/${id}/divergencias`);
  }

  /** Pode retornar 422 (modelo de nota ausente) ou 409 (itens pendentes) — quem chama trata via catchError. */
  aprovar(id: string): Observable<AprovarResponse> {
    return this.http.post<AprovarResponse>(`/api/inventarios/${id}/aprovar`, {});
  }

  cancelar(id: string): Observable<void> {
    return this.http.post<void>(`/api/inventarios/${id}/cancelar`, {});
  }
}
