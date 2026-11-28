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

export interface AlertaPedido {
  codprod: number;
  descricao: string | null;
  tipo: 'SEM_PEDIDO' | 'QTD_ACIMA' | 'PRECO_ACIMA' | 'PRECO_ABAIXO';
  nota: number | null;
  pedido: number | null;
}

export interface PedidoNota {
  itensNota: number;
  itensComPedido: number;
  alertas: AlertaPedido[];
}

export interface Logistica {
  codprod: number;
  descricao: string | null;
  lastro: number | null;
  camadas: number | null;
  origem: 'SANKHYA' | 'TORRE' | null;
}

export interface Foto {
  id: string;
  paleteId: string | null;
  codprod: number | null;
  tipo: string;
  criadoEm: string;
}

export interface EstimativaCaixas {
  codprod: number;
  descricao: string | null;
  kgNota: number;
  unidadeCaixa: string;
  kgPorCaixa: number;
  caixasEstimadas: number;
  caixasContadas: number;
  kgLido: number;
  alerta: boolean;
}

export interface ItemRecusa {
  codprod: number;
  qtd: number;
  unidade?: string | null;
}

export interface Decisao {
  recusaTipo?: 'TOTAL' | 'PARCIAL' | null;
  recusaMotivo?: string | null;
  recusaItens: ItemRecusa[];
  recusaEm?: string | null;
  recusaPor?: string | null;
  mdeEvento?: string | null;
  mdeEm?: string | null;
  mdeSituacao?: 'OK' | 'ERRO' | null;
  mdeMensagem?: string | null;
  recusaHabilitada: boolean;
  mdeHabilitada: boolean;
}

export interface Doca {
  nunota: number;
  chegada: Chegada | null;
  nota: NotaXml;
  paletes: Palete[];
  volumesFechados: number;
  paletesLotePendente: number;
  pedido?: PedidoNota | null;
  logistica?: Logistica[];
  fotos?: Foto[];
  caixasEstimadas?: EstimativaCaixas[];
  fonte?: 'ADDON' | 'DBEXPLORER' | null;
  decisao?: Decisao | null;
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

  registrarAmostra(paleteId: string, codprod: number, unidades: number): Observable<Palete> {
    return this.http.put<Palete>(`/api/paletes/${paleteId}/amostra`, { codprod, unidades });
  }

  registrarLogistica(codprod: number, lastro: number, camadas: number): Observable<void> {
    return this.http.put<void>(`/api/recebimento/logistica/${codprod}`, { lastro, camadas });
  }

  enviarFoto(nunota: number, paleteId: string, codprod: number, dataUrl: string, tipo = 'AVARIA'): Observable<Foto> {
    return this.http.post<Foto>(`/api/recebimento/doca/${nunota}/fotos`, { tipo, paleteId, codprod, dataUrl });
  }

  recusar(nunota: number, tipo: 'TOTAL' | 'PARCIAL', motivo: string, itens: ItemRecusa[]): Observable<Decisao> {
    return this.http.post<Decisao>(`/api/recebimento/doca/${nunota}/recusa`, { tipo, motivo, itens });
  }

  definirAvaria(paleteId: string, codprod: number, qtd: number, motivo: string | null): Observable<Palete> {
    return this.http.put<Palete>(`/api/paletes/${paleteId}/avaria`, { codprod, qtd, motivo });
  }
}

/** Reduz a foto da câmera para JPEG de até [max] px (o coletor manda ~100 KB, não 4 MB). */
export function reduzirFoto(arquivo: File, max = 1280, qualidade = 0.72): Promise<string> {
  return new Promise((resolve, reject) => {
    const url = URL.createObjectURL(arquivo);
    const img = new Image();
    img.onload = () => {
      const k = Math.min(1, max / Math.max(img.width, img.height));
      const c = document.createElement('canvas');
      c.width = Math.round(img.width * k);
      c.height = Math.round(img.height * k);
      c.getContext('2d')?.drawImage(img, 0, 0, c.width, c.height);
      URL.revokeObjectURL(url);
      resolve(c.toDataURL('image/jpeg', qualidade));
    };
    img.onerror = () => {
      URL.revokeObjectURL(url);
      reject(new Error('Não abriu a foto.'));
    };
    img.src = url;
  });
}

/** Lote automático ("Sem lote na caixa"): E + data da entrada (AAMMDD). Mesmo dia = mesmo lote. */
export function loteAutomatico(hoje = new Date()): string {
  const p = (n: number) => String(n).padStart(2, '0');
  return `E${String(hoje.getFullYear()).slice(2)}${p(hoje.getMonth() + 1)}${p(hoje.getDate())}`;
}
