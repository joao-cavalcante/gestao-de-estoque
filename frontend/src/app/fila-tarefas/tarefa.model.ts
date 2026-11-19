export type StatusTarefa = 'aguardando' | 'andamento' | 'concluido' | 'aguardando_corte';
export type SeveridadeAlerta = 'critico' | 'atencao' | null;

export interface Tarefa {
  id: string;
  cliente: string;
  codigoCliente: string | null;
  status: StatusTarefa;
  /**
   * Status granular original do backend (TarefaApiDto.statusOperacional,
   * ver ConferenciasService) — `status` acima agrupa em 4 buckets pra
   * filtro/KPI/ícone, mas o card usa este campo quando precisa de um rótulo
   * mais fino (ex.: "aguardando_recontagem" vs "aguardando" comum, que caem
   * no mesmo bucket 'aguardando' mas significam coisas bem diferentes pro
   * operador).
   */
  statusOperacional?: string;
  alerta: SeveridadeAlerta;
  motivoAlerta?: string;

  pedido: string;
  numeroUnico: string;
  nf: string;
  data: string;
  /** Veículo da Ordem de Carga — "PLACA · MODELO" ou "—". */
  transporte: string;
  /** Motorista da Ordem de Carga (null sem OC). */
  motorista: string | null;
  responsavel: string;
  codigoResponsavel: string | null;
  tipoOperacao: string;
  codigoTipoOperacao: string | null;
  ordemCarga: number | null;
  itens: number;
  valor: number;
  /** TGFCAB.AD_TURNOENTREGA já traduzido — "Diurno" | "Noturno" | "Qualquer" | "—". */
  periodoEntrega: string;
  /** Card da fila (17/11/2026) — ver docs do Hub "Dados de expedição do pedido". null = Sankhya não informou. */
  /** TGFCAB.PESOBRUTO (kg). */
  pesoKg?: number | null;
  /** TGFCAB.QTDVOL — volumes previstos (soma das quantidades na unidade de venda: caixa, fardo, pacote). */
  volumesPrevistos?: number | null;
  /** TGFCAB.AD_DHNEG — quando o pedido entrou (base da idade no card). */
  pedidoEm?: Date | null;
  /** TGFCAB.DTPREVENT — previsão de entrega (na Negri só a data). */
  previsaoEntrega?: Date | null;
  /** TGFCAB.AD_OBSSEP — observação do pedido (horário de recebimento, dias, avisos). */
  obsSeparacao?: string | null;
  /** Da OC do pedido: TGFORD.PESOMAX (kg) e DTPREVSAIDA — ocupação e saída no cabeçalho da OC. */
  ocPesoMax?: number | null;
  ocSaidaPrevista?: Date | null;
  /** Conferência em curso na Torre — itens conferidos/total, quem confere e desde quando (null = ninguém abriu). */
  andamento?: { conferidos: number; total: number; conferente: string | null; desde: Date } | null;
  /** Modalidade do pedido — TGFCAB.AD_EXPRESS / AD_RETIRA / AD_ENTREGA = 'S'. */
  express: boolean;
  retira: boolean;
  entrega: boolean;
  /**
   * Conferência por etapa (V29) — presente só quando o tenant tem o módulo
   * `conferencia_segmentada`. Uma entrada por tipo de separação com item na nota.
   */
  etapas?: TarefaEtapa[];
  /** Carregamento (checklist do "Ver conferidos") — só nota conferida com OC. */
  carregamento?: CarregamentoTarefa;
  /** Conferida, CCO pede faturamento e a nota ainda não saiu confirmada (sessão a faturar + último erro). */
  notaPendente?: NotaPendente;
  /** TGFORD.SITUACAO = 'F' — OC fechada no Sankhya: os pedidos dela saem da fila. */
  ordemCargaFechada?: boolean;
  /** Nota fiscal gerada a partir do pedido — número e situação no Sankhya (STATUSNFE). */
  notaFiscal?: NotaFiscalPedido;
  /** Compra: notas de ajuste de peso (entrada = a maior, saída = a menor) geradas pela Torre de Operação. */
  ajustesPeso?: AjustePeso[];
  /** TGFCAB.TIPMOV — 'C' compra e 'V' venda já são notas (sem "Gerar nota"). */
  tipMov?: string | null;
  /** Empresa do documento (TGFCAB.CODEMP / Empresa.NOMEFANTASIA) — usado pelo coletor. */
  codigoEmpresa?: string | null;
  nomeEmpresa?: string | null;
}

/** sentido 'E' entrada | 'S' saída; status pendente/criando/cabecalho/confirmado/erro. */
export interface AjustePeso {
  sentido: 'E' | 'S';
  status: string;
  nunotaAjuste: number | null;
  erro: string | null;
}

/** Texto do card: "Entrada nº 79760 · confirmada". */
export function textoAjustePeso(a: AjustePeso): string {
  const tipo = a.sentido === 'E' ? 'Entrada' : 'Saída';
  const numero = a.nunotaAjuste ? ` nº ${a.nunotaAjuste}` : '';
  const situacao =
    a.status === 'confirmado' ? 'confirmada'
    : a.status === 'erro' ? 'não gerada'
    : 'aguardando a nota do fornecedor ser confirmada';
  return `${tipo}${numero} · ${situacao}`;
}

export interface NotaFiscalPedido {
  nunota: number;
  numero: number | null;
  serie: string | null;
  /** TGFCAB.STATUSNFE cru — A aprovada, E/I/P aguardando, R/D/V problema; null = não enviada. */
  statusNfe: string | null;
  /** Rótulo da situação (ex.: "Aprovada", "Rejeitada pela SEFAZ"). */
  situacao: string;
  confirmada: boolean;
  nfce: boolean;
  /** Rejeitada/denegada pela SEFAZ: problema + o que fazer (backend MensagemOperador). */
  motivoRejeicao?: string | null;
}

/** Mensagem de erro do backend em duas partes: o problema e o que o operador deve fazer. */
export interface MensagemOperador {
  problema: string;
  acao: string | null;
}

const SEPARADOR_ACAO = ' O que fazer: ';

/** Separa "PROBLEMA. O que fazer: AÇÃO." (MensagemOperador do backend). Texto sem a ação vira só o problema. */
export function partesMensagem(texto: string | null | undefined): MensagemOperador | null {
  const t = texto?.trim();
  if (!t) return null;
  const i = t.indexOf(SEPARADOR_ACAO);
  return i < 0 ? { problema: t, acao: null } : { problema: t.slice(0, i), acao: t.slice(i + SEPARADOR_ACAO.length) };
}

/** Tom da situação da nota: verde aprovada · vermelho com problema · âmbar no meio do caminho. */
export function tomNota(n: NotaFiscalPedido): 'ok' | 'erro' | 'espera' {
  if (n.statusNfe === 'A') return 'ok';
  if (n.statusNfe === 'R' || n.statusNfe === 'D' || n.statusNfe === 'V') return 'erro';
  return 'espera';
}

export interface NotaPendente {
  sessaoId: string;
  /** Último motivo de recusa do Sankhya (faturar ou confirmar). */
  erro: string | null;
}

export interface CarregamentoTarefa {
  /** Itens conferidos (produto+controle) e quantos já foram checados como carregados. */
  total: number;
  carregados: number;
  /** Sessão a abrir no checklist (a que ainda tem item a carregar). */
  sessaoId: string | null;
}

/**
 * Fase do pedido na linha de frente, sempre na ordem do fluxo (usuário, 08/10/2026):
 * CONFERIR → CORTE → CARREGAR → FECHAMENTO (com OC: a nota sai no "Fechar OC") → PRONTO.
 * Pedido SEM OC não tem fechamento: depois da conferência vai pra NOTA ("Gerar nota" no card).
 * Uma fase = um botão principal no card.
 */
export type FasePedido = 'conferir' | 'corte' | 'carregar' | 'fechamento' | 'nota' | 'pronto';

export const FASES: readonly FasePedido[] = ['conferir', 'corte', 'carregar', 'fechamento', 'nota', 'pronto'];

/** Um nome por etapa, igual em pílulas, KPIs, títulos das seções e régua (usuário, 08/10/2026). */
export const ROTULO_FASE: Record<FasePedido, string> = {
  conferir: 'Conferência',
  corte: 'Corte',
  carregar: 'Carregamento',
  fechamento: 'Fechamento da OC',
  nota: 'Nota',
  pronto: 'Concluído',
};

export function faseTarefa(t: Tarefa): FasePedido {
  if (t.status === 'aguardando_corte') return 'corte';
  if (t.status !== 'concluido') return 'conferir';
  if (aCarregar(t)) return 'carregar';
  // Já tem nota confirmada no Sankhya (pelo WMS ou faturada por fora): concluído, com ou sem OC.
  if (t.notaFiscal?.confirmada) return 'pronto';
  // O documento JÁ É uma nota (compra 'C' / venda 'V'): não tem nota a gerar nem fechamento por nota (10/11/2026).
  if (jaENota(t.tipMov)) return 'pronto';
  // Com OC: depois de carregado, TODO pedido espera o "Fechar OC" (que fatura no Sankhya) — independente da CCO.
  if (t.ordemCarga != null) return 'fechamento';
  // Sem OC (retira/express): conferido e sem nota confirmada = gerar nota (independe da CCO, igual ao Fechar OC).
  // Só os recentes (o cache de notas cobre 15 dias) — antigos sem nota no cache contam como concluídos.
  if (t.notaPendente || recente(t.data, 15)) return 'nota';
  return 'pronto';
}

/** TIPMOV de documento que já é nota fiscal — compra (C) e venda (V). Pedido (P) e afins geram nota. */
export function jaENota(tipMov: string | null | undefined): boolean {
  const t = (tipMov ?? '').trim().toUpperCase();
  return t === 'C' || t === 'V';
}

/** "dd/mm/aaaa ..." dentro dos últimos [dias] dias. Data ilegível = não. */
function recente(data: string, dias: number): boolean {
  const m = /^(\d{2})\/(\d{2})\/(\d{4})/.exec((data ?? '').trim());
  if (!m) return false;
  const d = new Date(Number(m[3]), Number(m[2]) - 1, Number(m[1]));
  return Date.now() - d.getTime() <= dias * 86_400_000;
}

/** Nota pendente de pedido SEM OC (conferido, sem nota confirmada) — card com "Gerar nota". */
export function aguardandoNota(t: Tarefa): boolean {
  return faseTarefa(t) === 'nota';
}

/** Ainda sem nota confirmada: "Gerar nota" (sem OC) ou no fechamento da OC (com OC). */
export function semNota(t: Tarefa): boolean {
  const f = faseTarefa(t);
  return f === 'nota' || f === 'fechamento';
}

/** Conferida, com OC e com item ainda não carregado — vira "A CARREGAR" na fila filtrada por OC. */
export function aCarregar(t: Tarefa): boolean {
  return t.status === 'concluido' && t.ordemCarga != null && !!t.carregamento && t.carregamento.carregados < t.carregamento.total;
}

export interface TarefaEtapa {
  /** 1 Secos | 2 Refrigerado | 3 Congelado | 4 Não pesáveis | 5 Pesáveis (ver TIPOS_SEPARACAO). */
  tipo: number;
  status: 'P' | 'C';
  /** Concluída COM divergência — chip vermelho no card. */
  divergente?: boolean;
  /** Progresso da etapa (itens conferidos / total) — pra "Continuar 3/8" no card. */
  total: number;
  conferidos: number;
}

/**
 * Catálogo das etapas da conferência — rótulo + ícone. Critério ativo no backend (CriterioEtapa): por PESO,
 * 4 Não pesáveis | 5 Pesáveis. 1–3 (TGFPRO.AD_TIPOSEPARACAO: Secos/Refrigerado/Congelado) é o critério antigo,
 * desativado — fica aqui pras sessões antigas e pra poder voltar.
 */
export const TIPOS_SEPARACAO = [
  { id: 1, label: 'Secos', icone: 'seco' as const },
  { id: 2, label: 'Refrigerado', icone: 'refrigerado' as const },
  { id: 3, label: 'Congelado', icone: 'congelado' as const },
  { id: 4, label: 'Não pesáveis', icone: 'box' as const },
  { id: 5, label: 'Pesáveis', icone: 'balanca' as const },
];

/** Etapas do critério ativo (filtro da fila) — acompanhar WMS_CRITERIO_ETAPA do backend; temperatura = [1, 2, 3]. */
export const TIPOS_SEPARACAO_ATIVOS = TIPOS_SEPARACAO.filter((t) => t.id === 4 || t.id === 5);

export function rotuloTipoSeparacao(tipo: number): string {
  return TIPOS_SEPARACAO.find((t) => t.id === tipo)?.label ?? `Tipo ${tipo}`;
}

export function iconeTipoSeparacao(tipo: number | null | undefined): (typeof TIPOS_SEPARACAO)[number]['icone'] {
  return TIPOS_SEPARACAO.find((t) => t.id === tipo)?.icone ?? 'seco';
}

/** Uma opção de select "cód - descrição" — padrão do oq-searchable-select. */
export interface OpcaoComCodigo {
  codigo: string;
  label: string;
}

/** Filtros avançados — por CÓDIGO (não pelo texto exibido), Cliente/Vendedor/Tipo de Operação. */
export interface FiltrosAvancados {
  codigoParceiro: string | null;
  codigoVendedor: string | null;
  codigoTipoOperacao: string | null;
  /** Texto digitado no campo "Ordem de Carga" (match exato pelo número). */
  ordemCarga: string | null;
  /** Vínculo com Ordem de Carga (TGFCAB.ORDEMCARGA; nulo/0 = sem): todos | só com OC | só sem OC. */
  vinculoOrdemCarga: VinculoOrdemCarga;
}

export type VinculoOrdemCarga = 'todos' | 'com' | 'sem';

/** Pílulas da fila = fases do pedido ("pronto" só aparece agrupado dentro da OC). */
export const FILTROS_STATUS = ['todos', 'conferir', 'corte', 'carregar', 'nota'] as const;
export type FiltroStatus = (typeof FILTROS_STATUS)[number];

/** Escopo da fila: todas as OCs, uma OC (número) ou só pedidos sem OC. */
export type EscopoOc = { tipo: 'todas' } | { tipo: 'oc'; oc: string } | { tipo: 'sem' };

/** Modo de exibição — compartilhado com as outras telas de fila. */
export type { ViewMode } from '../shared/lista-layout/view-mode';

/** Colunas ordenáveis (cabeçalho da lista) — a ordenação vale pros dois modos. */
export type CampoOrdenacao = 'cliente' | 'numeroUnico' | 'nf' | 'data' | 'itens' | 'peso' | 'volumes' | 'entrega' | 'idade';

export interface Ordenacao {
  campo: CampoOrdenacao;
  direcao: 'asc' | 'desc';
}


/** "17/11/2026" ou "17/11/2026 15:54:56" (loadRecords do Sankhya, hora de Brasília) → Date local; inválido = null. */
export function dataSankhya(valor: string | null | undefined): Date | null {
  const m = valor?.trim().match(/^(\d{2})\/(\d{2})\/(\d{4})(?:\s+(\d{2}):(\d{2})(?::(\d{2}))?)?$/);
  if (!m) return null;
  const [, d, mes, a, h, min, seg] = m;
  const data = new Date(+a, +mes - 1, +d, +(h ?? 0), +(min ?? 0), +(seg ?? 0));
  return isNaN(data.getTime()) ? null : data;
}

/** Idade do pedido: "agora", "há 25 min", "há 1h05", "há 2 dias". */
export function idadePedido(desde: Date, agora = new Date()): string {
  const min = Math.max(0, Math.floor((agora.getTime() - desde.getTime()) / 60000));
  if (min < 1) return 'agora';
  if (min < 60) return `há ${min} min`;
  const h = Math.floor(min / 60);
  if (h < 24) return `há ${h}h${String(min % 60).padStart(2, '0')}`;
  const dias = Math.floor(h / 24);
  return `há ${dias} ${dias === 1 ? 'dia' : 'dias'}`;
}

export type SituacaoEntrega = 'atrasada' | 'hoje' | 'futura';

/** Previsão de entrega × hoje (por data, sem hora): "Entrega hoje", "Entrega 18/11", atrasada se já passou. */
export function entregaPedido(previsao: Date, agora = new Date()): { texto: string; situacao: SituacaoEntrega } {
  const dia = (d: Date) => new Date(d.getFullYear(), d.getMonth(), d.getDate()).getTime();
  const ddmm = previsao.toLocaleDateString('pt-BR', { day: '2-digit', month: '2-digit' });
  if (dia(previsao) < dia(agora)) return { texto: `Entrega ${ddmm} · atrasada`, situacao: 'atrasada' };
  if (dia(previsao) === dia(agora)) return { texto: 'Entrega hoje', situacao: 'hoje' };
  return { texto: `Entrega ${ddmm}`, situacao: 'futura' };
}

/** Peso no card: "68 kg", "1.240 kg", "12,5 kg" (uma casa só abaixo de 100 kg). */
export function pesoTexto(kg: number): string {
  const casas = kg < 100 && kg % 1 !== 0 ? 1 : 0;
  return `${kg.toLocaleString('pt-BR', { minimumFractionDigits: casas, maximumFractionDigits: casas })} kg`;
}

/** Documento que já é nota (TIPMOV V ou C — ver jaENota): o número da nota identifica o documento — card e lista
 *  mostram "NF …" ao lado do nº único (usuário, 17/11/2026). No pedido (P) o NUMNOTA não aparece. */
export function mostraNumeroNota(t: Tarefa): boolean {
  return jaENota(t.tipMov) && !!t.nf && t.nf !== '—';
}

/**
 * TIPMOV de entrada — compra (C) e pedido de compra (O), mesmo critério da TV de entrada. Desde 19/11/2026 a compra
 * sai da Fila de Conferência e fica só na tela Entrada (decisão do usuário: "entrada em um único lugar").
 */
export function ehEntrada(tipMov: string | null | undefined): boolean {
  const t = (tipMov ?? '').trim().toUpperCase();
  return t === 'C' || t === 'O';
}
