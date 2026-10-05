export type ItemStatus = 'pending' | 'ok' | 'critical' | 'warning';

export interface ConferenciaItem {
  seq: number;
  code: string;
  name: string;
  control: string;
  expected: number;
  scanned: number;
  status: ItemStatus;
  divergenceReason?: string;
  imagemUrl?: string | null;
  /** Produto pesável (backend RegraPesavel: unidade TGFVOL, ou TGFPRO.AD_PESAVEL com o módulo) — rotina de peso do projeto base. */
  usaConfPeso?: boolean;
  /** Unidade de cadastro / base (TGFPRO.CODVOL) — a magnitude `expected`/`scanned` está nela. */
  unidadePadrao?: string;
  /** Unidade negociada da linha (TGFITE.CODVOL). Quando != unidadePadrao, mostra "Pedido: X {unidadeComercial}". */
  unidadeComercial?: string;
  /** `expected` convertido pra unidade comercial (TGFVOA DIVIDEMULTIPLICA) — só display. */
  quantidadeComercial?: number;
  /** Observação "CX com 12 BI" — quantas unidades base cabem na unidade do pedido. Só display. */
  conversao?: string;
  /** Produto fora do pedido (qtd_neg=0, só existe porque foi bipado) — ao devolver, some, não volta pra pendentes. */
  foraPedido?: boolean;
  /** Item pesável cujo peso conferido saiu da tolerância da sessão (acima/abaixo, V50) — divergência de PESO (indicador visual próprio). */
  divergenciaPeso?: boolean;
  /** Desvio SIGNED do peso conferido vs. esperado, em % (+ maior, - menor) — presente pra TODO item pesável já conferido, não só quando diverge. */
  desvioPesoPct?: number;
  /** Pesável pesado A MENOR mas dentro da tolerância de baixo da sessão — continua em Pendentes
   *  (regra de 29/09), mas não é divergência: o corte é liberado sozinho no backend (autoLiberarPesoDentroTolerancia). */
  pesoNaTolerancia?: boolean;
  /** TGFPRO.AD_TIPOSEPARACAO — 1 Secos | 2 Resfriados | 3 Congelados. Conferência por etapa (V29). */
  tipoSeparacao?: number;
}

export type ChipTone = 'critical' | 'warning' | 'success' | 'neutral';
export type QtyTone = 'default' | 'muted' | 'critical';
