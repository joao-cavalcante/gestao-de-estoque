import { Modalidade } from '../shared/oq-modalidade-pins/oq-modalidade-pins.component';
/**
 * DTO cru devolvido por GET /api/mapa-separacao/{ordemCarga} (MapaSeparacaoRoutes.kt) —
 * consulta AO VIVO no Sankhya a cada chamada, sem mirror local (ver MapaSeparacaoService).
 * Números vêm como string (já formatados com ponto decimal e 3 casas pelo backend) — a
 * formatação pt-BR de exibição é feita aqui (ver formatarQtd/formatarPeso).
 */
export interface MapaSeparacaoDto {
  /** null = mapa S/ ORDEM DE CARGA (um pedido só — ver `nunota`). */
  ordemCarga: number | null;
  codVeiculo: number | null;
  placa: string | null;
  modeloVeiculo: string | null;
  codParcMotorista: number | null;
  nomeMotorista: string | null;
  pesoMaxOc: string | null;
  /** Nº Único de cada pedido do mapa — o Pedido de Venda de cada um é impresso junto. */
  nunotasPedidos?: number[];
  /** TGFORD.DTALTER — última alteração da OC ("dd/MM/yyyy HH:mm:ss"); null no mapa S/ OC. */
  ultimaAlteracaoOc?: string | null;
  totalPedidos: number;
  quantidadeTotal: string;
  pesoTotal: string;
  /** Seco, congelado (e sem classificação) somados sobre a OC inteira — uma folha por categoria, todos os clientes. */
  consolidado: CategoriaSeparacaoDto[];
  /** Refrigerado, uma folha por parceiro (soma só entre os pedidos do mesmo parceiro). */
  porParceiro: ParceiroSeparacaoDto[];
  /** Mapa S/ ORDEM DE CARGA: um mapa por Número Único, nunca consolidado com outro pedido. */
  semOrdemCarga?: boolean;
  nunota?: number | null;
  numNota?: number | null;
  codParc?: number | null;
  nomeParceiro?: string | null;
  /** Mapa S/ OC: modalidade do pedido (AD_EXPRESS / AD_RETIRA / AD_ENTREGA). */
  modalidade?: Modalidade;
  /** Produtos distintos do mapa inteiro. */
  totalItens?: number;
  /** Peso total por etapa (Frio / Refrigerado / Seco). */
  pesoPorEtapa?: PesoEtapaDto[];
}

/** Item de GET /api/mapa-separacao/sem-ordem-carga — pedido da fila de conferência sem Ordem de Carga. */
export interface PedidoSemOrdemCargaDto {
  nunota: number;
  /** null nesta lista (é o critério dela); o badge do card mostra [OC n] ou [S/ ORDEM DE CARGA]. */
  ordemCarga: number | null;
  numNota: number | null;
  codParc: number | null;
  nomeParceiro: string | null;
  dataMovimento: string | null;
  conferido: boolean;
  modalidade?: Modalidade;
  /** Última impressão do mapa deste Nº Único (ISO) — null = nunca impresso. */
  impressoEm?: string | null;
  impressoPor?: string | null;
}

export interface PesoEtapaDto {
  /** "1" SECO | "2" REFRIGERADO | "3" CONGELADO | "0" SEM CLASSIFICAÇÃO. */
  codigo: string;
  descricao: string;
  pesoTotal: string;
}

export interface ParceiroSeparacaoDto {
  codParc: number;
  nomeParceiro: string;
  nunotas: number[];
  quantidadeTotal: string;
  pesoTotal: string;
  categorias: CategoriaSeparacaoDto[];
  /** Produtos distintos do pedido inteiro (todas as etapas). */
  totalItensPedido?: number;
  modalidade?: Modalidade;
}

/** Item de GET /api/mapa-separacao/abertas — OC aberta, ou fechada no Sankhya que ainda tem nota na fila de conferência. */
export interface OrdemCargaResumoDto {
  ordemCarga: number;
  dataPrevSaida: string;
  /** TGFORD.DTALTER — última alteração da OC ("dd/MM/yyyy HH:mm:ss") ou "—". */
  ultimaAlteracao?: string;
  placa: string | null;
  nomeMotorista: string | null;
  /** Total de notas da OC e quantas já estão conferidas no mirror local (ver TarefaSyncService) — barra de progresso do card. */
  totalNotas: number;
  notasConferidas: number;
  /** TGFORD.SITUACAO: 'A' aberta | 'F' fechada (ainda com nota na fila) — badge "Fechada" no card. */
  situacao: string | null;
  /** Quantas notas da OC são Express / Retira / Entrega — pins do card da OC. */
  qtdExpress?: number;
  qtdRetira?: number;
  qtdEntrega?: number;
  /** Última impressão do mapa desta OC (ISO) — null = nunca impresso. */
  impressoEm?: string | null;
  impressoPor?: string | null;
}

export interface CategoriaSeparacaoDto {
  codigo: '1' | '2' | '3' | '0';
  descricao: string;
  quantidadeTotal: string;
  pesoTotal: string;
  itens: ItemSeparacaoDto[];
}

export interface ItemSeparacaoDto {
  codProd: number;
  descricao: string;
  controle: string | null;
  unidade: string;
  quantidade: string;
  pesoUnitario: string;
  pesoTotal: string;
  /** Exige pesagem (backend RegraPesavel) — ícone de balança antes da descrição. */
  pesavel: boolean;
  /** Vendido em unidade alternativa: a mesma qtd na unidade padrão ("5 CX = 60 UN"). */
  quantidadePadrao?: string | null;
  unidadePadrao?: string | null;
}

/** Cor por categoria — mesma paleta de tarefa.model.ts (rotuloTipoSeparacao), reaproveitada aqui pro banner. */
export const CLASSE_CATEGORIA: Record<string, string> = {
  '1': 'cat-seco',
  '2': 'cat-refrigerado',
  '3': 'cat-congelado',
  '0': 'cat-sem-classificacao',
};

/** "1234.500" -> "1.234,5" (sem zeros à direita além da 1ª casa) — mesma regra do componente original (formatQtd). */
export function formatarQtd(valor: string): string {
  const n = Number(valor);
  if (!isFinite(n)) return valor;
  let texto = n.toLocaleString('pt-BR', { minimumFractionDigits: 3, maximumFractionDigits: 3 });
  texto = texto.replace(/,000$/, '');
  texto = texto.replace(/(,\d*[1-9])0+$/, '$1');
  return texto;
}

/** "1234.500" -> "1.234,500" — peso sempre com 3 casas fixas. */
export function formatarPeso(valor: string): string {
  const n = Number(valor);
  if (!isFinite(n)) return valor;
  return n.toLocaleString('pt-BR', { minimumFractionDigits: 3, maximumFractionDigits: 3 });
}

/** Pedido de Venda (porte do Jasper) — GET /api/mapa-separacao/pedidos-venda. Valores em texto do Sankhya. */
export interface PedidoVenda {
  nunota: number;
  cabecalho: Record<string, string | null>;
  itens: Record<string, string | null>[];
  parcelas: Record<string, string | null>[];
  comissoes: Record<string, string | null>[];
  erro?: string | null;
}
