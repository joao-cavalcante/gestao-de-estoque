import { dataGs1, diasAte, lerGs1, lerValidadeDigitada, pareceGs1 } from './gs1';

describe('gs1', () => {
  it('lê GTIN, peso, embalagem, lote e série com separador', () => {
    const r = lerGs1('01989123456789043103012345' + '13261120' + '10L4521\x1D21000987');
    expect(r?.gtin).toBe('98912345678904');
    expect(r?.pesoKg).toBe(12.345);
    expect(r?.embalagem).toBe('2026-11-20');
    expect(r?.lote).toBe('L4521');
    expect(r?.serie).toBe('000987');
  });

  it('aceita o formato legível com parênteses e o prefixo ]C1', () => {
    expect(lerGs1('(01)07891234567895(17)270131(10)AB12')).toEqual({ gtin: '07891234567895', validade: '2027-01-31', lote: 'AB12' });
    expect(lerGs1(']C10107891234567895172701311' + '0AB12')?.lote).toBe('AB12');
  });

  it('validade com dia 00 vira o último dia do mês', () => {
    expect(dataGs1('270200')).toBe('2027-02-28');
    expect(dataGs1('271301')).toBeUndefined();
  });

  it('peso em libras vira kg e 3102 tem duas casas', () => {
    expect(lerGs1('3202001000')?.pesoKg).toBe(4.536);
    expect(lerGs1('3102002530')?.pesoKg).toBe(25.3);
  });

  it('EAN-13 comum não é GS1', () => {
    expect(pareceGs1('7891234567895')).toBeFalse();
    expect(pareceGs1('0107891234567895')).toBeTrue();
    expect(lerGs1('ABC')).toBeNull();
  });

  it('validade digitada: dia/mês/ano e mês/ano', () => {
    expect(lerValidadeDigitada('13/10/26')).toBe('2026-10-13');
    expect(lerValidadeDigitada('31/02/2026')).toBeUndefined();
    expect(lerValidadeDigitada('12/26')).toBe('2026-12-31');
    expect(lerValidadeDigitada('02/2028')).toBe('2028-02-29');
    expect(lerValidadeDigitada('xx')).toBeUndefined();
  });

  it('dias até a validade', () => {
    expect(diasAte('2026-12-01', new Date(2026, 10, 27))).toBe(4);
    expect(diasAte('2026-11-20', new Date(2026, 10, 27))).toBe(-7);
  });
});
