import type { Routes } from '@angular/router';
import { MasterLoginComponent } from './master/master-login/master-login.component';
import { MasterTenantsComponent } from './master/master-tenants/master-tenants.component';
import { MasterTenantComponent } from './master/master-tenant/master-tenant.component';
import { masterGuard } from './master/master.guard';
import { FilaTarefasComponent } from './fila-tarefas/fila-tarefas.component';
import { ConferenciaComponent } from './conferencia/conferencia.component';
import { TransferenciaComponent } from './transferencia/transferencia.component';
import { TransferenciasDesktopComponent } from './transferencias-desktop/transferencias-desktop.component';
import { InventarioComponent } from './inventario/inventario.component';
import { InventariosDesktopComponent } from './inventarios-desktop/inventarios-desktop.component';
import { InventarioDetalheComponent } from './inventarios-desktop/inventario-detalhe/inventario-detalhe.component';
import { ConfigConferenciaComponent } from './config-conferencia/config-conferencia.component';
import { TiposOperacaoComponent } from './tipos-operacao/tipos-operacao.component';
import { LoginComponent } from './auth/login/login.component';
import { authGuard } from './auth/auth.guard';
import { adminGuard } from './auth/admin.guard';
import { tvGuard } from './auth/tv.guard';
import { TvComponent } from './tv/tv.component';
import { TvOcComponent } from './tv/tv-oc.component';
import { UsuarioListComponent } from './usuarios/usuario-list/usuario-list.component';
import { BalancaListComponent } from './balancas/balanca-list/balanca-list.component';
import { DownloadsComponent } from './downloads/downloads.component';
import { LiberacaoCorteComponent } from './liberacao-corte/liberacao-corte.component';
import { PendenciasIntegracaoComponent } from './pendencias-integracao/pendencias-integracao.component';
import { EtiquetasComponent } from './etiquetas/etiquetas.component';
import { CrachasImpressaoComponent } from './crachas/crachas-impressao.component';
import { EtiquetaPesoComponent } from './etiqueta-peso/etiqueta-peso.component';
import { CarregamentoComponent } from './reconferencia/carregamento.component';
import { ImpressaoEtiquetasComponent } from './impressao-etiquetas/impressao-etiquetas.component';
import { ImpressaoOcComponent } from './impressao-oc/impressao-oc.component';
import { MapaSeparacaoComponent } from './mapa-separacao/mapa-separacao.component';
import { ConsultaProdutosComponent } from './consulta-produtos/consulta-produtos.component';
import { AuditoriaPedidoComponent } from './auditoria-pedido/auditoria-pedido.component';
import { ColetorFilaComponent } from './coletor/coletor-fila.component';
import { coletorRedirectGuard } from './coletor/coletor-modo';

export const routes: Routes = [
  { path: 'login', component: LoginComponent },
  { path: '', redirectTo: 'fila-tarefas', pathMatch: 'full' },
  // Coletor (18/11/2026): aparelho de toque até 639px vai para a fila própria; computador/tablet seguem na Torre.
  { path: 'fila-tarefas', component: FilaTarefasComponent, canActivate: [authGuard, coletorRedirectGuard] },
  { path: 'coletor', component: ColetorFilaComponent, canActivate: [authGuard] },
  // Entrada (19/11/2026): recebimentos de compra (saíram da Fila) e mesa de liberação — só leitura.
  {
    path: 'entrada',
    canActivate: [authGuard],
    loadComponent: () => import('./entrada/entrada-lista.component').then((m) => m.EntradaListaComponent),
  },
  {
    path: 'entrada/:nunota',
    canActivate: [authGuard],
    loadComponent: () => import('./entrada/entrada-mesa.component').then((m) => m.EntradaMesaComponent),
  },
  // TV de acompanhamento da conferência (painel de parede) — perfil TV ou admin.
  { path: 'tv', component: TvComponent, canActivate: [tvGuard] },
  // TV exclusiva de Ordens de Carga (conferência + carregamento por OC). Antes de 'tv/:movimento'.
  { path: 'tv/carga', component: TvOcComponent, canActivate: [tvGuard] },
  // /tv/saida = só vendas (expedição) | /tv/entrada = só compras (recebimento)
  { path: 'tv/:movimento', component: TvComponent, canActivate: [tvGuard] },
  { path: 'conferencia/:nunota', component: ConferenciaComponent, canActivate: [authGuard] },
  { path: 'transferencia', component: TransferenciaComponent, canActivate: [authGuard] },
  { path: 'transferencias', component: TransferenciasDesktopComponent, canActivate: [authGuard] },
  { path: 'inventario', component: InventarioComponent, canActivate: [authGuard] },
  { path: 'inventarios', component: InventariosDesktopComponent, canActivate: [authGuard] },
  { path: 'inventarios/:id', component: InventarioDetalheComponent, canActivate: [authGuard] },
  { path: 'config-conferencia', component: ConfigConferenciaComponent, canActivate: [adminGuard] },
  { path: 'liberacao-corte', component: LiberacaoCorteComponent, canActivate: [authGuard] },
  { path: 'pendencias-integracao', component: PendenciasIntegracaoComponent, canActivate: [authGuard] },
  { path: 'impressao-etiquetas', component: ImpressaoEtiquetasComponent, canActivate: [authGuard] },
  { path: 'impressao-oc', component: ImpressaoOcComponent, canActivate: [authGuard] },
  { path: 'carregamento', component: CarregamentoComponent, canActivate: [authGuard] },
  { path: 'carregamento/:sessaoId', component: CarregamentoComponent, canActivate: [authGuard] },
  { path: 'mapa-separacao', component: MapaSeparacaoComponent, canActivate: [authGuard] },
  { path: 'consulta-produtos', component: ConsultaProdutosComponent, canActivate: [authGuard] },
  // Linha do tempo de um pedido (Sankhya + WMS); /auditoria-pedido/63486 abre direto.
  { path: 'auditoria-pedido', component: AuditoriaPedidoComponent, canActivate: [authGuard] },
  { path: 'auditoria-pedido/:numero', component: AuditoriaPedidoComponent, canActivate: [authGuard] },
  { path: 'etiquetas', component: EtiquetasComponent, canActivate: [authGuard] },
  { path: 'etiquetas/:sessaoId', component: EtiquetasComponent, canActivate: [authGuard] },
  { path: 'etiquetas-peso/:sessaoId', component: EtiquetaPesoComponent, canActivate: [authGuard] },
  { path: 'tipos-operacao', component: TiposOperacaoComponent, canActivate: [adminGuard] },
  { path: 'usuarios', component: UsuarioListComponent, canActivate: [adminGuard] },
  { path: 'crachas', component: CrachasImpressaoComponent, canActivate: [adminGuard] },
  { path: 'balancas', component: BalancaListComponent, canActivate: [adminGuard] },
  { path: 'downloads', component: DownloadsComponent, canActivate: [adminGuard] },
  // Planta do Galpão (módulo planta_galpao): consulta pra todos; o backend recusa se o módulo estiver desligado.
  {
    path: 'galpao',
    canActivate: [authGuard],
    loadComponent: () => import('./galpao/plantas-lista/plantas-lista.component').then((m) => m.PlantasListaComponent),
  },
  {
    path: 'galpao/:id',
    canActivate: [authGuard],
    loadComponent: () => import('./galpao/planta-visao/planta-visao.component').then((m) => m.PlantaVisaoComponent),
  },
  {
    path: 'galpao/:id/produtos',
    canActivate: [authGuard],
    loadComponent: () => import('./galpao/planta-produtos/planta-produtos.component').then((m) => m.PlantaProdutosComponent),
  },
  {
    path: 'galpao/:id/alertas',
    canActivate: [authGuard],
    loadComponent: () => import('./galpao/planta-alertas/planta-alertas.component').then((m) => m.PlantaAlertasComponent),
  },
  {
    path: 'galpao/:id/operacao',
    canActivate: [authGuard],
    loadComponent: () => import('./galpao/planta-operacao/planta-operacao.component').then((m) => m.PlantaOperacaoComponent),
  },
  {
    path: 'galpao/:id/editor',
    canActivate: [adminGuard],
    loadComponent: () => import('./galpao/planta-editor/planta-editor.component').then((m) => m.PlantaEditorComponent),
  },
  {
    path: 'galpao/:id/controle',
    canActivate: [adminGuard],
    loadComponent: () => import('./galpao/planta-controle/planta-controle.component').then((m) => m.PlantaControleComponent),
  },
  // PROTÓTIPO Roteiros Operacionais — só interface, dados demonstrativos no navegador (ver roteiros/roteiros.model.ts).
  {
    path: 'roteiros',
    canActivate: [adminGuard],
    loadComponent: () => import('./roteiros/roteiros-lista/roteiros-lista.component').then((m) => m.RoteirosListaComponent),
  },
  {
    path: 'roteiros/:id',
    canActivate: [adminGuard],
    loadComponent: () => import('./roteiros/roteiro-construtor/roteiro-construtor.component').then((m) => m.RoteiroConstrutorComponent),
  },
  // Painel Master (administração da PLATAFORMA): login e sessão próprios, nunca o ADMINISTRADOR de um cliente.
  { path: 'master/login', component: MasterLoginComponent },
  { path: 'master', component: MasterTenantsComponent, canActivate: [masterGuard] },
  { path: 'master/tenants/:id', component: MasterTenantComponent, canActivate: [masterGuard] },
  // Endereço antigo da tela de tenants (era aberta a qualquer ADMINISTRADOR) — agora é o Painel Master.
  { path: 'tenants', redirectTo: 'master', pathMatch: 'full' },
  { path: 'tenants/:qualquer', redirectTo: 'master' },
];
