export interface IniciarSeparacaoResposta {
  sessaoId: string;
  status: SessaoStatus;
}

export type SessaoStatus = 'carregando' | 'pronta' | 'erro' | 'invalidada' | 'concluida';

export interface SessaoSeparacao {
  id: string;
  nunota: number;
  status: SessaoStatus;
  erro: string | null;
  /** CCO.OBTERQTDBALANCA cru — 'N' (ou ausente) = fluxo de peso desativado. */
  obterQtdBalanca: string | null;
  /** Módulo por-tenant "conferência segmentada" — snapshot da sessão; false = conferência normal. */
  conferenciaSegmentada: boolean;
  /** CCO.FATAOCONCLUIR cru — 'S' = oferecer faturamento (escolha de TOP) após finalizar. */
  fatAoConcluir: string | null;
  // CCO "Comportamento da interface" (V28) — só 'N' explícito esconde o painel; null/'S'/outro = mostra.
  /** CCO.EXIBIRPROD — painel de pendentes. */
  exibirProd: string | null;
  /** CCO.EXIBIRQTD — qtd negociada na lista de pendentes. */
  exibirQtd: string | null;
  /** CCO.EXIBIRPRODCONF — painel de conferidos. */
  exibirProdConf: string | null;
  /** CCO.EXIBIRQTDCONF — qtd conferida (lista de conferidos + última leitura). */
  exibirQtdConf: string | null;
  /** CCO.EXIBIRIMGPROD — painel de imagem / última leitura. */
  exibirImgProd: string | null;
}

/** Resposta de POST /sessoes/{id}/finalizar. */
export interface FinalizarResultado {
  ok: boolean;
  /** true = o corte deixou a conferência em TGFCON2.STATUS='C' (aguardando liberação). */
  aguardandoCorte: boolean;
  nuconf: number | null;
}

/** Conferência por etapa (V29). tipoSeparacao: 1 Secos | 2 Resfriados | 3 Congelados. */
export interface SessaoEtapa {
  tipoSeparacao: number;
  status: 'P' | 'C';
  concluidaPor: string | null;
  concluidaEm: string | null;
}

/** Resposta de POST /sessoes/{id}/concluir-etapa. */
export interface ConcluirEtapaResultado {
  etapaConcluida: boolean;
  /** true = era a última etapa; a conferência foi finalizada no Sankhya. */
  conferenciaFinalizada: boolean;
  aguardandoCorte: boolean;
  nuconf: number | null;
}

/** TOP de destino pro faturamento (GET /sessoes/{id}/tops-faturamento). */
export interface TopFaturamento {
  codTipOper: number;
  descricao: string;
}

/** Dados da etiqueta de volume (GET /sessoes/{id}/etiquetas). */
export interface EtiquetaDados {
  cliente: string;
  uf: string;
  numeroNota: string;
  numeroConferencia: number | null;
  totalVolumes: number;
}

/** Conferência finalizada pelo WMS (GET /conferencias-finalizadas). */
export interface ConferenciaFinalizada {
  sessaoId: string;
  nunota: number;
  numeroNota: number | null;
  nomeParceiro: string | null;
  descricaoTipoOperacao: string | null;
  dataMovimento: string | null;
  apelidoVendedor: string | null;
  nuconf: number | null;
}

export interface ConferenciasFinalizadasResposta {
  itens: ConferenciaFinalizada[];
  total: number;
  page: number;
  perPage: number;
}

/** UMA (Unidade de Movimentação/Armazenagem) — rotina de peso portada do projeto base. */
export interface Uma {
  codprod: number;
  coduma: number;
  descricao: string | null;
  peso: string | null;
  codvol: string | null;
  codbarra: string | null;
  padrao: boolean;
}

export interface CodigoBarra {
  codigoBarra: string;
  codprod: number;
  codvol: string | null;
  controle: string;
  origem: 'BAR' | 'VOA' | 'EST';
}

/** Resultado de POST /sessoes/{id}/resolver-codigo-barras — já vem com produto+unidade+controle certos, decidido no backend. */
export interface ItemResolvido {
  codprod: number;
  descricaoProduto: string | null;
  referencia: string | null;
  codvol: string | null;
  controle: string;
  fatorConversao: string | null;
  divideMultiplica: string | null;
}

/** Resultado de POST /sessoes/{id}/identificar — passo 1 do fluxo por Tab. */
export interface IdentificarProdutoResultado {
  codprod: number;
  descricaoProduto: string | null;
  /** true = campo "Nº do Lote" com digitação livre; false = <select> com controlesDisponiveis. */
  controleModoLote: boolean;
  /** "SEM_CONTROLE" é sentinel — front mostra "Sem controle" e desabilita a opção. */
  controlesDisponiveis: string[];
  controleAutoSelecionado: string | null;
  controleTravado: boolean;
  /** TGFVOL.UTILICONFPESO do produto — rotina de peso portada do projeto base (combinar com SessaoSeparacao.obterQtdBalanca). */
  usaConfPeso: boolean;
  /** Unidade escanada (VOA) — reenviada no /conferir p/ virar CODVOL no Sankhya. */
  codvol: string | null;
  /** Data URI (base64) — buscada e cacheada no backend (lazy, na primeira vez que o produto aparece). */
  imagemBase64: string | null;
}

/** Resultado de POST /sessoes/{id}/conferir — passo 2 (final): grava + recalcula. */
export interface ItemConferido {
  sequencia: number;
  codprod: number;
  controle: string;
  descricaoProduto: string | null;
  qtdConferidaLocal: string;
  qtdTotalLida: string;
}

/** Modo simplificado (sem dimensão) — só a quantidade total de volumes do pedido, nativo do Sankhya (TGFCON2.QTDVOL). */
export interface Volume {
  quantidade: number;
}

export interface ItemSeparacao {
  sequencia: number;
  codprod: number;
  controle: string;
  codvol: string | null;
  qtdNeg: string;
  qtdEntregue: string;
  qtdConferidaLocal: string;
  descricaoProduto: string | null;
  complementoDescricao: string | null;
  marca: string | null;
  referencia: string | null;
  usaConfPeso: boolean;
  /** Produto fora do pedido (qtd_neg=0, só existe porque foi bipado). */
  foraPedido: boolean;
  /** TGFPRO.AD_TIPOSEPARACAO — 1 Secos | 2 Resfriados | 3 Congelados. Conferência por etapa (V29). */
  tipoSeparacao: number;
  /** Unidades alternativas (TGFVOA) — só display "Pedido: X CX". Magnitude conferida é sempre a padrão. */
  unidadeComercial: string | null;
  unidadePadrao: string | null;
  quantidadeComercial: string | null;
  quantidadePadrao: string | null;
  quantidadeComercialConferida: string | null;
  quantidadePadraoConferida: string | null;
}
