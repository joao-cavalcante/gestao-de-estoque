import { inject } from '@angular/core';
import { CanActivateFn, Router } from '@angular/router';
import { AuthService } from './auth.service';

export const authGuard: CanActivateFn = () => {
  const auth = inject(AuthService);
  const router = inject(Router);

  if (auth.estaLogado()) {
    // Conta de TV (perfil TV) só tem a /tv — qualquer outra tela manda pra lá.
    if (auth.usuario()?.perfil === 'TV') {
      router.navigate(['/tv']);
      return false;
    }
    return true;
  }

  router.navigate(['/login']);
  return false;
};
