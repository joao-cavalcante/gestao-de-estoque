import { Component, inject, signal } from '@angular/core';
import { NavigationEnd, Router, RouterLink, RouterLinkActive } from '@angular/router';
import { filter } from 'rxjs';
import { AuthService } from '../../auth/auth.service';
import { OqIconComponent, OqIconName } from '../icons/oq-icon.component';
import { NavMenuService } from './nav-menu.service';

interface ItemMenu {
  rota?: string;
  label: string;
  icone: OqIconName;
  subItens?: ItemMenu[];
}

/**
 * Conferência não entra aqui de propósito — só faz sentido acessada a
 * partir de um pedido específico (a partir do "Conferir" na Fila de
 * Tarefas), não como link solto sem contexto de nunota. Tenants também
 * fica de fora — é painel master, separado, ninguém além do master deve
 * ter acesso a essa tela por este menu.
 *
 * Agrupado em 3 categorias (Operações/Coletor/Administração) — a lista
 * plana virou 8+ itens soltos conforme os módulos foram entrando, e isso
 * deixou de escalar. Fila de Tarefas fica sozinha no topo por ser a tela
 * principal do dia a dia, o resto entra em submenu.
 */
const ITENS: ItemMenu[] = [
  { rota: '/fila-tarefas', label: 'Fila de Tarefas', icone: 'list-check' },
  {
    label: 'Operações',
    icone: 'clock',
    subItens: [
      { rota: '/transferencias', label: 'Transferências', icone: 'sync' },
      { rota: '/inventarios', label: 'Auditoria de Estoque', icone: 'box' },
      { rota: '/liberacao-corte', label: 'Liberação de Corte', icone: 'badge' },
    ],
  },
  {
    label: 'Coletor',
    icone: 'barcode',
    subItens: [
      { rota: '/transferencia', label: 'Transferência Rápida', icone: 'sync' },
      { rota: '/inventario', label: 'Contagem de Inventário', icone: 'box' },
    ],
  },
  {
    label: 'Administração',
    icone: 'building',
    subItens: [
      { rota: '/usuarios', label: 'Usuários', icone: 'user' },
      { rota: '/balancas', label: 'Balanças', icone: 'scale' },
      { rota: '/downloads', label: 'Downloads', icone: 'download' },
      { rota: '/config-conferencia', label: 'Configuração de Conferência', icone: 'badge' },
      { rota: '/tipos-operacao', label: 'Tipos de Operação', icone: 'badge' },
      { rota: '/impressao-etiquetas', label: 'Impressão de Etiquetas', icone: 'download' },
    ],
  },
];

/**
 * Só o drawer + backdrop — o botão que abre/fecha não vive mais aqui.
 * Cada tela expõe seu próprio toggle (ex.: oq-header, agrupado com a
 * identidade da marca), todos operando o mesmo NavMenuService. Isto
 * evita um FAB solto flutuando fora do header, sem âncora visual a
 * nenhum outro elemento da tela.
 */
@Component({
  selector: 'app-nav-menu',
  standalone: true,
  imports: [RouterLink, RouterLinkActive, OqIconComponent],
  templateUrl: './nav-menu.component.html',
  styleUrl: './nav-menu.component.scss',
})
export class NavMenuComponent {
  private readonly auth = inject(AuthService);
  private readonly router = inject(Router);
  private readonly nav = inject(NavMenuService);

  readonly itens = ITENS;
  readonly aberto = this.nav.aberto;
  escondido = false;

  /** Grupos com sub-itens expandidos — por label, sobrevive à navegação (só fecha quando o drawer inteiro fecha). */
  private readonly gruposAbertos = signal(new Set<string>());

  constructor() {
    this.atualizarVisibilidade(this.router.url);
    this.router.events.pipe(filter((e) => e instanceof NavigationEnd)).subscribe((e) => {
      this.atualizarVisibilidade((e as NavigationEnd).urlAfterRedirects);
      this.nav.fechar();
    });
  }

  private atualizarVisibilidade(url: string): void {
    this.escondido = url.startsWith('/login');
  }

  grupoAberto(label: string): boolean {
    return this.gruposAbertos().has(label);
  }

  alternarGrupo(label: string): void {
    this.gruposAbertos.update((atual) => {
      const novo = new Set(atual);
      if (novo.has(label)) novo.delete(label);
      else novo.add(label);
      return novo;
    });
  }

  fechar(): void {
    this.nav.fechar();
  }

  sair(): void {
    this.fechar();
    this.auth.logout();
  }
}
