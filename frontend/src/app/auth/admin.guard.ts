import { inject } from '@angular/core';
import { CanActivateFn, Router } from '@angular/router';
import { AuthService } from './auth.service';

/**
 * Telas de Administração (cadastros e configurações) — só perfil ADMINISTRADOR. Operador/estação que
 * digitar a URL volta pra Fila. O backend também barra (exigirAdmin) — isto é só a porta da tela.
 */
export const adminGuard: CanActivateFn = () => {
  const auth = inject(AuthService);
  const router = inject(Router);

  if (!auth.estaLogado()) {
    router.navigate(['/login']);
    return false;
  }
  if (auth.usuario()?.perfil === 'ADMINISTRADOR') return true;

  router.navigate(['/fila-tarefas']);
  return false;
};
