import { HttpErrorResponse, HttpInterceptorFn } from '@angular/common/http';
import { inject } from '@angular/core';
import { catchError, throwError } from 'rxjs';
import { AuthService } from './auth.service';

/**
 * Injeta Authorization: Bearer em toda chamada (rotas públicas ignoram o header
 * extra) e, em 401 de rota autenticada, faz logout + volta pro login — evita o
 * "token ausente ou inválido" cru quando o JWT expira (TTL de 8h).
 */
export const authInterceptor: HttpInterceptorFn = (req, next) => {
  const auth = inject(AuthService);
  const token = auth.obterToken();

  const requisicao = token ? req.clone({ setHeaders: { Authorization: `Bearer ${token}` } }) : req;

  return next(requisicao).pipe(
    catchError((erro) => {
      if (erro instanceof HttpErrorResponse && erro.status === 401 && token && !req.url.includes('/api/auth/')) {
        auth.logout(); // limpa o token morto e redireciona pra /login
      }
      return throwError(() => erro);
    }),
  );
};
