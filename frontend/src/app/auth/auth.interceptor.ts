import { HttpErrorResponse, HttpInterceptorFn } from '@angular/common/http';
import { inject } from '@angular/core';
import { catchError, throwError } from 'rxjs';
import { AuthService } from './auth.service';
import { MasterAuthService } from '../master/master-auth.service';

/**
 * Injeta Authorization: Bearer em toda chamada (rotas públicas ignoram o header
 * extra) e, em 401 de rota autenticada, faz logout + volta pro login — evita o
 * "token ausente ou inválido" cru quando o JWT expira (TTL de 8h).
 *
 * /api/master/* leva SÓ o token Master (sessão própria, ver MasterAuthService) e nunca o do tenant; as demais
 * rotas nunca levam o token Master. 401 do painel encerra só a sessão Master.
 */
export const authInterceptor: HttpInterceptorFn = (req, next) => {
  if (req.url.startsWith('/api/master/')) {
    const master = inject(MasterAuthService);
    const tokenMaster = master.obterToken();
    const requisicaoMaster = tokenMaster ? req.clone({ setHeaders: { Authorization: `Bearer ${tokenMaster}` } }) : req;
    return next(requisicaoMaster).pipe(
      catchError((erro) => {
        if (erro instanceof HttpErrorResponse && erro.status === 401 && tokenMaster && !req.url.includes('/api/master/auth/login')) {
          master.sair();
        }
        return throwError(() => erro);
      }),
    );
  }

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
