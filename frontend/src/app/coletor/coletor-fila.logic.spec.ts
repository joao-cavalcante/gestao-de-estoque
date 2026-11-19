import { Tarefa } from '../fila-tarefas/tarefa.model';
import {
  dataReferencia,
  filaDoColetor,
  normalizarCodigo,
  numeroNotaDaChave,
  ordenarFila,
  paginar,
  resolverBusca,
  resolverEscolha,
  rotuloStatusColetor,
} from './coletor-fila.logic';

function tarefa(p: Partial<Tarefa> & { numeroUnico: string }): Tarefa {
  return {
    id: p.numeroUnico,
    cliente: 'CLIENTE',
    codigoCliente: null,
    status: 'aguardando',
    statusOperacional: 'aguardando',
    alerta: null,
    pedido: p.nf ?? '—',
    nf: '—',
    data: '17/11/2026',
    transporte: '—',
    motorista: null,
    responsavel: '—',
    codigoResponsavel: null,
    tipoOperacao: 'PEDIDO DE VENDA',
    codigoTipoOperacao: '1001',
    ordemCarga: null,
    itens: 0,
    valor: 0,
    periodoEntrega: '—',
    express: false,
    retira: false,
    entrega: false,
    tipMov: 'P',
    ...p,
  } as Tarefa;
}

// Chave NF-e de 44 dígitos com nNF = 000048120 nas posições 26–34.
const CHAVE = '35' + '2611' + '12345678000199' + '55' + '001' + '000048120' + '1' + '12345678' + '9';

describe('Coletor — busca na fila', () => {
  const t1 = tarefa({ numeroUnico: '72904', nf: '48120', tipMov: 'V' });
  const t2 = tarefa({ numeroUnico: '72910', nf: '500' });
  const tCorte = tarefa({ numeroUnico: '72950', status: 'aguardando_corte', statusOperacional: 'aguardando_corte' });
  const tConcluida = tarefa({ numeroUnico: '72960', status: 'concluido', statusOperacional: 'concluido', tipMov: 'V', nf: '1' });

  it('normaliza leitura (espaços, prefixos não numéricos)', () => {
    expect(normalizarCodigo(' ]C1 72904\n')).toBe('172904');
    expect(normalizarCodigo('72904')).toBe('72904');
  });

  it('1. abre pelo nº único', () => {
    const r = resolverBusca('72904', [t1, t2]);
    expect(r.tipo).toBe('abrir');
    if (r.tipo === 'abrir') {
      expect(r.tarefa.numeroUnico).toBe('72904');
      expect(r.por).toBe('nunota');
    }
  });

  it('2. abre pelo nº da nota (NUMNOTA)', () => {
    const r = resolverBusca('500', [t1, t2]);
    expect(r.tipo).toBe('abrir');
    if (r.tipo === 'abrir') {
      expect(r.tarefa.numeroUnico).toBe('72910');
      expect(r.por).toBe('numnota');
    }
  });

  it('aceita zeros à esquerda', () => {
    expect(resolverBusca('00072904', [t1]).tipo).toBe('abrir');
  });

  it('3. chave NF-e: extrai o nº da nota e SEMPRE pede confirmação', () => {
    expect(numeroNotaDaChave(CHAVE)).toBe(48120);
    const r = resolverBusca(CHAVE, [t1, t2]);
    expect(r.tipo).toBe('escolher');
    if (r.tipo === 'escolher') expect(r.candidatos.map((t) => t.numeroUnico)).toEqual(['72904']);
  });

  it('chave NF-e não casa com pedido (P) que tem o mesmo NUMNOTA', () => {
    const pedidoMesmoNumero = tarefa({ numeroUnico: '80000', nf: '48120', tipMov: 'P' });
    expect(resolverBusca(CHAVE, [pedidoMesmoNumero]).tipo).toBe('nao-encontrado');
  });

  it('4. documento inexistente → não encontrado, com explicação em texto', () => {
    const r = resolverBusca('99999', [t1, t2]);
    expect(r.tipo).toBe('nao-encontrado');
    if (r.tipo === 'nao-encontrado') expect(r.mensagem).toContain('fila de conferência');
  });

  it('5. documento já concluído → não abre, explica', () => {
    const r = resolverBusca('72960', [tConcluida]);
    expect(r.tipo).toBe('bloqueado');
    if (r.tipo === 'bloqueado') expect(r.estado).toBe('concluido');
  });

  it('6. aguardando liberação de corte → bloqueado, não abre', () => {
    const r = resolverBusca('72950', [tCorte]);
    expect(r.tipo).toBe('bloqueado');
    if (r.tipo === 'bloqueado') {
      expect(r.estado).toBe('bloqueado');
      expect(r.titulo).toContain('liberação de corte');
    }
  });

  it('ambiguidade: nº único de um = NUMNOTA de outro → operador escolhe', () => {
    const a = tarefa({ numeroUnico: '500' });
    const r = resolverBusca('500', [a, t2]);
    expect(r.tipo).toBe('escolher');
    if (r.tipo === 'escolher') expect(r.candidatos.length).toBe(2);
  });

  it('escolha de candidato bloqueado continua sem abrir', () => {
    expect(resolverEscolha(tCorte).tipo).toBe('bloqueado');
    expect(resolverEscolha(t1).tipo).toBe('abrir');
  });

  it('vazio não faz nada', () => {
    expect(resolverBusca('   ', [t1]).tipo).toBe('vazio');
  });
});

describe('Coletor — fila, ordenação e paginação', () => {
  const antiga = tarefa({ numeroUnico: '3', data: '10/11/2026' });
  const nova = tarefa({ numeroUnico: '1', data: '17/11/2026' });
  const express = tarefa({ numeroUnico: '2', data: '15/11/2026', express: true });
  const retira = tarefa({ numeroUnico: '4', data: '16/11/2026', retira: true });
  const corte = tarefa({ numeroUnico: '9', status: 'aguardando_corte', statusOperacional: 'aguardando_corte' });

  it('fila do coletor = só fase conferir (mesma regra da Torre)', () => {
    expect(filaDoColetor([antiga, corte]).map((t) => t.numeroUnico)).toEqual(['3']);
  });

  it('11. ordena: mais antigos primeiro (padrão), mais recentes, Express/Retira primeiro, nº único', () => {
    const l = [nova, express, antiga, retira];
    expect(ordenarFila(l, 'antigos').map((t) => t.numeroUnico)).toEqual(['3', '2', '4', '1']);
    expect(ordenarFila(l, 'recentes').map((t) => t.numeroUnico)).toEqual(['1', '4', '2', '3']);
    expect(ordenarFila(l, 'modalidade').map((t) => t.numeroUnico)).toEqual(['2', '4', '3', '1']);
    expect(ordenarFila(l, 'numero').map((t) => t.numeroUnico)).toEqual(['1', '2', '3', '4']);
  });

  it('data de referência usa AD_DHNEG (pedidoEm) quando existe', () => {
    const comHora = tarefa({ numeroUnico: '5', data: '10/11/2026', pedidoEm: new Date(2026, 10, 10, 15, 30) });
    expect(dataReferencia(comHora)).toBe(new Date(2026, 10, 10, 15, 30).getTime());
  });

  it('10. paginação limita a página ao intervalo válido', () => {
    const l = Array.from({ length: 31 }, (_, i) => i);
    expect(paginar(l, 1, 15).itens.length).toBe(15);
    expect(paginar(l, 3, 15).itens).toEqual([30]);
    expect(paginar(l, 9, 15).pagina).toBe(3);
    expect(paginar([], 1, 15).total).toBe(1);
  });

  it('status sempre em texto', () => {
    const emConf = tarefa({
      numeroUnico: '7',
      status: 'andamento',
      statusOperacional: 'andamento',
      andamento: { conferidos: 3, total: 8, conferente: 'CARLOS', desde: new Date() },
    });
    expect(rotuloStatusColetor(emConf).texto).toBe('Em conferência 3/8 · CARLOS');
    expect(rotuloStatusColetor(tarefa({ numeroUnico: '8', statusOperacional: 'aguardando_liberacao' })).texto).toBe('Aguardando liberação');
  });
});
