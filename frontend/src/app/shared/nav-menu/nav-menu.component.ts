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
  somenteAdmin?: boolean;
}

/**
 * Conferência não entra aqui de propósito — só faz sentido acessada a
 * partir de um pedido específico (a partir do "Conferir" na Fila de
 * Tarefas), não como link solto sem contexto de nunota. Tenants também
 * fica de fora — é painel master, separado, ninguém além do master deve
 * ter acesso a essa tela por este menu.
 *
 * As 4 telas de operação (Fila de Conferência, Mapa de Separação,
 * Impressão de Etiquetas, Liberação de Corte) ficam soltas no topo — o
 * grupo "Operações" saiu a pedido do usuário. Só Administração segue em
 * submenu.
 */
const ITENS: ItemMenu[] = [
  // Telas do dia a dia soltas no topo, na ordem do fluxo (pedido do usuário: sem o grupo "Operações").
  { rota: '/fila-tarefas', label: 'Fila de Conferência', icone: 'list-check' },
  { rota: '/mapa-separacao', label: 'Mapa de Separação', icone: 'box' },
  { rota: '/impressao-etiquetas', label: 'Impressão de Etiquetas', icone: 'barcode' },
  { rota: '/liberacao-corte', label: 'Liberação de Corte', icone: 'ajuste' },
  // TV de acompanhamento (painel de parede) — permissão TV: admin (a conta de TV já cai direto nela).
  { rota: '/tv', label: 'TV de Conferência', icone: 'tv', somenteAdmin: true },
  // Desabilitados por enquanto (pedido do usuário) — reativar removendo o comentário:
  // { rota: '/transferencias', label: 'Transferências', icone: 'sync' },
  // { rota: '/inventarios', label: 'Auditoria de Estoque', icone: 'box' },
  // Grupo "Coletor" desabilitado por enquanto (Transferência Rápida + Contagem de Inventário):
  // {
  //   label: 'Coletor',
  //   icone: 'barcode',
  //   subItens: [
  //     { rota: '/transferencia', label: 'Transferência Rápida', icone: 'sync' },
  //     { rota: '/inventario', label: 'Contagem de Inventário', icone: 'box' },
  //   ],
  // },
  {
    label: 'Administração',
    icone: 'building',
    // Só ADMINISTRADOR vê (as rotas também têm adminGuard e o backend exigirAdmin).
    somenteAdmin: true,
    subItens: [
      { rota: '/usuarios', label: 'Usuários', icone: 'user' },
      { rota: '/balancas', label: 'Balanças', icone: 'scale' },
      { rota: '/downloads', label: 'Downloads', icone: 'download' },
      { rota: '/config-conferencia', label: 'Configuração de Conferência', icone: 'badge' },
      { rota: '/tipos-operacao', label: 'Tipos de Operação', icone: 'badge' },
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

  /** Menu do usuário logado — grupo Administração só pra ADMINISTRADOR. */
  get itens(): ItemMenu[] {
    const admin = this.auth.usuario()?.perfil === 'ADMINISTRADOR';
    return ITENS.filter((i) => admin || !i.somenteAdmin);
  }
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
