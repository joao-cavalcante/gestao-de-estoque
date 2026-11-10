import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import {
  AjustePesoCompra,
  ConfigConferenciaDetalhe,
  ConfigConferenciaListItem,
  OpcoesConferencia,
  SincronizarResponse,
  ToleranciaPeso,
} from './config-conferencia.model';

@Injectable({ providedIn: 'root' })
export class ConfigConferenciaService {
  private readonly http = inject(HttpClient);

  listar(): Observable<ConfigConferenciaListItem[]> {
    return this.http.get<ConfigConferenciaListItem[]>('/api/config-conferencia');
  }

  detalhe(nucco: number): Observable<ConfigConferenciaDetalhe> {
    return this.http.get<ConfigConferenciaDetalhe>(`/api/config-conferencia/${nucco}`);
  }

  sincronizar(): Observable<SincronizarResponse> {
    return this.http.post<SincronizarResponse>('/api/config-conferencia/sincronizar', {});
  }

  /** V50 — tolerância de peso do WMS pro NUCCO (null = sem limite; configurada=false = padrão). */
  opcoes(nucco: number): Observable<OpcoesConferencia> {
    return this.http.get<OpcoesConferencia>(`/api/config-conferencia/${nucco}/opcoes`);
  }

  salvarOpcoes(nucco: number, opcoes: Pick<OpcoesConferencia, 'pedirQtdComercial'>): Observable<OpcoesConferencia> {
    return this.http.put<OpcoesConferencia>(`/api/config-conferencia/${nucco}/opcoes`, opcoes);
  }

  toleranciaPeso(nucco: number): Observable<ToleranciaPeso> {
    return this.http.get<ToleranciaPeso>(`/api/config-conferencia/${nucco}/tolerancia-peso`);
  }

  salvarToleranciaPeso(nucco: number, acimaPct: number | null, abaixoPct: number | null): Observable<ToleranciaPeso> {
    return this.http.put<ToleranciaPeso>(`/api/config-conferencia/${nucco}/tolerancia-peso`, { acimaPct, abaixoPct });
  }

  /** Diferença de peso na compra — configuração do cliente (todas as conferências de compra). */
  ajustePesoCompra(): Observable<AjustePesoCompra> {
    return this.http.get<AjustePesoCompra>('/api/config-conferencia/ajuste-peso-compra');
  }

  salvarAjustePesoCompra(ajuste: AjustePesoCompra): Observable<AjustePesoCompra> {
    return this.http.put<AjustePesoCompra>('/api/config-conferencia/ajuste-peso-compra', ajuste);
  }

  /** Edição local — não escreve no Sankhya (ver ressalva no backend). */
  atualizar(nucco: number, campos: Record<string, string | null>): Observable<ConfigConferenciaDetalhe> {
    return this.http.patch<ConfigConferenciaDetalhe>(`/api/config-conferencia/${nucco}`, { campos });
  }
}
