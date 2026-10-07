import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import { AuditoriaPedido } from './auditoria-pedido.model';

/** JWT-auth (tenant vem do claim); o authInterceptor injeta o Bearer. */
@Injectable({ providedIn: 'root' })
export class AuditoriaPedidoService {
  private readonly http = inject(HttpClient);

  /** [numero] = número único ou, se não achar, o número do pedido. */
  buscar(numero: number): Observable<AuditoriaPedido> {
    return this.http.get<AuditoriaPedido>(`/api/auditoria-pedido/${numero}`);
  }
}
