import { inject } from '@angular/core';
import { CanActivateFn, Router } from '@angular/router';
import { MasterAuthService } from './master-auth.service';

/** Painel Master — só com sessão Master. Só a porta da tela: toda rota /api/master valida o token no backend. */
export const masterGuard: CanActivateFn = () => {
  if (inject(MasterAuthService).estaLogado()) return true;
  inject(Router).navigate(['/master/login']);
  return false;
};
