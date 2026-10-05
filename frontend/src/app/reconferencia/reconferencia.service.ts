import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';

/** Item conferido de uma sessão (GET /api/reconferencia/{sessaoId}) — quantidades na unidade base. */
export interface ReconferenciaItem {
  codprod: number;
  controle: string;
  descricao: string;
  /** 1 Secos | 2 Refrigerado | 3 Congelado | 0 sem etapa. */
  tipoSeparacao: number;
  unidade: string | null;
  qtdPedido: string;
  qtdConferida: string;
  /** Unidade do pedido (ex.: CX) e a qtd do pedido nela. */
  unidadeComercial?: string | null;
  qtdPedidoComercial?: string | null;
  /** Qtd conferida na unidade do pedido (não pesável aparece nela). */
  qtdConferidaComercial?: string | null;
  /** Observação "CX com 12 BI"; null = sem conversão. */
  conversao?: string | null;
  pesavel: boolean;
  /** Item do pedido com NADA conferido — alerta no checklist, sem check. */
  naoConferido?: boolean;
  checado: boolean;
  checadoPor?: string | null;
  checadoEm?: string | null;
}

export interface ReconferenciaDetalhe {
  sessaoId: string;
  nunota: number;
  numNota: number | null;
  cliente: string | null;
  finalizada: boolean;
  tolPesoAbaixoPct: number | null;
  tolPesoAcimaPct: number | null;
  itens: ReconferenciaItem[];
}

export interface ReconferenciaResumo {
  sessaoId: string;
  nunota: number;
  numNota: number | null;
  cliente: string | null;
  ordemCarga: number | null;
  dataMovimento: string | null;
  finalizadaEm: string;
  express: boolean;
  retira: boolean;
  entrega: boolean;
  totalItens: number;
  checados: number;
}

/** Reconferência: check manual do que foi conferido (só registro — não altera a conferência). */
@Injectable({ providedIn: 'root' })
export class ReconferenciaService {
  private readonly http = inject(HttpClient);

  listar(dias = 7): Observable<ReconferenciaResumo[]> {
    return this.http.get<ReconferenciaResumo[]>('/api/reconferencia', { params: { dias } });
  }

  detalhe(sessaoId: string): Observable<ReconferenciaDetalhe> {
    return this.http.get<ReconferenciaDetalhe>(`/api/reconferencia/${sessaoId}`);
  }

  /** "✓ Carregado" de um ou vários pedidos (um toque, sem checklist). */
  carregarPedidos(nunotas: (number | string)[]): Observable<unknown> {
    return this.http.post('/api/reconferencia/carregar', { nunotas: nunotas.map(Number) });
  }

  /** "Marcar todos" do checklist de carregamento. */
  marcarTodos(sessaoId: string, checado: boolean): Observable<unknown> {
    return this.http.put(`/api/reconferencia/${sessaoId}/check-todos`, { checado });
  }

  marcar(sessaoId: string, codprod: number, controle: string, checado: boolean): Observable<unknown> {
    return this.http.put(`/api/reconferencia/${sessaoId}/check`, { codprod, controle, checado });
  }
}
