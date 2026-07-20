import { Component, inject } from '@angular/core';
import { OqIconComponent } from '../icons/oq-icon.component';
import { NavMenuService } from '../nav-menu/nav-menu.service';
import { ThemeService } from '../theme/theme.service';
import { SessaoContextoService } from './sessao-contexto.service';
import { SyncTickService } from './sync-tick.service';

/**
 * Header global de identidade (logo, unidade/turno, status de sync,
 * operador logado) + botão de abrir a navegação — renderizado uma vez em
 * app.component, fora do fluxo de cada tela (só some em rotas que já têm
 * header próprio ou não fazem sentido com o contexto de unidade/operador,
 * ver AppComponent.mostrarHeaderGlobal).
 */
@Component({
  selector: 'app-header',
  standalone: true,
  imports: [OqIconComponent],
  templateUrl: './app-header.component.html',
  styleUrl: './app-header.component.scss',
})
export class AppHeaderComponent {
  private readonly nav = inject(NavMenuService);
  readonly contexto = inject(SessaoContextoService);
  readonly sync = inject(SyncTickService);
  readonly temaService = inject(ThemeService);

  readonly menuAberto = this.nav.aberto;
  readonly tema = this.temaService.tema;

  alternarMenu(): void {
    this.nav.alternar();
  }

  alternarTema(): void {
    this.temaService.alternar();
  }
}
