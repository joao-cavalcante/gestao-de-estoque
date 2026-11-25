import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import { AjustePeso, ItemMesa } from '../entrada/entrada.service';

export interface NotaDerivada {
  nunota: number;
  numero?: number | null;
  confirmada: boolean;
}

export interface AlertaAuditoria {
  /** ajuste | addon | devolucao | complemento | parada */
  tipo: string;
  texto: string;
}

export interface RecebimentoAuditoria {
  nunota: number;
  nuconf?: number | null;
  numeroNota?: number | null;
  fornecedor?: string | null;
  empresa?: string | null;
  tipoOperacao?: string | null;
  dataMovimento?: string | null;
  conferente?: string | null;
  conferenciaInicio?: string | null;
  conferenciaFim?: string | null;
  /** A aberta · L confirmada · X não existe mais no Sankhya · null ainda não lida */
  statusNota?: string | null;
  confirmadoPor?: string | null;
  /** T = Aprovar da Torre · S = direto no Sankhya */
  confirmadoOrigem?: string | null;
  confirmadoEm?: string | null;
  ajustes: AjustePeso[];
  devolucao?: NotaDerivada | null;
  complemento?: NotaDerivada | null;
  alertas: AlertaAuditoria[];
  encerrado: boolean;
  lidoEm?: string | null;
}

export interface AuditoriaDetalhe {
  recebimento: RecebimentoAuditoria;
  itens: ItemMesa[];
  recontagem: boolean;
}

/** Auditoria de Recebimento (25/11/2026) — GET /api/entrada/auditoria. */
@Injectable({ providedIn: 'root' })
export class AuditoriaRecebimentoService {
  private readonly http = inject(HttpClient);

  listar(): Observable<RecebimentoAuditoria[]> {
    return this.http.get<RecebimentoAuditoria[]>('/api/entrada/auditoria');
  }

  detalhe(nunota: number): Observable<AuditoriaDetalhe> {
    return this.http.get<AuditoriaDetalhe>(`/api/entrada/auditoria/${nunota}`);
  }

  gerarAjuste(nunota: number): Observable<AuditoriaDetalhe> {
    return this.http.post<AuditoriaDetalhe>(`/api/entrada/auditoria/${nunota}/gerar-ajuste`, {});
  }
}
