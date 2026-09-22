/**
 * DTO cru devolvido por GET /api/mapa-separacao/{ordemCarga} (MapaSeparacaoRoutes.kt) —
 * consulta AO VIVO no Sankhya a cada chamada, sem mirror local (ver MapaSeparacaoService).
 * Números vêm como string (já formatados com ponto decimal e 3 casas pelo backend) — a
 * formatação pt-BR de exibição é feita aqui (ver formatarQtd/formatarPeso).
 */
export interface MapaSeparacaoDto {
  ordemCarga: number;
  notas: NotaSeparacaoDto[];
}

export interface NotaSeparacaoDto {
  nunota: number;
  codParc: number;
  nomeParceiro: string;
  codVeiculo: number | null;
  placa: string | null;
  modeloVeiculo: string | null;
  codParcMotorista: number | null;
  nomeMotorista: string | null;
  pesoMaxOc: string | null;
  produtosDistintos: number;
  quantidadeTotal: string;
  pesoTotal: string;
  semClassificacao: number;
  categorias: CategoriaSeparacaoDto[];
}

/** Item de GET /api/mapa-separacao/fechadas — Ordens de Carga já fechadas (TGFORD.SITUACAO='F') pra seleção. */
export interface OrdemCargaResumoDto {
  ordemCarga: number;
  dataPrevSaida: string;
  placa: string | null;
  nomeMotorista: string | null;
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
