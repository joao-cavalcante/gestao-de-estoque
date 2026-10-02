export interface EstoqueLocal {
  codemp: number | null;
  empresa: string | null;
  codlocal: number | null;
  local: string | null;
  controle: string | null;
  estoque: number;
  reservado: number;
  disponivel: number;
}

export interface ProdutoEstoque {
  codprod: number;
  descricao: string;
  complemento: string | null;
  marca: string | null;
  referencia: string | null;
  /** Unidade padrão — o saldo da instância Estoque é sempre nela. */
  unidade: string | null;
  /** Mesma regra da conferência; null = não deu pra decidir. */
  pesavel: boolean | null;
  codigosBarra: string[];
  estoque: number;
  reservado: number;
  disponivel: number;
  locais: EstoqueLocal[];
}

export interface ConsultaProdutosResposta {
  produtos: ProdutoEstoque[];
  /** ISO-8601 — quando o saldo foi lido do Sankhya. */
  estoqueLidoEm: string | null;
  erroEstoque: string | null;
}

export type FiltroSaldo = 'todos' | 'com-estoque' | 'sem-estoque' | 'com-reservado' | 'disp-negativo';

export type FiltroPesavel = 'todos' | 'sim' | 'nao';

export type CampoOrdenacaoProduto =
  | 'codprod' | 'descricao' | 'referencia' | 'marca' | 'unidade' | 'pesavel' | 'estoque' | 'reservado' | 'disponivel';

export interface OrdenacaoProduto {
  campo: CampoOrdenacaoProduto;
  direcao: 'asc' | 'desc';
}
