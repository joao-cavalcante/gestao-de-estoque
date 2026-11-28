import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import { Palete } from '../paletes/paletes.service';

/** Fila de pesagem (V84, 27/11/2026): coletor manda o pesável; o computador da balança pesa e fecha o palete. */
export interface ItemFila {
  id: string;
  sessaoId: string;
  nunota: number;
  numNota: number | null;
  fornecedor: string | null;
  codprod: number;
  controle: string;
  descricao: string | null;
  caixas: number;
  unidadeComercial: string | null;
  conferente: string | null;
  criadoEm: string;
}

export interface Pesado {
  item: ItemFila;
  pesoLiquido: number;
  palete: Palete | null;
}

@Injectable({ providedIn: 'root' })
export class PesagemFilaService {
  private readonly http = inject(HttpClient);

  listar(): Observable<ItemFila[]> {
    return this.http.get<ItemFila[]>('/api/pesagem-fila');
  }

  enviar(req: {
    sessaoId: string;
    codprod: number;
    controle: string;
    caixas: number;
    unidadeComercial?: string | null;
    codvol?: string | null;
    codigoBarra?: string | null;
  }): Observable<ItemFila> {
    return this.http.post<ItemFila>('/api/pesagem-fila', req);
  }

  pesar(id: string, pesoBruto: number, tara: number, origemPeso: 'BALANCA' | 'MANUAL'): Observable<Pesado> {
    return this.http.post<Pesado>(`/api/pesagem-fila/${id}/pesar`, { pesoBruto, tara, origemPeso, fecharPalete: true });
  }

  cancelar(id: string): Observable<void> {
    return this.http.post<void>(`/api/pesagem-fila/${id}/cancelar`, {});
  }
}
