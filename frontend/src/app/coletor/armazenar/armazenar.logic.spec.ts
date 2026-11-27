import { TarefaArmazenagem, lerEndereco, segmentos, ehDoProduto, ehOSugerido, falta, lerQtd, ordenarTarefas, tarefaPorProduto } from './armazenar.logic';

function t(p: Partial<TarefaArmazenagem>): TarefaArmazenagem {
  return {
    nutar: 1, situacao: 'AB', nunota: 1, numnota: 1, fornecedor: null, codemp: 1, codlocal: 101, codprod: 10,
    descricao: 'X', referencia: null, controle: null, unidade: 'KG', qtd: 100, qtdFeita: 0, origem: 'DOCA-1-101',
    sugerido: 'A-01-1', tipoSugerido: 'PULMAO', codusu: null, criada: null, barras: [], ...p,
  };
}

describe('armazenar.logic', () => {
  it('confere o produto por código de barras, referência ou código', () => {
    const x = t({ codprod: 10, referencia: 'LG-10', barras: ['7891234567895'] });
    expect(ehDoProduto('7891234567895', x)).toBeTrue();
    expect(ehDoProduto('lg-10', x)).toBeTrue();
    expect(ehDoProduto('10', x)).toBeTrue();
    expect(ehDoProduto('010', x)).toBeTrue();
    expect(ehDoProduto('11', x)).toBeFalse();
    expect(ehDoProduto('', x)).toBeFalse();
  });

  it('falta nunca é negativa', () => {
    expect(falta(t({ qtd: 100, qtdFeita: 30 }))).toBe(70);
    expect(falta(t({ qtd: 100, qtdFeita: 120 }))).toBe(0);
    expect(falta(t({ qtd: 0.3, qtdFeita: 0.1 }))).toBe(0.2);
  });

  it('endereço sugerido ignora caixa e espaços', () => {
    expect(ehOSugerido(' a-01-1 ', t({}))).toBeTrue();
    expect(ehOSugerido('A-02-1', t({}))).toBeFalse();
    expect(ehOSugerido('A-01-1', t({ sugerido: null }))).toBeFalse();
  });

  it('lê quantidade em pt-BR', () => {
    expect(lerQtd('1.100')).toBe(1100);
    expect(lerQtd('12,5')).toBe(12.5);
    expect(lerQtd('12.5')).toBe(12.5);
    expect(lerQtd('0')).toBeNull();
    expect(lerQtd('abc')).toBeNull();
  });

  it('em execução vem antes; acha tarefas pelo produto', () => {
    const l = [t({ nutar: 1 }), t({ nutar: 2, situacao: 'EX', codprod: 20 }), t({ nutar: 3, codprod: 20 })];
    expect(ordenarTarefas(l).map((x) => x.nutar)).toEqual([2, 1, 3]);
    expect(tarefaPorProduto('20', l).map((x) => x.nutar)).toEqual([2, 3]);
  });

  it('quebra o endereço em rua, módulo e nível', () => {
    expect(segmentos('R07-M01-N02')).toEqual([
      { rotulo: 'Rua', valor: 'R07' }, { rotulo: 'Módulo', valor: 'M01' }, { rotulo: 'Nível', valor: 'N02' },
    ]);
    expect(segmentos('DOCA')).toEqual([{ rotulo: 'Endereço', valor: 'DOCA' }]);
    expect(segmentos(null)).toEqual([]);
  });

  it('lê o endereço digitado em formatos diferentes', () => {
    expect(lerEndereco('R01-01-01', 'R01-M01-N01')).toBe('R01-M01-N01');
    expect(lerEndereco('r01m01n01', 'R01-M01-N01')).toBe('R01-M01-N01');
    expect(lerEndereco('R03 02 4', null)).toBe('R03-M02-N04');
    expect(lerEndereco('R3-2-4', null)).toBe('R03-M02-N04');
    expect(lerEndereco('R04-M05-N02', 'R01-M01-N01')).toBe('R04-M05-N02');
    expect(lerEndereco('DOCA-1-101', 'R01-M01-N01')).toBe('DOCA-1-101');
  });
});
