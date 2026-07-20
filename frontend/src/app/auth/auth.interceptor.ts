import { HttpInterceptorFn } from '@angular/common/http';
import { inject } from '@angular/core';
import { AuthService } from './auth.service';

/** Injeta Authorization: Bearer em toda chamada — rotas públicas (/api/auth, /api/downloads) simplesmente ignoram o header extra. */
export const authInterceptor: HttpInterceptorFn = (req, next) => {
  const token = inject(AuthService).obterToken();
  if (!token) return next(req);

  return next(
    req.clone({
      setHeaders: { Authorization: `Bearer ${token}` },
    }),
  );
};
