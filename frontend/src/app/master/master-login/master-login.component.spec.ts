import { TestBed } from '@angular/core/testing';
import { provideHttpClient, withInterceptors } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { Router, provideRouter } from '@angular/router';
import { authInterceptor } from '../../auth/auth.interceptor';
import { MasterLoginComponent, mensagemErroLogin } from './master-login.component';

/** Login Master: e-mail + senha + código TOTP; recusas sem revelar qual dado errou. */
describe('MasterLoginComponent', () => {
  let ctrl: HttpTestingController;
  let router: Router;

  beforeEach(() => {
    localStorage.removeItem('wms_master_token');
    TestBed.configureTestingModule({
      imports: [MasterLoginComponent],
      providers: [provideRouter([]), provideHttpClient(withInterceptors([authInterceptor])), provideHttpClientTesting()],
    });
    ctrl = TestBed.inject(HttpTestingController);
    router = TestBed.inject(Router);
  });

  afterEach(() => {
    ctrl.verify();
    localStorage.removeItem('wms_master_token');
    localStorage.removeItem('wms_master_usuario');
  });

  function criar() {
    const fixture = TestBed.createComponent(MasterLoginComponent);
    fixture.detectChanges();
    return fixture.componentInstance;
  }

  it('exibe o campo do código de verificação', () => {
    const fixture = TestBed.createComponent(MasterLoginComponent);
    fixture.detectChanges();
    const campo = (fixture.nativeElement as HTMLElement).querySelector('input[name="codigo"]') as HTMLInputElement;
    expect(campo).not.toBeNull();
    expect(campo.getAttribute('autocomplete')).toBe('one-time-code');
    expect(campo.getAttribute('inputmode')).toBe('numeric');
  });

  it('não chama o servidor sem o código de 6 dígitos', () => {
    const c = criar();
    c.email = 'master@produto.com';
    c.senha = 'senha-forte-123';
    c.codigo = '12345';
    c.entrar();
    expect(c.erro()).toContain('6 dígitos');
    ctrl.expectNone('/api/master/auth/login');
  });

  it('envia e-mail, senha e código e guarda a sessão Master', () => {
    const c = criar();
    const navegar = spyOn(router, 'navigate').and.resolveTo(true);
    c.email = ' master@produto.com ';
    c.senha = 'senha-forte-123';
    c.codigo = '123 456';
    c.entrar();
    const req = ctrl.expectOne('/api/master/auth/login');
    expect(req.request.body).toEqual({ email: 'master@produto.com', senha: 'senha-forte-123', codigo: '123456' });
    req.flush({ token: 'tok', master: { id: '1', nome: 'Master', email: 'master@produto.com' } });
    expect(localStorage.getItem('wms_master_token')).toBe('tok');
    expect(navegar).toHaveBeenCalledWith(['/master']);
  });

  it('recusa limpa senha e código e mostra mensagem genérica', () => {
    const c = criar();
    c.email = 'master@produto.com';
    c.senha = 'senha-errada-123';
    c.codigo = '654321';
    c.entrar();
    ctrl.expectOne('/api/master/auth/login').flush({ erro: 'credenciais inválidas' }, { status: 401, statusText: 'Unauthorized' });
    expect(c.senha).toBe('');
    expect(c.codigo).toBe('');
    expect(c.erro()).toBe(mensagemErroLogin(401));
    expect(c.carregando()).toBeFalse();
  });

  it('mensagens de bloqueio, configuração ausente e rede não permitida', () => {
    expect(mensagemErroLogin(429)).toContain('15 minutos');
    expect(mensagemErroLogin(503)).toContain('não configurada');
    expect(mensagemErroLogin(404)).toContain('rede');
    expect(mensagemErroLogin(500)).toContain('Tente novamente');
  });
});
