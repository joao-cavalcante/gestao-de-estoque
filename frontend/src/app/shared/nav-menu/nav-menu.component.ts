import { Component, inject, signal } from '@angular/core';
import { NavigationEnd, Router, RouterLink, RouterLinkActive } from '@angular/router';
import { filter } from 'rxjs';
import { AuthService } from '../../auth/auth.service';
import { OqIconComponent, OqIconName } from '../icons/oq-icon.component';
import { NavMenuService } from './nav-menu.service';
import { GalpaoService } from '../../galpao/galpao.service';
import { SessaoContextoService } from '../app-header/sessao-contexto.service';

interface ItemMenu {
  rota?: string;
  label: string;
  icone: OqIconName;
  subItens?: ItemMenu[];
  somenteAdmin?: boolean;
  /** Só aparece com a Planta do Galpão habilitada pro tenant (módulo planta_galpao). */
  somenteGalpao?: boolean;
}

interface SecaoMenu {
  titulo: string;
  itens: ItemMenu[];
}

/**
 * Conferência não entra aqui de propósito — só faz sentido acessada a
 * partir de um pedido específico (a partir do "Conferir" na Fila de
 * Tarefas), não como link solto sem contexto de nunota. Tenants também
 * fica de fora — é painel master, separado, ninguém além do master deve
 * ter acesso a essa tela por este menu.
 *
 * Seções (17/11/2026, revisão "sem cara de IA"): só títulos fixos, não grupos que abrem e fecham — as telas do dia a
 * dia continuam soltas e à vista (o grupo recolhível "Operações" saiu a pedido do usuário). Só TV e Administração
 * seguem em submenu. Subitens sem ícone.
 */
const SECOES: SecaoMenu[] = [
  {
    titulo: 'Operação',
    itens: [
      { rota: '/fila-tarefas', label: 'Fila de Conferência', icone: 'list-check' },
      // Recebimentos de compra (19/11/2026) — a compra saiu da Fila de Conferência.
      { rota: '/entrada', label: 'Entrada', icone: 'recebimento' },
      { rota: '/mapa-separacao', label: 'Mapa de Separação', icone: 'box' },
      { rota: '/liberacao-corte', label: 'Liberação de Corte', icone: 'ajuste' },
    ],
  },
  {
    titulo: 'Impressão',
    itens: [
      { rota: '/impressao-etiquetas', label: 'Etiquetas', icone: 'barcode' },
      // Fica aberta no computador da expedição: romaneio + DANFEs saem sozinhos (agente) ao fechar a OC.
      { rota: '/impressao-oc', label: 'Ordens de Carga', icone: 'impressora' },
    ],
  },
  {
    titulo: 'Consulta',
    itens: [
      { rota: '/consulta-produtos', label: 'Produtos', icone: 'search' },
      { rota: '/auditoria-pedido', label: 'Auditoria de Pedidos', icone: 'clock' },
      { rota: '/pendencias-integracao', label: 'Pendências de Integração', icone: 'circle-alert' },
      { rota: '/galpao', label: 'Planta do Galpão', icone: 'grid', somenteGalpao: true },
    ],
  },
  // Desabilitados por enquanto (pedido do usuário) — reativar removendo o comentário:
  // { rota: '/transferencias', label: 'Transferências', icone: 'sync' },
  // { rota: '/inventarios', label: 'Auditoria de Estoque', icone: 'box' },
  // Grupo "Coletor" desabilitado por enquanto (Transferência Rápida + Contagem de Inventário):
  // { label: 'Coletor', icone: 'barcode', subItens: [
  //   { rota: '/transferencia', label: 'Transferência Rápida', icone: 'sync' },
  //   { rota: '/inventario', label: 'Contagem de Inventário', icone: 'box' } ] },
  {
    titulo: 'Gestão',
    itens: [
      // TV de acompanhamento (painel de parede) — permissão TV: admin (a conta de TV já cai direto nela).
      {
        label: 'TV de Conferência',
        icone: 'tv',
        somenteAdmin: true,
        subItens: [
          { rota: '/tv', label: 'Entradas e saídas', icone: 'tv' },
          { rota: '/tv/carga', label: 'Ordens de carga', icone: 'tv' },
        ],
      },
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
          // Protótipo "Roteiros Operacionais" (/roteiros) fora do menu desde 17/11/2026 — a rota segue no ar.
        ],
      },
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
  private readonly galpao = inject(GalpaoService);
  /** null = ainda não perguntado ao backend nesta sessão. */
  private readonly galpaoDisponivel = signal<boolean | null>(null);

  readonly contexto = inject(SessaoContextoService);

  /** Menu do usuário logado — TV e Administração só pra ADMINISTRADOR; seção sem item visível some. */
  get secoes(): SecaoMenu[] {
    const admin = this.auth.usuario()?.perfil === 'ADMINISTRADOR';
    const galpao = this.galpaoDisponivel() === true;
    return SECOES.map((s) => ({
      titulo: s.titulo,
      itens: s.itens.filter((i) => (admin || !i.somenteAdmin) && (galpao || !i.somenteGalpao)),
    })).filter((s) => s.itens.length > 0);
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
    // Painel Master também fica sem o menu do tenant: senão os links e o "Sair" operacional seguiam no DOM do
    // painel (alcançáveis por Tab), e o "Sair" encerrava a sessão do tenant, não a do Master.
    this.escondido = url.startsWith('/login') || url === '/master' || url.startsWith('/master/');
    if (url.startsWith('/login')) this.galpaoDisponivel.set(null);
    else if (!this.escondido && this.galpaoDisponivel() === null && this.auth.usuario() && this.auth.usuario()?.perfil !== 'TV') {
      this.galpaoDisponivel.set(false);
      this.galpao.disponivel().subscribe({ next: (r) => this.galpaoDisponivel.set(r.disponivel), error: () => undefined });
    }
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
