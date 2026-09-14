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
import { UsuarioListComponent } from './usuarios/usuario-list/usuario-list.component';
import { BalancaListComponent } from './balancas/balanca-list/balanca-list.component';
import { DownloadsComponent } from './downloads/downloads.component';
import { LiberacaoCorteComponent } from './liberacao-corte/liberacao-corte.component';
import { EtiquetasComponent } from './etiquetas/etiquetas.component';
import { ImpressaoEtiquetasComponent } from './impressao-etiquetas/impressao-etiquetas.component';

export const routes: Routes = [
  { path: 'login', component: LoginComponent },
  { path: '', redirectTo: 'fila-tarefas', pathMatch: 'full' },
  { path: 'fila-tarefas', component: FilaTarefasComponent, canActivate: [authGuard] },
  { path: 'conferencia/:nunota', component: ConferenciaComponent, canActivate: [authGuard] },
  { path: 'transferencia', component: TransferenciaComponent, canActivate: [authGuard] },
  { path: 'transferencias', component: TransferenciasDesktopComponent, canActivate: [authGuard] },
  { path: 'inventario', component: InventarioComponent, canActivate: [authGuard] },
  { path: 'inventarios', component: InventariosDesktopComponent, canActivate: [authGuard] },
  { path: 'inventarios/:id', component: InventarioDetalheComponent, canActivate: [authGuard] },
  { path: 'config-conferencia', component: ConfigConferenciaComponent, canActivate: [authGuard] },
  { path: 'liberacao-corte', component: LiberacaoCorteComponent, canActivate: [authGuard] },
  { path: 'impressao-etiquetas', component: ImpressaoEtiquetasComponent, canActivate: [authGuard] },
  { path: 'etiquetas', component: EtiquetasComponent, canActivate: [authGuard] },
  { path: 'etiquetas/:sessaoId', component: EtiquetasComponent, canActivate: [authGuard] },
  { path: 'tipos-operacao', component: TiposOperacaoComponent, canActivate: [authGuard] },
  { path: 'usuarios', component: UsuarioListComponent, canActivate: [authGuard] },
  { path: 'balancas', component: BalancaListComponent, canActivate: [authGuard] },
  { path: 'downloads', component: DownloadsComponent },
  { path: 'tenants', component: TenantListComponent },
  { path: 'tenants/novo', component: TenantFormComponent },
  { path: 'tenants/:slug', component: TenantFormComponent },
];
