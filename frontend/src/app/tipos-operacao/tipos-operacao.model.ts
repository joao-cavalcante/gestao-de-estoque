/**
 * Espelho local dos Tipos de Operação — ver db/migrations/V16 e V17. Derivado
 * do que a Fila de Tarefas já sincroniza (não de uma consulta própria ao
 * Sankhya — a entidade TipoOperacao isolada é incompleta). Só exibição.
 */
export interface TipoOperacao {
  id: string;
  codtop: number;
  descricao: string;
  nucco: number | null;
  localAtualizadoEm: string;
}

export interface SincronizarTipoOperacaoResponse {
  ok: boolean;
  totalAtualizado: number;
}
