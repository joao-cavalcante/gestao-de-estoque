/** Resposta de GET /api/auditoria-pedido/{numero}. Datas = ISO local de Brasília ("2026-10-07T04:42:28"). */
export interface AuditoriaCabecalho {
  nunota: number;
  numnota: number | null;
  codtipoper: number | null;
  top: string | null;
  tipmov: string | null;
  statusNota: string | null;
  ordemCarga: number | null;
  codparc: number | null;
  parceiro: string | null;
  codvend: number | null;
  vendedor: string | null;
  valor: number | null;
  incluidoEm: string | null;
  alteradoEm: string | null;
  alteradoPor?: string | null;
  nuconfAtual: number | null;
}

export interface AuditoriaItem {
  sequencia: number;
  codprod: number;
  produto: string;
  unidade: string | null;
  qtdNegociada: number | null;
  /** Na unidade de exibição (comercial do pedido; kg quando a unidade padrão é KG). */
  qtdConferida: number | null;
  /** Unidade padrão do produto e as quantidades nela — só quando difere da exibida (ex.: 15 FD = 75 PT). */
  unidadePadrao: string | null;
  qtdNegociadaPadrao: number | null;
  qtdConferidaPadrao: number | null;
  valorUnitario: number | null;
  valorTotal: number | null;
}

export interface AuditoriaNotaGerada {
  nunota: number;
  numnota: number | null;
  tipmov: string | null;
  top: string | null;
  valor: number | null;
  geradaEm: string | null;
}

export type OrigemEvento = 'WMS' | 'SANKHYA';
export type NivelEvento = 'info' | 'sucesso' | 'alerta' | 'erro';

export interface AuditoriaEvento {
  quando: string;
  origem: OrigemEvento;
  tipo: string;
  titulo: string;
  detalhe: string | null;
  usuario: string | null;
  nivel: NivelEvento;
}

export interface AuditoriaPedido {
  cabecalho: AuditoriaCabecalho;
  itens: AuditoriaItem[];
  notasGeradas: AuditoriaNotaGerada[];
  eventos: AuditoriaEvento[];
  avisos: string[];
}
