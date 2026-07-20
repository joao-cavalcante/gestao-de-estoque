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
}
