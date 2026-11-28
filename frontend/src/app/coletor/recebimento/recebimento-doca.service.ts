import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import { Palete } from '../../paletes/paletes.service';

/** Recebimento na doca — etapa D1 (27/11/2026). Desenho: sankhya-hub docs/projetos/torre-recebimento-doca.md. */
export interface Chegada {
  chegadaEm: string;
  placa: string | null;
  lacre: 'OK' | 'VIOLADO' | 'SEM_LACRE' | null;
  bau: 'OK' | 'SUJO' | 'AVARIADO' | null;
  temperaturaBau: number | null;
  observacao: string | null;
  registradoPor: string | null;
  liberadoEm: string | null;
  liberadoPor: string | null;
}

export interface LoteXml {
  lote: string;
  qtd: number | null;
  fabricacao: string | null;
  validade: string | null;
}

export interface ItemXml {
  nItem: number;
  codprod: number | null;
  cProd: string | null;
  descricao: string | null;
  lotes: LoteXml[];
}

export interface NotaXml {
  encontrado: boolean;
  volumes?: number | null;
  especie?: string | null;
  pesoBruto?: number | null;
  pesoLiquido?: number | null;
  placa?: string | null;
  itens: ItemXml[];
}

export interface Doca {
  nunota: number;
  chegada: Chegada | null;
  nota: NotaXml;
  paletes: Palete[];
  volumesFechados: number;
  paletesLotePendente: number;
}

export interface LinhaLote {
  lote: string;
  validade?: string | null;
  fabricacao?: string | null;
  origem: 'GS1' | 'XML' | 'DIGITADO' | 'AUTOMATICO' | 'ANTERIOR';
  qtd?: number | null;
}

@Injectable({ providedIn: 'root' })
export class RecebimentoDocaService {
  private readonly http = inject(HttpClient);

  doca(nunota: number): Observable<Doca> {
    return this.http.get<Doca>(`/api/recebimento/doca/${nunota}`);
  }

  registrarChegada(nunota: number, c: Partial<Chegada>): Observable<Chegada> {
    return this.http.put<Chegada>(`/api/recebimento/doca/${nunota}/chegada`, c);
  }

  liberarMotorista(nunota: number): Observable<Chegada> {
    return this.http.post<Chegada>(`/api/recebimento/doca/${nunota}/liberar-motorista`, {});
  }

  definirLotes(paleteId: string, codprod: number, linhas: LinhaLote[], temperatura: number | null = null): Observable<Palete> {
    return this.http.put<Palete>(`/api/paletes/${paleteId}/lotes`, { itens: [{ codprod, linhas }], temperatura });
  }

  definirAvaria(paleteId: string, codprod: number, qtd: number, motivo: string | null): Observable<Palete> {
    return this.http.put<Palete>(`/api/paletes/${paleteId}/avaria`, { codprod, qtd, motivo });
  }
}

/** Lote automático ("Sem lote na caixa"): E + data da entrada (AAMMDD). Mesmo dia = mesmo lote. */
export function loteAutomatico(hoje = new Date()): string {
  const p = (n: number) => String(n).padStart(2, '0');
  return `E${String(hoje.getFullYear()).slice(2)}${p(hoje.getMonth() + 1)}${p(hoje.getDate())}`;
}
