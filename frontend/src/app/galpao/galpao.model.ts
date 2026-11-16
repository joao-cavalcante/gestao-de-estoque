/** Espelho de wms.backend.galpao (GalpaoDtos.kt / Geometria.kt). Medidas em metros. */

export type TipoObjeto = 'ESTRUTURA' | 'CORREDOR' | 'DOCA' | 'AREA' | 'PAREDE';

export interface SetorPlanta {
  codigo: string;
  nome: string;
  cor: string;
  /** AD_TIPOSEPARACAO do Sankhya que o setor recebe (1 Seco, 2 Refrigerado, 3 Congelado); ausente = qualquer. */
  tipoSeparacao?: number | null;
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
  /** Nome de cada produto (cache da Torre). */
  nomes?: Record<number, string>;
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

// ─── Análises (ABC, cobertura, validade, saúde) — GET /plantas/{id}/analises ───

export interface ComponenteSaude {
  componente: 'CONSISTENCIA' | 'COBERTURA' | 'RECENCIA' | 'VALIDADE' | 'ENDERECO';
  peso: number;
  /** 0–1; null = sem dado (sai da conta e baixa a confiança). */
  qualidade: number | null;
  pontos: number | null;
}

export interface SaudeEstoque {
  score: number | null;
  confianca: number;
  faixa: 'CRÍTICO' | 'ATENÇÃO' | 'SAUDÁVEL' | 'EXCELENTE' | 'N/D';
  formula: string;
  componentes: ComponenteSaude[];
}

export type ClasseAbc = 'A' | 'B' | 'C' | 'N/D';
export type FaixaValidade = 'VENCIDO' | 'ATE_30' | '31_90' | 'ACIMA_90' | 'N/D';

export interface ProdutoAnalise {
  codprod: number;
  descricao: string;
  ativo: boolean;
  codvol: string | null;
  codgrupo: number | null;
  grupo: string | null;
  /** AD_TIPOSEPARACAO: 1 Seco, 2 Refrigerado, 3 Congelado; null = sem setor. */
  setor: number | null;
  setorOrigem: 'PRODUTO' | 'GRUPO' | 'N/D';
  pesoBruto: number | null;
  m3: number | null;
  valorVendido: number;
  quantidadeVendida: number;
  frequencia: number;
  ultimaVenda: string | null;
  abc: ClasseAbc;
  participacao: number | null;
  acumulado: number | null;
  abcQtd: ClasseAbc;
  participacaoQtd: number | null;
  acumuladoQtd: number | null;
  abcFreq: ClasseAbc;
  participacaoFreq: number | null;
  acumuladoFreq: number | null;
  estoque: number;
  reservado: number;
  disponivel: number;
  enderecado: number;
  demandaDia: number;
  cobertura: number | null;
  coberturaOrigem: string;
  dtval: string | null;
  validade: FaixaValidade;
  parado: boolean;
  idadeDias: number | null;
  idadeOrigem: string;
  saude: SaudeEstoque;
  origem: string;
}

export interface ParametrosAnalise {
  dias: number;
  limiteA: number;
  limiteB: number;
  coberturaMinDias: number;
  coberturaMaxDias: number;
  setorPeloGrupo: boolean;
  tipmovCompra: string[];
}

export interface AnalisesPlanta {
  lidoEm: string;
  parametros: ParametrosAnalise;
  produtos: ProdutoAnalise[];
  validadesLote: { codprod: number; controle: string; dtval: string }[];
  aviso: string | null;
}

export type MetricaAbc = 'VALOR' | 'QUANTIDADE' | 'FREQUENCIA';

export const ROTULO_SETOR_PRODUTO: Record<number, string> = { 1: 'Seco', 2: 'Refrigerado', 3: 'Congelado' };

export const ROTULO_VALIDADE: Record<FaixaValidade, string> = {
  VENCIDO: 'Vencido',
  ATE_30: 'Até 30 dias',
  '31_90': '31–90 dias',
  ACIMA_90: 'Acima de 90 dias',
  'N/D': 'N/D',
};

export const ROTULO_COMPONENTE: Record<ComponenteSaude['componente'], string> = {
  CONSISTENCIA: 'Consistência (Sankhya × endereçado)',
  COBERTURA: 'Cobertura',
  RECENCIA: 'Recência de venda',
  VALIDADE: 'Validade',
  ENDERECO: 'Endereçamento',
};

/** Classe, participação e acumulado do produto na métrica escolhida. */
export function abcDa(p: ProdutoAnalise, m: MetricaAbc): { classe: ClasseAbc; participacao: number | null; acumulado: number | null } {
  if (m === 'QUANTIDADE') return { classe: p.abcQtd, participacao: p.participacaoQtd, acumulado: p.acumuladoQtd };
  if (m === 'FREQUENCIA') return { classe: p.abcFreq, participacao: p.participacaoFreq, acumulado: p.acumuladoFreq };
  return { classe: p.abc, participacao: p.participacao, acumulado: p.acumulado };
}

// ─── Alertas — GET/POST /plantas/{id}/alertas ───

export type SeveridadeAlerta = 'CRITICO' | 'ATENCAO' | 'INFO';
export type StatusAlerta = 'ABERTO' | 'RECONHECIDO' | 'RESOLVIDO';

export interface Alerta {
  id: string;
  codigo: string;
  severidade: SeveridadeAlerta;
  entidade: 'PRODUTO' | 'ESTOQUE' | 'ENDERECO' | 'CONFERENCIA';
  mensagem: string;
  evidencia: Record<string, unknown>;
  codprod: number | null;
  produto: string | null;
  controle: string | null;
  enderecoId: string | null;
  endereco: string | null;
  status: StatusAlerta;
  atribuido: string | null;
  slaHoras: number;
  versao: number;
  criadoEm: string;
  vistoEm: string;
  reconhecidoEm: string | null;
  resolvidoEm: string | null;
  tratadoPor: string | null;
  slaVencido: boolean;
  /** A última geração ainda encontrou a condição. */
  vigente: boolean;
}

export interface AlertasPlanta {
  alertas: Alerta[];
  geradoEm: string | null;
  aviso: string | null;
}

export const ROTULO_SEVERIDADE: Record<SeveridadeAlerta, string> = { CRITICO: 'Crítico', ATENCAO: 'Atenção', INFO: 'Informativo' };
export const ROTULO_STATUS_ALERTA: Record<StatusAlerta, string> = { ABERTO: 'Aberto', RECONHECIDO: 'Reconhecido', RESOLVIDO: 'Resolvido' };

export const ROTULO_CODIGO_ALERTA: Record<string, string> = {
  ESTOQUE_NEGATIVO: 'Estoque negativo',
  RESERVA_EXCEDENTE: 'Reserva maior que o estoque',
  VALIDADE_AUSENTE: 'Validade não informada',
  SEM_SETOR: 'Produto sem setor',
  DIMENSOES_AUSENTES: 'Peso/volume ausentes',
  ESTOQUE_PARADO: 'Estoque parado',
  COBERTURA_BAIXA: 'Cobertura baixa',
  COBERTURA_ALTA: 'Cobertura alta',
  VALIDADE_VENCIDO: 'Validade vencida',
  VALIDADE_ATE_30: 'Vence em até 30 dias',
  VALIDADE_31_90: 'Vence em 31–90 dias',
  RUPTURA: 'Ruptura',
  CAPACIDADE_AUSENTE: 'Capacidade não parametrizada',
  SOBRECAPACIDADE: 'Acima da capacidade',
  BLOQUEADO_COM_SALDO_TAREFA: 'Bloqueado com saldo',
  SETOR_INCOMPATIVEL: 'Setor incompatível',
  SALDO_SEM_ENDERECO: 'Saldo sem endereço',
  RECONCILIACAO: 'Diferença contábil × endereçado',
  CONFERENCIA_DIVERGENTE: 'Conferência divergente',
};
