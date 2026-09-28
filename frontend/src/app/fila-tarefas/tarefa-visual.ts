import { OqIconName } from '../shared/icons/oq-icon.component';
import { StatusTarefa, Tarefa, TIPOS_SEPARACAO } from './tarefa.model';

/**
 * Apresentação de uma tarefa da fila — status (ícone/rótulo/cor) e etapas da
 * conferência por etapa. Compartilhado entre o card (modo CARDS) e a linha da
 * tabela (modo LISTA), pra os dois modos mostrarem exatamente a mesma coisa.
 */
export interface StatusVisual {
  icone: OqIconName;
  label: string;
  gira: boolean;
  /** Modificador da etiqueta colorida (.oq-status-pin--{tom}, styles.scss). */
  tom: 'aguardando' | 'andamento' | 'corte' | 'concluido';
}

const STATUS_VISUAL: Record<StatusTarefa, StatusVisual> = {
  aguardando: { icone: 'circle', label: 'AGUARDANDO CONFERÊNCIA', gira: false, tom: 'aguardando' },
  andamento: { icone: 'gear', label: 'EM ANDAMENTO', gira: true, tom: 'andamento' },
  aguardando_corte: { icone: 'circle-alert', label: 'AGUARDANDO CORTE', gira: false, tom: 'corte' },
  concluido: { icone: 'check', label: 'CONCLUÍDO', gira: false, tom: 'concluido' },
};

export function statusVisual(tarefa: Tarefa): StatusVisual {
  const visual = STATUS_VISUAL[tarefa.status];
  // "aguardando_recontagem" cai no mesmo bucket 'aguardando' de uma nota
  // nunca conferida (ver STATUS_MAP em conferencias.service.ts), mas pro
  // operador são situações bem diferentes — uma já foi conferida antes e
  // voltou por divergência/item negado, a outra nunca foi aberta.
  if (tarefa.statusOperacional === 'aguardando_recontagem') {
    return { ...visual, label: 'AGUARDANDO RECONTAGEM' };
  }
  return visual;
}

export interface EtapaVisual {
  tipo: number;
  label: string;
  icone: OqIconName;
  concluida: boolean;
  divergente: boolean;
  emAndamento: boolean;
  progresso: string;
  botao: string;
}

/** Etapas da conferência por etapa (V29) — com rótulo/ícone/progresso resolvidos. Vazio = nota não segmentada. */
export function etapasVisiveis(tarefa: Tarefa): EtapaVisual[] {
  return (tarefa.etapas ?? [])
    .map((e) => {
      const cat = TIPOS_SEPARACAO.find((t) => t.id === e.tipo);
      const concluida = e.status === 'C';
      const emAndamento = !concluida && e.conferidos > 0;
      return {
        tipo: e.tipo,
        label: cat?.label ?? `Tipo ${e.tipo}`,
        icone: (cat?.icone ?? 'box') as OqIconName,
        concluida,
        divergente: concluida && !!e.divergente,
        emAndamento,
        progresso: e.total > 0 ? `${e.conferidos}/${e.total}` : '',
        botao: concluida ? (e.divergente ? 'Concluída com divergência' : 'Concluída') : emAndamento ? 'Continuar' : 'Conferir',
      };
    })
    .sort((a, b) => a.tipo - b.tipo);
}
