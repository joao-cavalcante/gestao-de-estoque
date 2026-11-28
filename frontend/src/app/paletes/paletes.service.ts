import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';

/** Paletes do recebimento (endereçamento etapa 4a, 27/11/2026) — LPN montado na conferência da compra. */
export interface PaleteItem {
  codprod: number;
  controle: string;
  descricao: string | null;
  qtd: number;
  unidade: string | null;
  qtdComercial: number | null;
  unidadeComercial: string | null;
  /** Tempo 2 da doca (V82): null = lote pendente. */
  lote?: string | null;
  validade?: string | null;
  fabricacao?: string | null;
  origemLote?: string | null;
  qtdAvaria?: number | null;
  motivoAvaria?: string | null;
  amostraUnidades?: number | null;
  amostraEsperado?: number | null;
}

export interface Palete {
  id: string;
  nunota: number;
  numero: number;
  /** Código da etiqueta (UMA): "P" + 7 dígitos. */
  uma: string;
  seq: number;
  tipo: 'NORMAL' | 'MISTO';
  impresso: boolean;
  criadoPor: string | null;
  criadoEm: string;
  cancelado: boolean;
  itens: PaleteItem[];
  temperatura?: number | null;
}

export interface ResumoPaletes {
  permitirMisto: boolean;
  /** Lido e ainda sem palete, por produto. */
  pendentes: PaleteItem[];
  paletes: Palete[];
}

@Injectable({ providedIn: 'root' })
export class PaletesService {
  private readonly http = inject(HttpClient);
  private readonly base = '/api/paletes';

  resumo(sessaoId: string): Observable<ResumoPaletes> {
    return this.http.get<ResumoPaletes>(`${this.base}/sessao/${sessaoId}`);
  }

  /** itens vazio = tudo o que está sem palete; umPorProduto = um palete por produto (finalizar sem palete misto). */
  fechar(sessaoId: string, itens: { codprod: number; controle: string }[] = [], umPorProduto = false): Observable<Palete[]> {
    return this.http.post<Palete[]>(`${this.base}/sessao/${sessaoId}/fechar`, { itens, umPorProduto });
  }

  porIds(ids: string[]): Observable<Palete[]> {
    return this.http.get<Palete[]>(this.base, { params: { ids: ids.join(',') } });
  }

  porNunota(nunota: number): Observable<Palete[]> {
    return this.http.get<Palete[]>(`${this.base}/nunota/${nunota}`);
  }

  marcarImpresso(ids: string[]): Observable<void> {
    return this.http.post<void>(`${this.base}/impresso`, { ids });
  }
}

/** "30 FD" (unidade da compra) ou "180 GF" (padrão), sem casas desnecessárias. */
export function qtdPalete(i: PaleteItem): string {
  const f = (n: number) => n.toLocaleString('pt-BR', { maximumFractionDigits: 3 });
  return i.qtdComercial != null && i.unidadeComercial ? `${f(i.qtdComercial)} ${i.unidadeComercial}` : `${f(i.qtd)} ${i.unidade ?? ''}`.trim();
}
