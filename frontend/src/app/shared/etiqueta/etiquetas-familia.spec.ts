import { Type } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { ActivatedRoute, Router } from '@angular/router';
import { of } from 'rxjs';
import { AuthService } from '../../auth/auth.service';
import { SeparacaoService } from '../../separacao/separacao.service';
import { EtiquetasComponent } from '../../etiquetas/etiquetas.component';
import { EtiquetaPesoComponent } from '../../etiqueta-peso/etiqueta-peso.component';

/**
 * Renderiza as duas etiquetas (volume e peso) com dados de exemplo e confere
 * que usam as MESMAS peças (cabeçalho, cliente, caixas) — e despeja o HTML com
 * os estilos pra inspeção visual lado a lado.
 */
describe('Etiquetas — mesma família visual', () => {
  const rota = (params: Record<string, string>, query: Record<string, string>) => ({
    snapshot: {
      paramMap: { get: (k: string) => params[k] ?? null },
      queryParamMap: { get: (k: string) => query[k] ?? null },
    },
  });

  beforeEach(() => {
    spyOn(window, 'print');
  });

  const dumps: Record<string, { estilos: string; html: string }> = {};

  async function render<T>(comp: Type<T>, route: unknown, separacao: unknown, cls: string): Promise<HTMLElement> {
    TestBed.resetTestingModule();
    TestBed.configureTestingModule({
      imports: [comp],
      providers: [
        { provide: ActivatedRoute, useValue: route },
        { provide: Router, useValue: { navigate: () => Promise.resolve(true) } },
        { provide: AuthService, useValue: { obterTenantSlug: () => 'sem-logo-no-teste' } },
        { provide: SeparacaoService, useValue: separacao },
      ],
    });
    const f = TestBed.createComponent(comp);
    f.detectChanges();
    await f.whenStable();
    f.detectChanges();
    const el = f.nativeElement as HTMLElement;
    dumps[cls] = {
      estilos: Array.from(document.head.querySelectorAll('style')).map((s) => s.outerHTML).join(''),
      html: (el.querySelector('.' + cls) as HTMLElement)?.outerHTML ?? '',
    };
    return el;
  }

  it('volume e peso usam cabeçalho, cliente e caixas compartilhados', async () => {
    const volume = await render(EtiquetasComponent, rota({ sessaoId: 's1' }, { etapa: '' }), {
      buscarEtapas: () => of([]),
      dadosEtiqueta: () =>
        of({ cliente: 'DOUGLAS COELHO DA SILVA PEREIRA', uf: 'SP', numeroNota: '57758', nunota: 57758, ordemCarga: 56, codParc: 67993045, numeroConferencia: 1, totalVolumes: 5 }),
    }, 'et');
    const peso = await render(EtiquetaPesoComponent, rota({ sessaoId: 's1' }, {}), {
      etiquetasPeso: () =>
        of({
          etiquetas: [
            { numero: 1, numeroFormatado: '00000000001', produto: 'QUEIJO MUSSARELA APOLO', peso: '25.300', cliente: 'LILIANE FEITOZA SILVA',
              nunota: 57797, codprod: 2904, controle: '', reimpressao: false, impressoes: 1, ordemCarga: 56, codParc: 36532787, uf: 'PE' },
          ],
        }),
    }, 'ep');

    for (const el of [volume, peso]) {
      expect(el.querySelector('oq-et-cabecalho')).withContext('cabeçalho').not.toBeNull();
      expect(el.querySelector('oq-et-cliente')).withContext('cliente').not.toBeNull();
      expect(el.querySelector('oq-et-caixas')).withContext('caixas').not.toBeNull();
    }
    expect(peso.textContent).toContain('25,300');
    expect(peso.textContent).toContain('kg');
    expect(peso.textContent).toContain('Nº ÚNICO - O.C.');
    // Caixas do Nº Único: 7 dígitos + traço fora das caixas, nas duas.
    const caixasPeso = peso.querySelectorAll('.ep__unico .et-caixas__caixa').length;
    expect(caixasPeso).toBe('5779756'.length);
    expect(peso.querySelectorAll('.ep__unico .et-caixas__traco').length).toBe(1);

    console.log('ETQ_DUMP::' + JSON.stringify(dumps));
  });
});
