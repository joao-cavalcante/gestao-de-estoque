import { Component, inject, signal } from '@angular/core';
import { NavigationEnd, Router, RouterOutlet } from '@angular/router';
import { filter } from 'rxjs';
import { AppHeaderComponent } from './shared/app-header/app-header.component';
import { NavMenuComponent } from './shared/nav-menu/nav-menu.component';
import { ThemeService } from './shared/theme/theme.service';

/**
 * Rotas onde o header global de identidade (logo/unidade/sync/operador)
 * não faz sentido:
 * - /login: ainda não há unidade/operador autenticado pra mostrar.
 * - /conferencia/:nunota: já tem seu próprio header, específico do pedido
 *   (NOTA/PARCEIRO/VENDEDOR/PEND-CONF-DIV), calibrado a 100vh sem sobra de
 *   espaço — empilhar os dois cortaria a tela ou duplicaria informação.
 * - /tenants*: painel master separado (ver comentário em NavMenuComponent),
 *   sem conceito de unidade/operador de tenant.
 *
 * /transferencia (coletor) FICA DE FORA desta lista de propósito — ao
 * contrário da Conferência, o layout dele é centralizado e estreito
 * (max-width 480px, simulando o aparelho dentro do navegador), sobra
 * espaço nas laterais, então não há motivo pra esconder o header/menu e
 * deixar o operador sem como navegar pra outra tela.
 */
const PREFIXOS_SEM_HEADER_GLOBAL = ['/login', '/conferencia', '/tenants'];

/** Match exato de segmento — startsWith cru faria '/transferencia' casar com '/transferencias' também. */
function casaPrefixo(url: string, prefixo: string): boolean {
  return url === prefixo || url.startsWith(prefixo + '/');
}

@Component({
  selector: 'app-root',
  standalone: true,
  imports: [RouterOutlet, NavMenuComponent, AppHeaderComponent],
  templateUrl: './app.component.html',
  styleUrl: './app.component.scss',
})
export class AppComponent {
  mostrarHeaderGlobal = signal(true);

  // injetado (não usado diretamente aqui) só pra garantir que o tema seja
  // aplicado/observado assim que o app sobe, independente de qual tela abre.
  private readonly tema = inject(ThemeService);

  constructor(router: Router) {
    this.atualizar(router.url);
    router.events.pipe(filter((e) => e instanceof NavigationEnd)).subscribe((e) => {
      this.atualizar((e as NavigationEnd).urlAfterRedirects);
    });
  }

  private atualizar(url: string): void {
    this.mostrarHeaderGlobal.set(!PREFIXOS_SEM_HEADER_GLOBAL.some((p) => casaPrefixo(url, p)));
  }
}
