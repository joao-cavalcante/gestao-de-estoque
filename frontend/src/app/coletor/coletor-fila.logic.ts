/**
 * Coletor — regras da FILA (18/11/2026). Só decide o que MOSTRAR e o que ABRIR a partir da mesma fila que a Torre
 * recebe (GET /api/tarefas, que já filtra tenant + TOPs do usuário e usa o mesmo critério da fila nativa do Sankhya).
 * Nenhuma regra de negócio nova: quem pode conferir é a mesma fase 'conferir' da Torre (faseTarefa), e a abertura
 * vai para a MESMA tela/serviços de conferência — o backend continua validando tudo.
 *
 * Busca (referência: coletor nativo, ConferenciaSPBean.listarConferencias — igualdade em Nro. único, Nro. nota e
 * Chave NF-e):
 * - número → casa Nº único (NUNOTA) OU Nº da nota (NUMNOTA). Mais de um documento = o operador escolhe (nunca abre
 *   no escuro).
 * - 44 dígitos (chave NF-e) → a fila ainda NÃO tem TGFCAB.CHAVENFE; usa o nº da NF contido na chave (posições 26–34)
 *   só entre documentos que já são nota (V/C) e SEMPRE pede confirmação, mesmo com um candidato só.
 */
import { FasePedido, Tarefa, faseTarefa, jaENota } from '../fila-tarefas/tarefa.model';

export type OrdemColetor = 'antigos' | 'recentes' | 'modalidade' | 'numero';

export const ORDENS_COLETOR: { id: OrdemColetor; rotulo: string }[] = [
  { id: 'antigos', rotulo: 'Mais antigos primeiro' },
  { id: 'recentes', rotulo: 'Mais recentes primeiro' },
  { id: 'modalidade', rotulo: 'Express e Retira primeiro' },
  { id: 'numero', rotulo: 'Nº único' },
];

export type ResultadoBusca =
  | { tipo: 'vazio' }
  | { tipo: 'abrir'; tarefa: Tarefa; por: 'nunota' | 'numnota' }
  | { tipo: 'escolher'; candidatos: Tarefa[]; motivo: string }
  | { tipo: 'bloqueado'; tarefa: Tarefa; titulo: string; mensagem: string; estado: 'bloqueado' | 'concluido' }
  | { tipo: 'nao-encontrado'; codigo: string; mensagem: string };

/** Só dígitos (leitor pode mandar espaço, prefixo de símbolo etc.). */
export function normalizarCodigo(bruto: string): string {
  return (bruto ?? '').replace(/\D+/g, '');
}

/** Documento que o coletor pode abrir para conferir — exatamente a fase 'conferir' da Torre. */
export function podeConferir(t: Tarefa): boolean {
  return faseTarefa(t) === 'conferir';
}

/** Fila do coletor = só o que está para conferir (o resto é corte, carregamento, fechamento ou nota — outra tela). */
export function filaDoColetor(tarefas: Tarefa[]): Tarefa[] {
  return tarefas.filter(podeConferir);
}

/** Nº da NF contido na chave de acesso (layout NF-e: posições 26–34, 9 dígitos). */
export function numeroNotaDaChave(chave44: string): number | null {
  if (!/^\d{44}$/.test(chave44)) return null;
  const n = Number(chave44.slice(25, 34));
  return Number.isFinite(n) && n > 0 ? n : null;
}

const PROXIMO_PASSO: Partial<Record<FasePedido, string>> = {
  carregar: 'Próximo passo: carregamento',
  fechamento: 'Próximo passo: fechamento da OC',
  nota: 'Próximo passo: gerar a nota',
  pronto: 'Processo concluído',
};

/** Documento achado, mas fora da fase de conferência: explica o porquê em texto (nunca abre). */
function explicarBloqueio(t: Tarefa): ResultadoBusca {
  const fase = faseTarefa(t);
  if (fase === 'corte') {
    return {
      tipo: 'bloqueado',
      tarefa: t,
      estado: 'bloqueado',
      titulo: 'Aguardando liberação de corte',
      mensagem: 'A conferência foi enviada com divergência. O supervisor libera na tela Liberação de Corte.',
    };
  }
  const oc = t.ordemCarga != null ? ` (OC ${t.ordemCarga})` : '';
  return {
    tipo: 'bloqueado',
    tarefa: t,
    estado: 'concluido',
    titulo: 'Conferência já concluída',
    mensagem: `${PROXIMO_PASSO[fase] ?? 'Sem conferência pendente'}${fase === 'carregar' || fase === 'fechamento' ? oc : ''}.`,
  };
}

const MSG_NAO_ENCONTRADO =
  'Não está na sua fila de conferência. Pode não existir, ainda não estar liberado, já ter saído da fila ou ser de um tipo de operação fora do seu acesso.';

/**
 * Resolve uma leitura/digitação na fila (tarefas = TODA a fila da API, não só a do coletor — para explicar
 * bloqueio/concluído em vez de dizer "não encontrado").
 */
export function resolverBusca(entrada: string, tarefas: Tarefa[]): ResultadoBusca {
  const codigo = normalizarCodigo(entrada);
  if (!codigo) return { tipo: 'vazio' };

  if (codigo.length === 44) {
    const nf = numeroNotaDaChave(codigo);
    const candidatos = nf == null ? [] : tarefas.filter((t) => jaENota(t.tipMov) && Number(t.nf) === nf);
    if (candidatos.length === 0) return { tipo: 'nao-encontrado', codigo, mensagem: MSG_NAO_ENCONTRADO };
    return {
      tipo: 'escolher',
      candidatos,
      motivo: `Chave NF-e: confirme a nota ${nf} antes de abrir.`,
    };
  }

  // Número de até 18 dígitos — compara como número (o leitor pode mandar zeros à esquerda).
  if (codigo.length > 18) return { tipo: 'nao-encontrado', codigo, mensagem: MSG_NAO_ENCONTRADO };
  const n = Number(codigo);
  const porUnico = tarefas.filter((t) => Number(t.numeroUnico) === n);
  const porNota = tarefas.filter((t) => t.nf !== '—' && Number(t.nf) === n && !porUnico.includes(t));
  const todos = [...porUnico, ...porNota];

  if (todos.length === 0) return { tipo: 'nao-encontrado', codigo, mensagem: MSG_NAO_ENCONTRADO };
  if (todos.length > 1) {
    return { tipo: 'escolher', candidatos: todos, motivo: `${todos.length} documentos com o número ${n}. Escolha qual abrir.` };
  }
  const t = todos[0];
  if (!podeConferir(t)) return explicarBloqueio(t);
  return { tipo: 'abrir', tarefa: t, por: porUnico.length ? 'nunota' : 'numnota' };
}

/** Explica (sem abrir) um candidato escolhido na lista de ambiguidade que não está para conferir. */
export function resolverEscolha(t: Tarefa): ResultadoBusca {
  return podeConferir(t) ? { tipo: 'abrir', tarefa: t, por: 'nunota' } : explicarBloqueio(t);
}

/** Data de referência do processo: AD_DHNEG (data/hora, Negri) quando houver; senão DTNEG (dd/mm/aaaa). */
export function dataReferencia(t: Tarefa): number {
  if (t.pedidoEm instanceof Date && !Number.isNaN(t.pedidoEm.getTime())) return t.pedidoEm.getTime();
  const m = /^(\d{2})\/(\d{2})\/(\d{4})/.exec((t.data ?? '').trim());
  return m ? new Date(Number(m[3]), Number(m[2]) - 1, Number(m[1])).getTime() : Number.MAX_SAFE_INTEGER;
}

export function ordenarFila(tarefas: Tarefa[], ordem: OrdemColetor): Tarefa[] {
  const porData = (a: Tarefa, b: Tarefa) => dataReferencia(a) - dataReferencia(b) || Number(a.numeroUnico) - Number(b.numeroUnico);
  const copia = [...tarefas];
  switch (ordem) {
    case 'recentes':
      return copia.sort((a, b) => -porData(a, b));
    case 'modalidade': {
      const peso = (t: Tarefa) => (t.express ? 0 : t.retira ? 1 : 2);
      return copia.sort((a, b) => peso(a) - peso(b) || porData(a, b));
    }
    case 'numero':
      return copia.sort((a, b) => Number(a.numeroUnico) - Number(b.numeroUnico));
    default:
      return copia.sort(porData);
  }
}

/** Status em texto (nunca só cor). */
export function rotuloStatusColetor(t: Tarefa): { texto: string; tom: 'neutro' | 'andamento' | 'atencao' } {
  switch (t.statusOperacional) {
    case 'aguardando_liberacao':
      return { texto: 'Aguardando liberação', tom: 'atencao' };
    case 'andamento': {
      const a = t.andamento;
      const prog = a ? ` ${a.conferidos}/${a.total}` : '';
      const quem = a?.conferente ? ` · ${a.conferente}` : '';
      return { texto: `Em conferência${prog}${quem}`, tom: 'andamento' };
    }
    case 'aguardando_recontagem':
      return { texto: 'Recontagem', tom: 'atencao' };
    case 'recontagem_andamento':
      return { texto: 'Recontagem em andamento', tom: 'andamento' };
    default:
      return { texto: 'Aguardando conferência', tom: 'neutro' };
  }
}

export function paginar<T>(lista: T[], pagina: number, tamanho: number): { itens: T[]; pagina: number; total: number } {
  const total = Math.max(1, Math.ceil(lista.length / tamanho));
  const p = Math.min(Math.max(1, pagina), total);
  return { itens: lista.slice((p - 1) * tamanho, p * tamanho), pagina: p, total };
}
