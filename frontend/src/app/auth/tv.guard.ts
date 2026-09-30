import { inject } from '@angular/core';
import { CanActivateFn, Router } from '@angular/router';
import { AuthService } from './auth.service';

/** Permissão TV_CONFERENCIA (mesma regra do backend podeVerTv): perfil TV ou ADMINISTRADOR. */
export const tvGuard: CanActivateFn = () => {
  const auth = inject(AuthService);
  const router = inject(Router);
  if (!auth.estaLogado()) {
    router.navigate(['/login']);
    return false;
  }
  const perfil = auth.usuario()?.perfil;
  if (perfil === 'TV' || perfil === 'ADMINISTRADOR') return true;
  router.navigate(['/fila-tarefas']);
  return false;
};
