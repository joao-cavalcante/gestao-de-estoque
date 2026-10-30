import { CUSTOM_ELEMENTS_SCHEMA, Component } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { Router, RouterOutlet, provideRouter } from '@angular/router';
import { AppComponent } from './app.component';

@Component({ standalone: true, template: '' })
class TelaVaziaComponent {}

/**
 * Casca da aplicação: o header global (unidade/operador do tenant) aparece nas telas operacionais e some no
 * login, na conferência e no Painel Master (que não tem tenant). Os filhos (header, menu, banners) viram elementos
 * vazios aqui — o teste é do AppComponent, não deles.
 */
describe('AppComponent', () => {
  let router: Router;

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [AppComponent],
      providers: [provideRouter([{ path: '**', component: TelaVaziaComponent }])],
    })
      .overrideComponent(AppComponent, { set: { imports: [RouterOutlet], schemas: [CUSTOM_ELEMENTS_SCHEMA] } })
      .compileComponents();
    router = TestBed.inject(Router);
  });

  async function abrir(url: string) {
    const fixture = TestBed.createComponent(AppComponent);
    await router.navigateByUrl(url);
    fixture.detectChanges();
    return { app: fixture.componentInstance, dom: fixture.nativeElement as HTMLElement };
  }

  it('cria a casca com menu, área de conteúdo e avisos globais', async () => {
    const { app, dom } = await abrir('/fila');
    expect(app).toBeTruthy();
    expect(dom.querySelector('app-nav-menu')).not.toBeNull();
    expect(dom.querySelector('router-outlet')).not.toBeNull();
    expect(dom.querySelector('oq-conexao-banner')).not.toBeNull();
    expect(dom.querySelector('oq-nova-versao')).not.toBeNull();
  });

  it('mostra o header global nas telas operacionais', async () => {
    for (const url of ['/fila', '/transferencia', '/usuarios', '/tipos-operacao']) {
      const { app, dom } = await abrir(url);
      expect(app.mostrarHeaderGlobal()).withContext(url).toBeTrue();
      expect(dom.querySelector('app-header')).withContext(url).not.toBeNull();
    }
  });

  it('esconde o header global no login, na conferência e no Painel Master', async () => {
    for (const url of ['/login', '/conferencia/123', '/master', '/master/login', '/master/tenants/abc', '/tv', '/etiquetas']) {
      const { app, dom } = await abrir(url);
      expect(app.mostrarHeaderGlobal()).withContext(url).toBeFalse();
      expect(dom.querySelector('app-header')).withContext(url).toBeNull();
    }
  });

  it('compara o prefixo por segmento inteiro (/masterx não é o Painel Master)', async () => {
    const { app } = await abrir('/masterx');
    expect(app.mostrarHeaderGlobal()).toBeTrue();
  });
});
