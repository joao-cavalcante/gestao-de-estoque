import { ligacaoCircular, organizar, simular, validar } from './roteiro-logica';
import { MODELOS, roteirosDemonstracao } from './roteiros-demo.data';
import { Roteiro, configPadrao } from './roteiros.model';

function roteiroDe(id: string): Roteiro {
  return roteirosDemonstracao().find((r) => r.id === id)!;
}

function nomesExecutados(r: Roteiro, cenario: Parameters<typeof simular>[1]): string[] {
  const porId = new Map(r.etapas.map((e) => [e.id, e.nome]));
  return simular(r, cenario)
    .passos.filter((p) => p.resultado === 'EXECUTADA' || p.resultado === 'FIM')
    .map((p) => porId.get(p.etapaId)!);
}

describe('Roteiros (protótipo) — lógica', () => {
  it('roteiros de demonstração de Saída não têm erros', () => {
    for (const r of roteirosDemonstracao().filter((x) => x.tipo === 'SAIDA')) {
      expect(validar(r).filter((v) => v.nivel === 'ERRO')).withContext(r.nome).toEqual([]);
    }
  });

  it('todos os modelos montam um roteiro sem erros', () => {
    for (const m of MODELOS) {
      const g = m.montar();
      const r = organizar({ id: m.id, nome: m.nome, descricao: '', tipo: m.tipo, status: 'RASCUNHO', versao: 1, atualizadoEm: '', demonstracao: true, ...g });
      expect(validar(r).filter((v) => v.nivel === 'ERRO')).withContext(m.nome).toEqual([]);
    }
  });

  it('Expedição Padrão: pesagem e liberação só nos cenários que pedem', () => {
    const r = roteiroDe('demo-expedicao-padrao');
    expect(nomesExecutados(r, { PESAVEIS: false, DIVERGENCIAS: false })).toEqual([
      'Início', 'Conferência', 'Carregamento', 'Faturamento', 'Impressão automática', 'Finalização',
    ]);
    expect(nomesExecutados(r, { PESAVEIS: true, DIVERGENCIAS: true })).toEqual([
      'Início', 'Conferência', 'Pesagem de mercadorias', 'Liberação de corte', 'Carregamento', 'Faturamento', 'Impressão automática', 'Finalização',
    ]);
  });

  it('etapa condicional fica ignorada quando a condição não vale', () => {
    const r = roteiroDe('demo-expedicao-pesagem');
    const res = simular(r, { PESAVEIS: false, DIVERGENCIAS: true });
    const pesagem = r.etapas.find((e) => e.tipo === 'PESAGEM')!;
    expect(res.passos.find((p) => p.etapaId === pesagem.id)?.resultado).toBe('IGNORADA');
    expect(res.interrupcao).toBeNull();
  });

  it('acusa roteiro sem Início, sem Finalização e etapa solta', () => {
    const r: Roteiro = {
      id: 'x', nome: 'x', descricao: '', tipo: 'SAIDA', status: 'RASCUNHO', versao: 1, atualizadoEm: '', demonstracao: true,
      etapas: [{ id: 'a', tipo: 'CONFERENCIA', nome: 'Conferência', x: 0, y: 0, config: configPadrao('CONFERENCIA') }],
      conexoes: [],
    };
    const msgs = validar(r).map((v) => v.mensagem);
    expect(msgs.some((m) => m.includes('não tem Início'))).toBeTrue();
    expect(msgs.some((m) => m.includes('não tem Finalização'))).toBeTrue();
    expect(msgs.some((m) => m.includes('está solta'))).toBeTrue();
  });

  it('acusa ligação circular, condição sem caminho e dependência faltando', () => {
    const r = roteiroDe('demo-expedicao-padrao');
    const conf = r.etapas.find((e) => e.tipo === 'CONFERENCIA')!;
    const fat = r.etapas.find((e) => e.tipo === 'FATURAMENTO')!;
    const cond = r.etapas.find((e) => e.tipo === 'CONDICAO')!;
    const comCiclo: Roteiro = {
      ...r,
      conexoes: [...r.conexoes.filter((c) => !(c.de === cond.id && c.ramo === 'NAO')), { id: 'volta', de: fat.id, para: conf.id, ramo: null }],
    };
    expect(ligacaoCircular(comCiclo)?.id).toBe('volta');
    const msgs = validar(comCiclo).map((v) => v.mensagem);
    expect(msgs.some((m) => m.includes('Retornos não são permitidos'))).toBeTrue();
    expect(msgs.some((m) => m.includes('não tem caminho para Não'))).toBeTrue();

    const semConferencia: Roteiro = { ...r, etapas: r.etapas.filter((e) => e.id !== conf.id), conexoes: r.conexoes.filter((c) => c.de !== conf.id && c.para !== conf.id) };
    expect(validar(semConferencia).some((v) => v.mensagem.includes('precisa de uma Conferência'))).toBeTrue();
  });

  it('organizar põe cada etapa abaixo da anterior', () => {
    const r = organizar(roteiroDe('demo-expedicao-pesagem'));
    const porId = new Map(r.etapas.map((e) => [e.id, e]));
    for (const c of r.conexoes) expect(porId.get(c.para)!.y).toBeGreaterThan(porId.get(c.de)!.y);
  });
});
