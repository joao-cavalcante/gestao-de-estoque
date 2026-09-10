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
  preAlert?: string;
  imagemUrl?: string | null;
  /** TGFVOL.UTILICONFPESO — produto pesável (rotina de peso do projeto base). */
  usaConfPeso?: boolean;
  /** Unidade de cadastro / base (TGFPRO.CODVOL) — a magnitude `expected`/`scanned` está nela. */
  unidadePadrao?: string;
  /** Unidade negociada da linha (TGFITE.CODVOL). Quando != unidadePadrao, mostra "Pedido: X {unidadeComercial}". */
  unidadeComercial?: string;
  /** `expected` convertido pra unidade comercial (TGFVOA DIVIDEMULTIPLICA) — só display. */
  quantidadeComercial?: number;
  /** Produto fora do pedido (qtd_neg=0, só existe porque foi bipado) — ao devolver, some, não volta pra pendentes. */
  foraPedido?: boolean;
  /** TGFPRO.AD_TIPOSEPARACAO — 1 Secos | 2 Resfriados | 3 Congelados. Conferência por etapa (V29). */
  tipoSeparacao?: number;
}

export type ChipTone = 'critical' | 'warning' | 'success' | 'neutral';
export type QtyTone = 'default' | 'muted' | 'critical';
