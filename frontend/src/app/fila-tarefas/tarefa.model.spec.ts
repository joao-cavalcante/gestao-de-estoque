import { iconeTipoSeparacao, rotuloTipoSeparacao, TIPOS_SEPARACAO_ATIVOS } from './tarefa.model';

describe('etapas da conferência (tarefa.model)', () => {
  it('critério ativo é por peso: só Não pesáveis e Pesáveis no filtro', () => {
    expect(TIPOS_SEPARACAO_ATIVOS.map((t) => t.id)).toEqual([4, 5]);
  });

  it('rótulos das etapas novas e das antigas (sessões abertas antes)', () => {
    expect(rotuloTipoSeparacao(4)).toBe('Não pesáveis');
    expect(rotuloTipoSeparacao(5)).toBe('Pesáveis');
    expect(rotuloTipoSeparacao(1)).toBe('Secos');
    expect(rotuloTipoSeparacao(2)).toBe('Refrigerado');
    expect(rotuloTipoSeparacao(3)).toBe('Congelado');
  });

  it('ícones: caixa pra Não pesáveis, balança pra Pesáveis, antigos preservados', () => {
    expect(iconeTipoSeparacao(4)).toBe('box');
    expect(iconeTipoSeparacao(5)).toBe('balanca');
    expect(iconeTipoSeparacao(2)).toBe('refrigerado');
    expect(iconeTipoSeparacao(null)).toBe('seco');
  });
});
