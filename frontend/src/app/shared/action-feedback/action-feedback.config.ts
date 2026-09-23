import { FeedbackDef, FeedbackEvento, Prioridade, SomId } from './action-feedback.types';

/**
 * Mapa ÚNICO evento → som/visual/comportamento. Para mudar o feedback de uma
 * ação, mude aqui — nunca na tela.
 *
 * Toast só onde a tela ainda NÃO mostra o resultado (painel de última leitura,
 * modais e rodapé de progresso já existentes continuam sendo o visual principal).
 */
export const FEEDBACK_CONFIG: Record<FeedbackEvento, FeedbackDef> = {
  // ─── Leitura / identificação ───────────────────────────────────────────
  // Tocado no ato do bipe (antes da resposta do backend): feedback imediato.
  ITEM_LIDO: { som: 'item-lido', volume: 0.6, prioridade: Prioridade.DISCRETO, tom: 'info', bloqueante: false },
  PRODUTO_ENCONTRADO: { som: false, prioridade: Prioridade.DISCRETO, tom: 'info', bloqueante: false },
  PRODUTO_NAO_ENCONTRADO: { som: 'erro', prioridade: Prioridade.ERRO, tom: 'critical', bloqueante: false },
  PRODUTO_CORRETO: { som: false, prioridade: Prioridade.DISCRETO, tom: 'success', bloqueante: false },
  PRODUTO_INCORRETO: { som: 'produto-incorreto', prioridade: Prioridade.ALERTA, tom: 'critical', bloqueante: false },
  CONTROLE_NECESSARIO: { som: 'atencao', prioridade: Prioridade.ALERTA, tom: 'warning', bloqueante: false },

  // ─── Conferência de item ───────────────────────────────────────────────
  ITEM_CONFERIDO: { som: 'item-conferido', prioridade: Prioridade.CONFIRMACAO, tom: 'success', bloqueante: false },
  QUANTIDADE_OK: { som: 'item-conferido', prioridade: Prioridade.CONFIRMACAO, tom: 'success', bloqueante: false },
  TODOS_CONFERIDOS: { som: 'finalizacao', prioridade: Prioridade.ALERTA, tom: 'success', bloqueante: false },
  QUANTIDADE_DIVERGENTE: { som: 'divergencia', prioridade: Prioridade.ALERTA, tom: 'critical', bloqueante: false },

  // ─── Pesagem ───────────────────────────────────────────────────────────
  PESAGEM_INICIADA: { som: false, prioridade: Prioridade.DISCRETO, tom: null, bloqueante: false },
  PESAGEM_OK: { som: 'pesagem-ok', prioridade: Prioridade.CONFIRMACAO, tom: 'success', bloqueante: false },
  PESO_DIVERGENTE: { som: 'divergencia', prioridade: Prioridade.ALERTA, tom: 'critical', bloqueante: false },

  // ─── Divergência / corte ───────────────────────────────────────────────
  DIVERGENCIA: { som: 'divergencia', prioridade: Prioridade.ALERTA, tom: 'warning', bloqueante: true },
  CORTE_LIBERADO: { som: 'corte-liberado', prioridade: Prioridade.ALERTA, tom: 'success', bloqueante: false },
  CORTE_NEGADO: { som: 'atencao', prioridade: Prioridade.ALERTA, tom: 'warning', bloqueante: false },
  // Corte silencioso (pesável dentro da tolerância, decidido no backend): MUDO por regra operacional.
  CORTE_AUTOMATICO: { som: false, prioridade: Prioridade.DISCRETO, tom: null, bloqueante: false },

  // ─── Finalização ───────────────────────────────────────────────────────
  ETAPA_CONCLUIDA: { som: 'sucesso-sankhya', prioridade: Prioridade.ALERTA, tom: 'success', bloqueante: false },
  FINALIZACAO: { som: 'finalizacao', prioridade: Prioridade.ALERTA, tom: 'success', bloqueante: false },
  FINALIZACAO_DIVERGENTE: { som: 'divergencia', prioridade: Prioridade.ALERTA, tom: 'critical', bloqueante: true },

  // ─── Sankhya ───────────────────────────────────────────────────────────
  // Só um pulso discreto — o progresso ("Enviando itens ao Sankhya… x de y") já aparece no rodapé.
  ENVIANDO_SANKHYA: { som: 'enviando-sankhya', volume: 0.35, prioridade: Prioridade.DISCRETO, tom: null, bloqueante: false },
  SUCESSO_SANKHYA: { som: 'sucesso-sankhya', prioridade: Prioridade.ALERTA, tom: 'success', bloqueante: false },
  ERRO_SANKHYA: {
    som: 'erro-sankhya',
    prioridade: Prioridade.ERRO,
    tom: 'critical',
    toast: { titulo: 'Falha na comunicação com o Sankhya', duracaoMs: 0 },
    bloqueante: true,
  },

  // ─── Genéricos ─────────────────────────────────────────────────────────
  ERRO: { som: 'erro', prioridade: Prioridade.ERRO, tom: 'critical', toast: { titulo: 'Erro', duracaoMs: 4500 }, bloqueante: false },
  BLOQUEADO: { som: 'bloqueado', prioridade: Prioridade.ERRO, tom: 'critical', bloqueante: true },
  OPERACAO_NAO_PERMITIDA: { som: 'bloqueado', prioridade: Prioridade.ERRO, tom: 'critical', bloqueante: false },
  // Spinners/skeletons já existentes são o visual — eventos ficam registrados pra uso futuro.
  PROCESSANDO: { som: false, prioridade: Prioridade.DISCRETO, tom: null, bloqueante: false },
  CARREGANDO: { som: false, prioridade: Prioridade.DISCRETO, tom: null, bloqueante: false },
};

/** Sons efetivamente usados pela config — só esses são pré-carregados. */
export const SONS_USADOS: SomId[] = [
  ...new Set(Object.values(FEEDBACK_CONFIG).flatMap((d) => (d.som ? [d.som] : []))),
];

/** Mesmo evento dentro desta janela não re-dispara (leitor repetindo/duplo Enter). */
export const JANELA_REPETICAO_MS = 90;
/** Toasts simultâneos no máximo — o mais antigo sai. */
export const MAX_TOASTS = 3;
