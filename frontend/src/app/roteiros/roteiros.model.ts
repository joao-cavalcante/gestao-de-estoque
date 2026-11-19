import { OqIconName } from '../shared/icons/oq-icon.component';

/**
 * PROTÓTIPO — Roteiros Operacionais (25/10/2026).
 *
 * Tudo nesta pasta é demonstração de interface: nada aqui é lido pelo backend, nada altera a
 * conferência, o faturamento ou o Sankhya. Os roteiros ficam só no localStorage do navegador
 * (roteiros-demo.service.ts). Os blocos apontam para os módulos reais da aplicação (campo `hoje`)
 * só como referência; o motor que executaria um roteiro não existe.
 */

export type TipoRoteiro = 'SAIDA' | 'ENTRADA' | 'DEVOLUCAO';
export type StatusRoteiro = 'PUBLICADO' | 'RASCUNHO' | 'INATIVO';
export type CategoriaBloco = 'OPERACAO' | 'CONTROLE' | 'SISTEMA';
/** EXISTENTE = o módulo existe hoje na aplicação; CONCEITUAL = só existe neste protótipo. */
export type SituacaoBloco = 'EXISTENTE' | 'CONCEITUAL';

export type TipoBloco =
  | 'INICIO'
  | 'CONFERENCIA'
  | 'PESAGEM'
  | 'VOLUMES'
  | 'LIBERACAO_CORTE'
  | 'CARREGAMENTO'
  | 'FATURAMENTO'
  | 'IMPRESSAO'
  | 'IDENTIFICACAO'
  | 'AVALIACAO'
  | 'DESTINACAO'
  | 'CONDICAO'
  | 'APROVACAO'
  | 'VALIDACAO'
  | 'FINALIZACAO'
  | 'INTEGRACAO'
  | 'NOTIFICACAO';

export type Execucao = 'OBRIGATORIA' | 'CONDICIONAL' | 'OPCIONAL';
export type AoConcluir = 'AVANCAR' | 'AGUARDAR';
export type CondicaoId = 'PESAVEIS' | 'DIVERGENCIAS' | 'ENTREGA' | 'ORDEM_CARGA' | 'AVARIA';
export type Ramo = 'SIM' | 'NAO';
export type AcaoDivergencia = 'LIBERACAO' | 'BLOQUEAR' | 'PERMITIR';
export type Responsavel = '' | 'LIBERADOR' | 'SUPERVISOR' | 'ADMINISTRADOR';

export interface ConfigEtapa {
  execucao: Execucao;
  /** Só com execucao CONDICIONAL. */
  condicao: CondicaoId | null;
  aoConcluir: AoConcluir;
  descricao: string;
  /** Bloco Condição: a pergunta que divide o caminho. */
  pergunta?: CondicaoId | null;
  /** Conferência. */
  divergencia?: AcaoDivergencia;
  porEtapas?: boolean;
  /** Impressão. */
  documentos?: { nota: boolean; romaneio: boolean; etiquetas: boolean };
  /** Aprovação / Liberação de corte: quem decide. Avançado: perfil exigido para executar. */
  responsavel?: Responsavel;
  perfil?: Responsavel;
}

export interface Etapa {
  id: string;
  tipo: TipoBloco;
  nome: string;
  /** Canto superior esquerdo, em coordenadas do canvas. */
  x: number;
  y: number;
  config: ConfigEtapa;
}

export interface Conexao {
  id: string;
  de: string;
  para: string;
  /** Só nas saídas de um bloco Condição. */
  ramo: Ramo | null;
}

export interface Roteiro {
  id: string;
  nome: string;
  descricao: string;
  tipo: TipoRoteiro;
  status: StatusRoteiro;
  versao: number;
  atualizadoEm: string;
  /** Sempre true no protótipo — a tela mostra o selo "Demonstração". */
  demonstracao: true;
  etapas: Etapa[];
  conexoes: Conexao[];
}

export interface DefinicaoBloco {
  tipo: TipoBloco;
  rotulo: string;
  categoria: CategoriaBloco;
  situacao: SituacaoBloco;
  icone: OqIconName;
  resumo: string;
  /** Onde a funcionalidade vive hoje na aplicação (só blocos EXISTENTE). */
  hoje?: string;
  /** Etapas que precisam vir antes (dependência técnica dos módulos atuais). */
  requer?: TipoBloco[];
  /** Texto que o operador veria na simulação. */
  instrucao: string;
}

export const ROTULO_TIPO: Record<TipoRoteiro, string> = {
  SAIDA: 'Saída',
  ENTRADA: 'Entrada',
  DEVOLUCAO: 'Devolução',
};

export const ROTULO_STATUS: Record<StatusRoteiro, string> = {
  PUBLICADO: 'Publicado',
  RASCUNHO: 'Rascunho',
  INATIVO: 'Inativo',
};

export const ROTULO_CATEGORIA: Record<CategoriaBloco, string> = {
  OPERACAO: 'Operação',
  CONTROLE: 'Controle',
  SISTEMA: 'Sistema',
};

/** Linguagem operacional — nunca a expressão técnica. */
export const CONDICOES: Record<CondicaoId, { pergunta: string; quando: string; sim: string; nao: string }> = {
  PESAVEIS: {
    pergunta: 'O pedido possui produtos pesáveis?',
    quando: 'quando o pedido possuir produtos pesáveis',
    sim: 'Com produtos pesáveis',
    nao: 'Sem produtos pesáveis',
  },
  DIVERGENCIAS: {
    pergunta: 'Existem divergências pendentes?',
    quando: 'quando existirem divergências pendentes',
    sim: 'Com divergência',
    nao: 'Sem divergência',
  },
  ENTREGA: {
    pergunta: 'O pedido é de entrega?',
    quando: 'quando o pedido for de entrega',
    sim: 'Pedido de entrega',
    nao: 'Retirada no balcão',
  },
  ORDEM_CARGA: {
    pergunta: 'O pedido está em uma Ordem de Carga?',
    quando: 'quando o pedido estiver em uma Ordem de Carga',
    sim: 'Em Ordem de Carga',
    nao: 'Sem Ordem de Carga',
  },
  AVARIA: {
    pergunta: 'Há mercadoria avariada?',
    quando: 'quando houver mercadoria avariada',
    sim: 'Com avaria',
    nao: 'Sem avaria',
  },
};

export const ROTULO_RESPONSAVEL: Record<Exclude<Responsavel, ''>, string> = {
  LIBERADOR: 'Liberador (login no Sankhya)',
  SUPERVISOR: 'Supervisor da expedição',
  ADMINISTRADOR: 'Administrador do WMS',
};

export const BLOCOS: DefinicaoBloco[] = [
  // ─── Operação ─────────────────────────────────────────────
  {
    tipo: 'CONFERENCIA',
    rotulo: 'Conferência',
    categoria: 'OPERACAO',
    situacao: 'EXISTENTE',
    icone: 'list-check',
    resumo: 'Conferir itens e quantidades do pedido.',
    hoje: 'Fila de Conferência → Conferir (por pedido ou por OC), inclusive compras.',
    instrucao: 'Confira os itens do pedido e informe as quantidades.',
  },
  {
    tipo: 'PESAGEM',
    rotulo: 'Pesagem',
    categoria: 'OPERACAO',
    situacao: 'EXISTENTE',
    icone: 'balanca',
    resumo: 'Pesar os produtos pesáveis na balança.',
    hoje: 'Dentro da conferência (pop-up de pesagem e etapa Pesáveis); não é uma tela separada.',
    requer: ['CONFERENCIA'],
    instrucao: 'Pese os produtos pesáveis na balança da estação.',
  },
  {
    tipo: 'VOLUMES',
    rotulo: 'Formação de volumes',
    categoria: 'OPERACAO',
    situacao: 'EXISTENTE',
    icone: 'box',
    resumo: 'Informar caixas e volumes e imprimir etiquetas.',
    hoje: 'Dentro da conferência (contador de volumes) e Impressão de Etiquetas.',
    requer: ['CONFERENCIA'],
    instrucao: 'Informe a quantidade de volumes e cole as etiquetas.',
  },
  {
    tipo: 'LIBERACAO_CORTE',
    rotulo: 'Liberação de corte',
    categoria: 'OPERACAO',
    situacao: 'EXISTENTE',
    icone: 'ajuste',
    resumo: 'Liberar as divergências antes de seguir.',
    hoje: 'Tela Liberação de Corte (login do liberador) e auto-liberação de pesáveis na tolerância.',
    requer: ['CONFERENCIA'],
    instrucao: 'Aguardando um liberador aprovar as divergências do pedido.',
  },
  {
    tipo: 'CARREGAMENTO',
    rotulo: 'Carregamento',
    categoria: 'OPERACAO',
    situacao: 'EXISTENTE',
    icone: 'entrega',
    resumo: 'Confirmar os volumes no veículo.',
    hoje: 'Checklist de carregamento da Ordem de Carga.',
    requer: ['CONFERENCIA'],
    instrucao: 'Marque cada volume carregado no veículo.',
  },
  {
    tipo: 'FATURAMENTO',
    rotulo: 'Faturamento',
    categoria: 'OPERACAO',
    situacao: 'EXISTENTE',
    icone: 'receipt',
    resumo: 'Gerar e confirmar a nota fiscal.',
    hoje: 'Fechar OC / Gerar nota (faturar na TOP de destino e confirmar a nota).',
    requer: ['CONFERENCIA'],
    instrucao: 'Gerar a nota fiscal do pedido.',
  },
  {
    tipo: 'IMPRESSAO',
    rotulo: 'Impressão',
    categoria: 'OPERACAO',
    situacao: 'EXISTENTE',
    icone: 'impressora',
    resumo: 'Imprimir nota, romaneio e etiquetas.',
    hoje: 'Impressão automática de NF e romaneio ao fechar a OC (agente da balança).',
    instrucao: 'Imprimir os documentos do pedido.',
  },
  {
    tipo: 'IDENTIFICACAO',
    rotulo: 'Identificação',
    categoria: 'OPERACAO',
    situacao: 'CONCEITUAL',
    icone: 'barcode',
    resumo: 'Identificar a nota, o fornecedor ou o cliente da mercadoria.',
    instrucao: 'Informe a nota ou o documento que acompanha a mercadoria.',
  },
  {
    tipo: 'AVALIACAO',
    rotulo: 'Avaliação',
    categoria: 'OPERACAO',
    situacao: 'CONCEITUAL',
    icone: 'search',
    resumo: 'Avaliar o estado da mercadoria devolvida.',
    instrucao: 'Avalie o estado de cada item devolvido.',
  },
  {
    tipo: 'DESTINACAO',
    rotulo: 'Destinação',
    categoria: 'OPERACAO',
    situacao: 'CONCEITUAL',
    icone: 'building',
    resumo: 'Definir para onde a mercadoria vai (estoque, avaria, descarte).',
    instrucao: 'Escolha o destino de cada item.',
  },
  // ─── Controle ─────────────────────────────────────────────
  {
    tipo: 'CONDICAO',
    rotulo: 'Condição',
    categoria: 'CONTROLE',
    situacao: 'CONCEITUAL',
    icone: 'filter',
    resumo: 'Dividir o caminho com uma pergunta de sim ou não.',
    instrucao: 'Verificação automática.',
  },
  {
    tipo: 'APROVACAO',
    rotulo: 'Aprovação',
    categoria: 'CONTROLE',
    situacao: 'CONCEITUAL',
    icone: 'handshake',
    resumo: 'Pedir o aval de um responsável antes de seguir.',
    instrucao: 'Aguardando aprovação do responsável.',
  },
  {
    tipo: 'VALIDACAO',
    rotulo: 'Validação',
    categoria: 'CONTROLE',
    situacao: 'CONCEITUAL',
    icone: 'check',
    resumo: 'Conferir automaticamente se tudo está correto antes de seguir.',
    instrucao: 'Verificando se o pedido está pronto para seguir.',
  },
  {
    tipo: 'FINALIZACAO',
    rotulo: 'Finalização',
    categoria: 'CONTROLE',
    situacao: 'EXISTENTE',
    icone: 'circle-filled',
    resumo: 'Encerrar o roteiro.',
    hoje: 'Finalizar conferência / pedido concluído na Fila.',
    instrucao: 'Processo concluído.',
  },
  // ─── Sistema ──────────────────────────────────────────────
  {
    tipo: 'INICIO',
    rotulo: 'Início',
    categoria: 'SISTEMA',
    situacao: 'EXISTENTE',
    icone: 'circle',
    resumo: 'Ponto de partida do roteiro.',
    hoje: 'Pedido liberado na Fila de Conferência.',
    instrucao: 'Pedido recebido na fila.',
  },
  {
    tipo: 'INTEGRACAO',
    rotulo: 'Integração',
    categoria: 'SISTEMA',
    situacao: 'CONCEITUAL',
    icone: 'sync',
    resumo: 'Enviar ou consultar informações em outro sistema.',
    instrucao: 'Sincronizando informações (simulado).',
  },
  {
    tipo: 'NOTIFICACAO',
    rotulo: 'Notificação',
    categoria: 'SISTEMA',
    situacao: 'CONCEITUAL',
    icone: 'circle-alert',
    resumo: 'Avisar alguém sobre o andamento.',
    instrucao: 'Aviso enviado ao responsável (simulado).',
  },
];

const POR_TIPO = new Map(BLOCOS.map((b) => [b.tipo, b]));

export function definicao(tipo: TipoBloco): DefinicaoBloco {
  return POR_TIPO.get(tipo)!;
}

/** Início, Finalização e Condição não têm execução/conclusão configuráveis. */
export function ehEstrutural(tipo: TipoBloco): boolean {
  return tipo === 'INICIO' || tipo === 'FINALIZACAO' || tipo === 'CONDICAO';
}

export function configPadrao(tipo: TipoBloco): ConfigEtapa {
  const base: ConfigEtapa = { execucao: 'OBRIGATORIA', condicao: null, aoConcluir: 'AGUARDAR', descricao: '' };
  switch (tipo) {
    case 'CONDICAO':
      return { ...base, pergunta: null };
    case 'CONFERENCIA':
      return { ...base, divergencia: 'LIBERACAO', porEtapas: false };
    case 'IMPRESSAO':
      return { ...base, aoConcluir: 'AVANCAR', documentos: { nota: true, romaneio: true, etiquetas: false } };
    case 'APROVACAO':
      return { ...base, responsavel: '' };
    case 'LIBERACAO_CORTE':
      return { ...base, responsavel: 'LIBERADOR' };
    case 'FATURAMENTO':
    case 'INTEGRACAO':
    case 'NOTIFICACAO':
    case 'VALIDACAO':
      return { ...base, aoConcluir: 'AVANCAR' };
    default:
      return base;
  }
}

export function novoId(prefixo: string): string {
  return `${prefixo}-${Math.random().toString(36).slice(2, 9)}`;
}

/** Tamanho dos blocos no canvas (coordenadas do mundo). */
export function tamanhoEtapa(tipo: TipoBloco): { w: number; h: number } {
  if (tipo === 'INICIO' || tipo === 'FINALIZACAO') return { w: 168, h: 44 };
  if (tipo === 'CONDICAO') return { w: 216, h: 64 };
  return { w: 216, h: 64 };
}

/** Quantas etapas o roteiro tem, sem contar Início e Finalização. */
export function contarEtapas(r: Roteiro): number {
  return r.etapas.filter((e) => e.tipo !== 'INICIO' && e.tipo !== 'FINALIZACAO').length;
}
