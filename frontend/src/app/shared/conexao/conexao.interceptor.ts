import { HttpErrorResponse, HttpInterceptorFn, HttpResponse } from '@angular/common/http';
import { inject } from '@angular/core';
import { tap } from 'rxjs';
import { ConexaoService } from './conexao.service';

/** Falha de rede (status 0) ou servidor fora (502/503/504) = sem conexão; qualquer resposta do servidor = com conexão. */
const STATUS_SEM_SERVIDOR = new Set([0, 502, 503, 504]);

export const conexaoInterceptor: HttpInterceptorFn = (req, next) => {
  const conexao = inject(ConexaoService);
  return next(req).pipe(
    tap({
      next: (ev) => {
        if (ev instanceof HttpResponse) conexao.marcarOnline();
      },
      error: (err) => {
        if (!(err instanceof HttpErrorResponse)) return;
        if (STATUS_SEM_SERVIDOR.has(err.status)) conexao.marcarOffline();
        else conexao.marcarOnline(); // 4xx/500 = o servidor respondeu; a conexão está ok
      },
    }),
  );
};
