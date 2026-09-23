/**
 * Eventos de feedback da aplicação. Novo evento = nova entrada aqui + uma
 * linha em action-feedback.config.ts — nenhuma lógica de áudio/visual nova.
 */
export type FeedbackEvento =
  // Leitura / identificação
  | 'ITEM_LIDO'
  | 'PRODUTO_ENCONTRADO'
  | 'PRODUTO_NAO_ENCONTRADO'
  | 'PRODUTO_CORRETO'
  | 'PRODUTO_INCORRETO'
  | 'CONTROLE_NECESSARIO'
  // Conferência de item
  | 'ITEM_CONFERIDO'
  | 'TODOS_CONFERIDOS'
  | 'QUANTIDADE_OK'
  | 'QUANTIDADE_DIVERGENTE'
  // Pesagem
  | 'PESAGEM_INICIADA'
  | 'PESAGEM_OK'
  | 'PESO_DIVERGENTE'
  // Divergência / corte
  | 'DIVERGENCIA'
  | 'CORTE_LIBERADO'
  | 'CORTE_NEGADO'
  | 'CORTE_AUTOMATICO'
  // Finalização
  | 'ETAPA_CONCLUIDA'
  | 'FINALIZACAO'
  | 'FINALIZACAO_DIVERGENTE'
  // Sankhya
  | 'ENVIANDO_SANKHYA'
  | 'SUCESSO_SANKHYA'
  | 'ERRO_SANKHYA'
  // Genéricos
  | 'ERRO'
  | 'BLOQUEADO'
  | 'OPERACAO_NAO_PERMITIDA'
  | 'PROCESSANDO'
  | 'CARREGANDO';

/** Arquivos em src/assets/sounds (gerados por tools/sons/gerar-sons.py). */
export type SomId =
  | 'item-lido'
  | 'item-conferido'
  | 'atencao'
  | 'pesagem-ok'
  | 'produto-incorreto'
  | 'erro'
  | 'divergencia'
  | 'corte-liberado'
  | 'finalizacao'
  | 'enviando-sankhya'
  | 'sucesso-sankhya'
  | 'erro-sankhya'
  | 'bloqueado';

export type FeedbackTom = 'info' | 'success' | 'warning' | 'critical';

/**
 * Prioridade do som: um som só interrompe outro de prioridade MENOR ou IGUAL
 * (o mais recente vence). Um alerta nunca é cortado por um "ok" que chega logo depois.
 */
export const Prioridade = {
  DISCRETO: 0,
  CONFIRMACAO: 1,
  ALERTA: 2,
  ERRO: 3,
} as const;
export type Prioridade = (typeof Prioridade)[keyof typeof Prioridade];

export interface FeedbackDef {
  /** `false` = evento mudo (ex.: corte silencioso). */
  som: SomId | false;
  /** 0..1 sobre o nível normalizado dos arquivos. Padrão 1. */
  volume?: number;
  prioridade: Prioridade;
  /** Tom do "flash" rápido nos elementos marcados com [oqFeedbackFlash]. `null` = sem flash. */
  tom: FeedbackTom | null;
  /** Toast global. Omitido = sem toast (a própria tela já mostra o resultado). */
  toast?: { titulo: string; duracaoMs: number };
  /**
   * Bloqueante = exige ação/ciência do operador. Para toast: fica na tela até
   * ser fechado (sem auto-dismiss). Decisões de negócio (divergência, corte)
   * continuam nos modais das próprias telas — o evento só dá o som/visual.
   */
  bloqueante: boolean;
}

export interface FeedbackOpcoes {
  /** Texto do toast (substitui o título padrão do evento). */
  mensagem?: string;
  detalhe?: string;
  /** `false` força silêncio nesta chamada (ex.: corte silencioso). */
  som?: boolean;
  /** `true` força toast mesmo sem toast padrão; `false` suprime (a tela já mostra a mensagem). */
  toast?: boolean;
}

export interface ToastAtivo {
  id: number;
  evento: FeedbackEvento;
  tom: FeedbackTom;
  titulo: string;
  detalhe?: string;
  bloqueante: boolean;
}

export interface UltimoFeedback {
  evento: FeedbackEvento;
  tom: FeedbackTom | null;
  /** Muda a cada disparo — permite reagir ao mesmo evento repetido. */
  seq: number;
}
