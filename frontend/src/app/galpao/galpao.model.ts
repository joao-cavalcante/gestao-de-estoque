/** Espelho de wms.backend.galpao (GalpaoDtos.kt / Geometria.kt). Medidas em metros. */

export type TipoObjeto = 'ESTRUTURA' | 'CORREDOR' | 'DOCA' | 'AREA' | 'PAREDE';

export interface SetorPlanta {
  codigo: string;
  nome: string;
  cor: string;
}

export interface ObjetoPlanta {
  codigo: string;
  tipo: TipoObjeto;
  /** Centro do objeto. */
  x: number;
  z: number;
  largura: number;
  profundidade: number;
  altura: number;
  rotacao: number;
  setor?: string | null;
  modulos: number;
  niveis: number;
  nivelPicking: number;
  capQtd?: number | null;
  capPeso?: number | null;
  capVolume?: number | null;
}

export interface GeometriaPlanta {
  largura: number;
  profundidade: number;
  corredorMinimo: number;
  padraoEndereco: string;
  setores: SetorPlanta[];
  objetos: ObjetoPlanta[];
}

export interface Planta {
  id: string;
  nome: string;
  codemp: number | null;
  codlocal: number | null;
  largura: number;
  profundidade: number;
  versaoPublicada: number | null;
  totalEnderecos: number;
  temRascunho: boolean;
}

export interface SalvarPlantaRequest {
  nome: string;
  codemp: number | null;
  codlocal: number | null;
  largura: number;
  profundidade: number;
}

export interface VersaoResumo {
  id: string;
  numero: number;
  status: 'RASCUNHO' | 'PUBLICADA' | 'ARQUIVADA';
  observacao: string | null;
  criadoPorNome: string;
  criadoEm: string;
  publicadoPorNome: string | null;
  publicadoEm: string | null;
  totalObjetos: number;
}

export interface Versao {
  versao: VersaoResumo;
  geometria: GeometriaPlanta;
}

export interface Validacao {
  valido: boolean;
  erros: string[];
  totalEnderecos: number;
}

export interface SaldoEndereco {
  codprod: number;
  produto: string;
  controle: string;
  quantidade: number;
  /** Versão do saldo — vai junto no movimento (mudou = 409). */
  versao: number;
}

export interface Endereco {
  id: string;
  codigo: string;
  setor: string;
  estrutura: string;
  modulo: number;
  nivel: number;
  tipo: 'PICKING' | 'PULMAO';
  x: number;
  y: number;
  z: number;
  rotacao: number;
  largura: number;
  comprimento: number;
  altura: number;
  capQtd: number | null;
  capPeso: number | null;
  capVolume: number | null;
  bloqueado: boolean;
  quantidade: number;
  /** Maior razão entre quantidade, peso e volume; null = N/D, não 0%. */
  ocupacao: number | null;
  baseOcupacao: 'QUANTIDADE' | 'PESO' | 'VOLUME' | null;
  saldos: SaldoEndereco[];
  contagensPendentes: number;
  /** Cada razão em separado (null = sem capacidade ou medida desconhecida). */
  ocupacaoQtd: number | null;
  ocupacaoPeso: number | null;
  ocupacaoVolume: number | null;
  pesoKg: number | null;
  volumeM3: number | null;
  /** Último movimento físico no endereço (ISO). */
  ultimoMovimentoEm: string | null;
  emSeparacao: boolean;
}

export interface VisaoPlanta {
  planta: Planta;
  geometria: GeometriaPlanta | null;
  enderecos: Endereco[];
  documentosEmSeparacao: number;
  geradoEm: string;
}

export interface IndicadoresPlanta {
  saldoContabil: number | null;
  produtosComSaldo: number | null;
  lidoEm: string;
  aviso: string | null;
}

export interface DocumentoBusca {
  nunota: number;
  numnota: number | null;
  tipmov: string | null;
  codprods: number[];
}

export interface PublicacaoOk {
  versao: string;
  enderecosAtivos: string;
  enderecosDesativados: string;
}

export const PADRAO_ENDERECO = '{estrutura}-M{modulo}-N{nivel}';

export const ROTULO_TIPO: Record<TipoObjeto, string> = {
  ESTRUTURA: 'Estrutura (porta-palete)',
  CORREDOR: 'Corredor',
  DOCA: 'Doca',
  AREA: 'Área operacional',
  PAREDE: 'Parede',
};

// ─── Movimentação (fase 4) ──────────────────────────────────────────────────

export type TipoMovimentacao = 'ENDERECAR' | 'TRANSFERIR' | 'RETIRAR' | 'CONTAR' | 'BLOQUEAR' | 'DESBLOQUEAR';

export interface MovimentoRequest {
  comandoId: string;
  tipo: TipoMovimentacao;
  origemId?: string | null;
  destinoId?: string | null;
  codprod?: number | null;
  controle?: string;
  quantidade?: number;
  versaoOrigem?: number | null;
  versaoDestino?: number | null;
  motivo?: string | null;
}

export interface ResultadoMovimento {
  repetido: boolean;
  mensagem: string;
  movimentoId: string | null;
  contagemId: string | null;
}

export interface Movimento {
  id: string;
  tipo: TipoMovimentacao | 'AJUSTE_CONTAGEM';
  origem: string | null;
  destino: string | null;
  codprod: number | null;
  produto: string | null;
  controle: string;
  quantidade: number;
  motivo: string | null;
  usuarioNome: string;
  criadoEm: string;
}

export interface MovimentosPaginados {
  itens: Movimento[];
  paginaAtual: number;
  totalPaginas: number;
  totalRegistros: number;
}

export interface Contagem {
  id: string;
  endereco: string;
  enderecoId: string;
  codprod: number;
  produto: string;
  controle: string;
  quantidadeSistema: number;
  quantidadeContada: number;
  diferenca: number;
  motivo: string | null;
  status: 'PENDENTE' | 'APROVADA' | 'DESCARTADA';
  contadoPorNome: string;
  contadoEm: string;
  decididoPorNome: string | null;
  decididoEm: string | null;
  observacaoDecisao: string | null;
}

export interface ProdutoBusca {
  codprod: number;
  produto: string;
  unidade: string | null;
}

export interface ControleEstoque {
  controle: string;
  contabil: number;
  reservado: number;
  enderecado: number;
  semEndereco: number;
}

export interface EstoqueProduto {
  codprod: number;
  produto: string;
  controles: ControleEstoque[];
  enderecos: { endereco: string; enderecoId: string; controle: string; quantidade: number }[];
  aviso: string | null;
}

export interface ConciliacaoLinha {
  codprod: number;
  produto: string;
  controle: string;
  contabil: number;
  reservado: number;
  enderecado: number;
  semEndereco: number;
  excedente: number;
  situacao: 'OK' | 'PARCIAL' | 'SEM_ENDERECO' | 'EXCEDENTE' | 'NEGATIVO';
}

export interface Conciliacao {
  codemp: number;
  codlocal: number;
  linhas: ConciliacaoLinha[];
  lidoEm: string;
}

export const ROTULO_MOVIMENTO: Record<string, string> = {
  ENDERECAR: 'Endereçamento',
  TRANSFERIR: 'Transferência',
  RETIRAR: 'Retirada',
  CONTAR: 'Contagem',
  AJUSTE_CONTAGEM: 'Ajuste de contagem',
  BLOQUEAR: 'Bloqueio',
  DESBLOQUEAR: 'Desbloqueio',
};
