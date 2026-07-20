import { HttpClient, HttpErrorResponse } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable, of } from 'rxjs';
import { catchError, map } from 'rxjs/operators';
import {
  FiltrosTransferencia,
  ItemTransferencia,
  ModeloNota,
  ResultadoIdentificacaoItem,
  ResultadoValidacaoLocal,
  TransferenciaCriada,
  TransferenciasPaginadas,
} from './transferencia.model';

const TIPO_TRANSFERENCIA_LOCAIS = 'transferencia_locais';

/** Contrato HTTP real, compartilhado entre o coletor e o desktop — só muda `canalOrigem` em `criar()`. */
@Injectable({ providedIn: 'root' })
export class TransferenciaService {
  private readonly http = inject(HttpClient);

  /** null = 404 (não configurado) — a tela chamadora decide como bloquear. */
  buscarModeloNota(): Observable<ModeloNota | null> {
    return this.http.get<ModeloNota>('/api/modelos-nota', { params: { tipo: TIPO_TRANSFERENCIA_LOCAIS } }).pipe(
      catchError((err: HttpErrorResponse) => (err.status === 404 ? of(null) : throwAsIs(err))),
    );
  }

  validarLocal(codigoBruto: string, origemParaComparar: string | null): Observable<ResultadoValidacaoLocal> {
    const codigo = codigoBruto.trim().toUpperCase();
    if (!codigo) {
      return of({ ok: false, codigo, erro: 'Informe um local' });
    }
    if (origemParaComparar && codigo === origemParaComparar) {
      return of({ ok: false, codigo, erro: 'Destino não pode ser igual à origem' });
    }

    return this.http.get<{ codigo: string; ativo: boolean }>(`/api/locais/${encodeURIComponent(codigo)}`).pipe(
      map((res) => (res.ativo ? { ok: true, codigo: res.codigo } : { ok: false, codigo, erro: 'Local inativo' })),
      catchError((err: HttpErrorResponse) =>
        of({ ok: false, codigo, erro: err.error?.erro ?? 'Local não encontrado' }),
      ),
    );
  }

  /** Só existência/modo do produto — saldo na origem é validado de verdade em `adicionarItem` (que sabe a origem real da transferência). */
  identificarItem(codigoBruto: string): Observable<ResultadoIdentificacaoItem> {
    const codigo = codigoBruto.trim();
    return this.http.get(`/api/produtos/${encodeURIComponent(codigo)}`).pipe(
      map((produto) => ({ ok: true, produto }) as ResultadoIdentificacaoItem),
      catchError((err: HttpErrorResponse) =>
        of({ ok: false, erro: err.error?.erro ?? 'Código não reconhecido' } as ResultadoIdentificacaoItem),
      ),
    );
  }

  criarTransferencia(origem: string, destino: string, canalOrigem: 'coletor' | 'desktop'): Observable<TransferenciaCriada> {
    return this.http.post<TransferenciaCriada>('/api/transferencias', { origem, destino, canalOrigem });
  }

  /**
   * Erros (409 sem saldo / 404 não reconhecido / 400 "informe a quantidade — produto a granel")
   * chegam como HttpErrorResponse — quem chama trata via `catchError`. `quantidade` é opcional:
   * o backend resolve sozinho pra etiqueta (soma automático) e peça avulsa (+1); só é
   * obrigatória quando o produto é granel.
   */
  adicionarItem(transferenciaId: string, codigoLido: string, quantidade?: number): Observable<ItemTransferencia> {
    return this.http.post<ItemTransferencia>(`/api/transferencias/${transferenciaId}/itens`, {
      codigoLido,
      quantidade: quantidade != null ? String(quantidade) : undefined,
    });
  }

  removerItem(transferenciaId: string, itemId: string): Observable<void> {
    return this.http.delete<void>(`/api/transferencias/${transferenciaId}/itens/${itemId}`);
  }

  /** Pode retornar 422 (modelo de nota não configurado) — quem chama trata via `catchError`. */
  confirmarTransferencia(transferenciaId: string): Observable<void> {
    return this.http.post<void>(`/api/transferencias/${transferenciaId}/confirmar`, {});
  }

  listar(filtros: FiltrosTransferencia = {}): Observable<TransferenciasPaginadas> {
    const params: Record<string, string> = {};
    if (filtros.canal) params['canal'] = filtros.canal;
    if (filtros.status) params['status'] = filtros.status;
    if (filtros.busca) params['busca'] = filtros.busca;
    params['pagina'] = String(filtros.pagina ?? 1);
    return this.http.get<TransferenciasPaginadas>('/api/transferencias', { params });
  }
}

function throwAsIs(err: HttpErrorResponse): Observable<never> {
  throw err;
}
