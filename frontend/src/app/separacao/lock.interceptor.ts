import { HttpErrorResponse, HttpInterceptorFn } from '@angular/common/http';
import { inject } from '@angular/core';
import { catchError, throwError } from 'rxjs';
import { LockService } from './lock.service';

/**
 * Manda o token do lock (X-Lock-Token) em toda chamada de /api/separacao e, quando o backend rejeita uma
 * operação com 409 LOCK_INVALIDO (a sessão da etapa expirou ou outro operador assumiu), avisa a tela pelo
 * LockService.invalido — a conferência mostra "sua sessão não é mais válida" em vez de um erro genérico.
 */
export const lockInterceptor: HttpInterceptorFn = (req, next) => {
  if (!req.url.includes('/api/separacao/')) return next(req);

  const lock = inject(LockService);
  return next(req.clone({ setHeaders: { 'X-Lock-Token': lock.token } })).pipe(
    catchError((erro) => {
      if (erro instanceof HttpErrorResponse && erro.status === 409 && erro.error?.codigo === 'LOCK_INVALIDO') {
        lock.invalido.set(erro.error?.erro ?? 'Sua sessão nesta etapa não é mais válida.');
      }
      return throwError(() => erro);
    }),
  );
};
