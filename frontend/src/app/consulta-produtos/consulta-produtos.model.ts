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
  estoque: number;
  reservado: number;
  disponivel: number;
  locais: EstoqueLocal[];
}

export interface ConsultaProdutosResposta {
  produtos: ProdutoEstoque[];
  limitado: boolean;
  erroEstoque: string | null;
}
