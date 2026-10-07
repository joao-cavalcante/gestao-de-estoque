import type { Routes } from '@angular/router';
import { TenantListComponent } from './tenants/tenant-list/tenant-list.component';
import { TenantFormComponent } from './tenants/tenant-form/tenant-form.component';
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
import { EtiquetasComponent } from './etiquetas/etiquetas.component';
import { CrachasImpressaoComponent } from './crachas/crachas-impressao.component';
import { EtiquetaPesoComponent } from './etiqueta-peso/etiqueta-peso.component';
import { CarregamentoComponent } from './reconferencia/carregamento.component';
import { ImpressaoEtiquetasComponent } from './impressao-etiquetas/impressao-etiquetas.component';
import { MapaSeparacaoComponent } from './mapa-separacao/mapa-separacao.component';
import { ConsultaProdutosComponent } from './consulta-produtos/consulta-produtos.component';
import { AuditoriaPedidoComponent } from './auditoria-pedido/auditoria-pedido.component';

export const routes: Routes = [
  { path: 'login', component: LoginComponent },
  { path: '', redirectTo: 'fila-tarefas', pathMatch: 'full' },
  { path: 'fila-tarefas', component: FilaTarefasComponent, canActivate: [authGuard] },
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
  { path: 'impressao-etiquetas', component: ImpressaoEtiquetasComponent, canActivate: [authGuard] },
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
  { path: 'tenants', component: TenantListComponent, canActivate: [adminGuard] },
  { path: 'tenants/novo', component: TenantFormComponent, canActivate: [adminGuard] },
  { path: 'tenants/:slug', component: TenantFormComponent, canActivate: [adminGuard] },
];
