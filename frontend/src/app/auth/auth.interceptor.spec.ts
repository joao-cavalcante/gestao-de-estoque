import { TestBed } from '@angular/core/testing';
import { HttpClient, provideHttpClient, withInterceptors } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { provideRouter } from '@angular/router';
import { authInterceptor } from './auth.interceptor';

/** Sessão Master e sessão do tenant nunca se misturam: cada token só vai pras rotas dele. */
describe('authInterceptor — isolamento Master x tenant', () => {
  let http: HttpClient;
  let ctrl: HttpTestingController;

  beforeEach(() => {
    localStorage.setItem('wms_token', 'token-do-tenant');
    localStorage.setItem('wms_master_token', 'token-master');
    TestBed.configureTestingModule({
      providers: [provideRouter([]), provideHttpClient(withInterceptors([authInterceptor])), provideHttpClientTesting()],
    });
    http = TestBed.inject(HttpClient);
    ctrl = TestBed.inject(HttpTestingController);
  });

  afterEach(() => {
    ctrl.verify();
    localStorage.removeItem('wms_token');
    localStorage.removeItem('wms_master_token');
  });

  it('rota da plataforma leva só o token Master', () => {
    http.get('/api/master/tenants').subscribe();
    expect(ctrl.expectOne('/api/master/tenants').request.headers.get('Authorization')).toBe('Bearer token-master');
  });

  it('rota operacional leva só o token do tenant', () => {
    http.get('/api/tarefas').subscribe();
    expect(ctrl.expectOne('/api/tarefas').request.headers.get('Authorization')).toBe('Bearer token-do-tenant');
  });

  it('sem sessão Master, a rota da plataforma vai sem token (nunca com o do tenant)', () => {
    localStorage.removeItem('wms_master_token');
    http.get('/api/master/instalacao').subscribe();
    expect(ctrl.expectOne('/api/master/instalacao').request.headers.has('Authorization')).toBeFalse();
  });
});
