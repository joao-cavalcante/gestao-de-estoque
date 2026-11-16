import { ObjetoPlanta } from './galpao.model';
import { enderecosDaEstrutura, gerarEnderecos, geometriaVazia, novoObjeto, totalEnderecos } from './geometria';

/** Mesmos casos de backend/src/test/kotlin/wms/backend/galpao/GeometriaTest.kt — prévia e publicação têm de bater. */
describe('geometria (prévia do editor)', () => {
  const estrutura = (codigo: string, x: number, z: number, rotacao = 0): ObjetoPlanta => ({
    codigo, tipo: 'ESTRUTURA', x, z, largura: 1.2, profundidade: 12, altura: 6, rotacao,
    setor: 'SECO', modulos: 4, niveis: 3, nivelPicking: 1, capQtd: 100,
  });

  it('gera módulo × nível endereços com o padrão de código', () => {
    const g = { ...geometriaVazia(50, 40), objetos: [estrutura('R01', 5, 10), estrutura('R02', 10, 10)] };
    const e = gerarEnderecos(g);
    expect(e.length).toBe(24);
    expect(totalEnderecos(g)).toBe(24);
    expect(e[0].codigo).toBe('R01-M01-N01');
    expect(e[e.length - 1].codigo).toBe('R02-M04-N03');
  });

  it('nível 1 é picking e acima é pulmão', () => {
    const e = enderecosDaEstrutura(estrutura('R01', 5, 10));
    expect(e.filter((x) => x.nivel === 1).every((x) => x.tipo === 'PICKING')).toBeTrue();
    expect(e.filter((x) => x.nivel > 1).every((x) => x.tipo === 'PULMAO')).toBeTrue();
  });

  it('distribui os módulos ao longo da estrutura e gira com ela', () => {
    const reto = enderecosDaEstrutura(estrutura('R01', 10, 10)).filter((x) => x.nivel === 1);
    expect(reto.map((x) => x.z)).toEqual([5.5, 8.5, 11.5, 14.5]);
    const girado = enderecosDaEstrutura(estrutura('R01', 10, 10, 90)).filter((x) => x.nivel === 1);
    [14.5, 11.5, 8.5, 5.5].forEach((x, i) => expect(girado[i].x).toBeCloseTo(x, 9));
  });

  it('novo objeto recebe o próximo código livre do tipo', () => {
    const g = { ...geometriaVazia(50, 40), objetos: [estrutura('R01', 5, 10)] };
    expect(novoObjeto(g, 'ESTRUTURA', 1, 1).codigo).toBe('R02');
    expect(novoObjeto(g, 'CORREDOR', 1, 1).codigo).toBe('C01');
  });
});
