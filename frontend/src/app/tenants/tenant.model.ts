/** Leitura — nunca traz segredo em texto plano, só se está configurado ou não. */
export interface ErpConnection {
  erpType: string;
  baseUrl: string;
  gatewayPath?: string | null;
  dialect?: string | null;
  ativo: boolean;
  credenciaisConfiguradas: boolean;
}

/** Escrita — vai em texto plano só nesta chamada; o backend cifra antes de gravar. */
export interface ErpConnectionInput {
  erpType: string;
  baseUrl: string;
  gatewayPath?: string | null;
  dialect?: string | null;
  ativo: boolean;
  /** null = "não mexe no segredo já salvo" (edição sem preencher os campos de novo). */
  credenciais: string | null;
}

export interface Tenant {
  id?: string;
  slug: string;
  nome: string;
  tier: 'shared' | 'dedicated';
  status: 'trial' | 'active' | 'suspended' | 'cancelled';
  dedicatedDbUrl?: string | null;
  erpConnections: ErpConnection[];
}

export interface CriarTenantRequest {
  slug: string;
  nome: string;
  tier: 'shared' | 'dedicated';
  dedicatedDbUrl?: string | null;
  erpConnections: ErpConnectionInput[];
}

export interface AtualizarTenantRequest {
  nome?: string;
  status?: string;
  tier?: string;
  dedicatedDbUrl?: string | null;
}
