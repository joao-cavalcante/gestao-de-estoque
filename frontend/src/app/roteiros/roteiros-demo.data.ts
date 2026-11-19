import { organizar } from './roteiro-logica';
import {
  ConfigEtapa,
  Conexao,
  Etapa,
  Ramo,
  Roteiro,
  StatusRoteiro,
  TipoBloco,
  TipoRoteiro,
  configPadrao,
  definicao,
  novoId,
} from './roteiros.model';

/**
 * PROTÓTIPO — modelos prontos e roteiros de demonstração. Dados fictícios: não vêm do Sankhya nem do
 * banco, e não representam a configuração de nenhum cliente.
 */

class Montador {
  readonly etapas: Etapa[] = [];
  readonly conexoes: Conexao[] = [];

  etapa(tipo: TipoBloco, nome?: string, cfg: Partial<ConfigEtapa> = {}): string {
    const id = novoId('etapa');
    this.etapas.push({ id, tipo, nome: nome ?? definicao(tipo).rotulo, x: 0, y: 0, config: { ...configPadrao(tipo), ...cfg } });
    return id;
  }

  ligar(de: string, para: string, ramo: Ramo | null = null): void {
    this.conexoes.push({ id: novoId('lig'), de, para, ramo });
  }

  sequencia(...ids: string[]): void {
    for (let i = 0; i < ids.length - 1; i++) this.ligar(ids[i], ids[i + 1]);
  }
}

export interface ModeloRoteiro {
  id: string;
  nome: string;
  tipo: TipoRoteiro;
  descricao: string;
  /** Resumo em uma linha: "Conferência → Faturamento → Impressão". */
  trilha: string;
  /** Entrada e Devolução: processos que a aplicação ainda não tem. */
  conceitual: boolean;
  montar(): { etapas: Etapa[]; conexoes: Conexao[] };
}

export const MODELOS: ModeloRoteiro[] = [
  {
    id: 'expedicao-simples',
    nome: 'Expedição Simples',
    tipo: 'SAIDA',
    descricao: 'Para retirada no balcão: confere, fatura e imprime.',
    trilha: 'Conferência → Faturamento → Impressão',
    conceitual: false,
    montar() {
      const m = new Montador();
      m.sequencia(
        m.etapa('INICIO'),
        m.etapa('CONFERENCIA'),
        m.etapa('FATURAMENTO'),
        m.etapa('IMPRESSAO', 'Impressão da nota', { documentos: { nota: true, romaneio: false, etiquetas: false } }),
        m.etapa('FINALIZACAO'),
      );
      return m;
    },
  },
  {
    id: 'expedicao-completa',
    nome: 'Expedição Completa',
    tipo: 'SAIDA',
    descricao: 'Entrega com Ordem de Carga: pesagem e liberação só quando necessárias.',
    trilha: 'Conferência → Pesagem condicional → Liberação de divergências → Carregamento → Faturamento → Impressão',
    conceitual: false,
    montar() {
      const m = new Montador();
      m.sequencia(
        m.etapa('INICIO'),
        m.etapa('CONFERENCIA'),
        m.etapa('PESAGEM', 'Pesagem de mercadorias', { execucao: 'CONDICIONAL', condicao: 'PESAVEIS' }),
        m.etapa('LIBERACAO_CORTE', 'Liberação de divergências', { execucao: 'CONDICIONAL', condicao: 'DIVERGENCIAS' }),
        m.etapa('CARREGAMENTO'),
        m.etapa('FATURAMENTO'),
        m.etapa('IMPRESSAO', 'Impressão de nota e romaneio'),
        m.etapa('FINALIZACAO'),
      );
      return m;
    },
  },
  {
    id: 'recebimento',
    nome: 'Recebimento',
    tipo: 'ENTRADA',
    descricao: 'Conceitual: recebimento com aprovação quando houver divergência.',
    trilha: 'Identificação → Conferência física → Divergência → Aprovação → Finalização',
    conceitual: true,
    montar() {
      const m = new Montador();
      const inicio = m.etapa('INICIO');
      const ident = m.etapa('IDENTIFICACAO', 'Identificação da nota de entrada');
      const conf = m.etapa('CONFERENCIA', 'Conferência física');
      const cond = m.etapa('CONDICAO', 'Divergência na conferência?', { pergunta: 'DIVERGENCIAS' });
      const aprov = m.etapa('APROVACAO', 'Aprovação do comprador', { responsavel: 'SUPERVISOR' });
      const fim = m.etapa('FINALIZACAO');
      m.sequencia(inicio, ident, conf, cond);
      m.ligar(cond, aprov, 'SIM');
      m.ligar(cond, fim, 'NAO');
      m.ligar(aprov, fim);
      return m;
    },
  },
  {
    id: 'devolucao-cliente',
    nome: 'Devolução de Cliente',
    tipo: 'DEVOLUCAO',
    descricao: 'Conceitual: avalia a mercadoria devolvida e decide o destino.',
    trilha: 'Identificação da devolução → Conferência → Avaliação → Aprovação → Destinação',
    conceitual: true,
    montar() {
      const m = new Montador();
      const inicio = m.etapa('INICIO');
      const ident = m.etapa('IDENTIFICACAO', 'Identificação da devolução');
      const conf = m.etapa('CONFERENCIA', 'Conferência da devolução');
      const aval = m.etapa('AVALIACAO');
      const cond = m.etapa('CONDICAO', 'Mercadoria avariada?', { pergunta: 'AVARIA' });
      const aprov = m.etapa('APROVACAO', 'Aprovação do supervisor', { responsavel: 'SUPERVISOR' });
      const dest = m.etapa('DESTINACAO');
      const fim = m.etapa('FINALIZACAO');
      m.sequencia(inicio, ident, conf, aval, cond);
      m.ligar(cond, aprov, 'SIM');
      m.ligar(cond, dest, 'NAO');
      m.ligar(aprov, dest);
      m.ligar(dest, fim);
      return m;
    },
  },
];

/** O exemplo do enunciado, com as perguntas desenhadas como bifurcações. */
function expedicaoPadrao(): { etapas: Etapa[]; conexoes: Conexao[] } {
  const m = new Montador();
  const inicio = m.etapa('INICIO');
  const conf = m.etapa('CONFERENCIA');
  const pesaveis = m.etapa('CONDICAO', 'Possui produtos pesáveis?', { pergunta: 'PESAVEIS' });
  const pesagem = m.etapa('PESAGEM', 'Pesagem de mercadorias');
  const diverg = m.etapa('CONDICAO', 'Existem divergências?', { pergunta: 'DIVERGENCIAS' });
  const liberacao = m.etapa('LIBERACAO_CORTE');
  const carreg = m.etapa('CARREGAMENTO');
  const fat = m.etapa('FATURAMENTO');
  const imp = m.etapa('IMPRESSAO', 'Impressão automática', { aoConcluir: 'AVANCAR' });
  const fim = m.etapa('FINALIZACAO');
  m.sequencia(inicio, conf, pesaveis);
  m.ligar(pesaveis, pesagem, 'SIM');
  m.ligar(pesaveis, diverg, 'NAO');
  m.ligar(pesagem, diverg);
  m.ligar(diverg, liberacao, 'SIM');
  m.ligar(diverg, carreg, 'NAO');
  m.ligar(liberacao, carreg);
  m.sequencia(carreg, fat, imp, fim);
  return m;
}

function roteiro(
  id: string,
  nome: string,
  descricao: string,
  tipo: TipoRoteiro,
  status: StatusRoteiro,
  versao: number,
  atualizadoEm: string,
  grafo: { etapas: Etapa[]; conexoes: Conexao[] },
): Roteiro {
  return organizar({
    id,
    nome,
    descricao,
    tipo,
    status,
    versao,
    atualizadoEm,
    demonstracao: true,
    etapas: grafo.etapas,
    conexoes: grafo.conexoes,
  });
}

const modelo = (id: string) => MODELOS.find((m) => m.id === id)!.montar();

export function roteirosDemonstracao(): Roteiro[] {
  return [
    roteiro(
      'demo-expedicao-padrao',
      'Expedição Padrão',
      'Pedidos de entrega: pesagem e liberação só quando o pedido precisa.',
      'SAIDA',
      'PUBLICADO',
      3,
      '2026-10-21T17:12:00.000Z',
      expedicaoPadrao(),
    ),
    roteiro(
      'demo-expedicao-pesagem',
      'Expedição com Pesagem',
      'Mesmo processo, com etapas condicionais em vez de bifurcações.',
      'SAIDA',
      'PUBLICADO',
      2,
      '2026-10-18T12:40:00.000Z',
      modelo('expedicao-completa'),
    ),
    roteiro(
      'demo-expedicao-balcao',
      'Expedição Balcão',
      'Retirada pelo cliente, sem carregamento.',
      'SAIDA',
      'INATIVO',
      1,
      '2026-10-02T13:05:00.000Z',
      modelo('expedicao-simples'),
    ),
    roteiro(
      'demo-recebimento',
      'Recebimento de Mercadorias',
      'Conceitual: entrada de compras com aprovação de divergências.',
      'ENTRADA',
      'RASCUNHO',
      1,
      '2026-10-23T19:30:00.000Z',
      modelo('recebimento'),
    ),
    roteiro(
      'demo-devolucao',
      'Devolução de Cliente',
      'Conceitual: avaliação e destino da mercadoria devolvida.',
      'DEVOLUCAO',
      'RASCUNHO',
      1,
      '2026-10-24T11:15:00.000Z',
      modelo('devolucao-cliente'),
    ),
  ];
}

export function roteiroDeModelo(modeloId: string | null, base: Pick<Roteiro, 'id' | 'nome' | 'descricao' | 'tipo'>): Roteiro {
  const grafo = modeloId
    ? modelo(modeloId)
    : (() => {
        const m = new Montador();
        m.etapa('INICIO');
        return m;
      })();
  return roteiro(base.id, base.nome, base.descricao, base.tipo, 'RASCUNHO', 1, new Date().toISOString(), grafo);
}
