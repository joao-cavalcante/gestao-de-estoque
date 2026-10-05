/**
 * Módulos (feature flags) por-tenant — conceito do WMS, ligado só pela
 * plataforma pra isolar comportamento que só um cliente usa. Espelha
 * wms.backend.tenancy.Modulos.
 */
export const MODULOS_DISPONIVEIS = [
  { id: 'conferencia_segmentada', label: 'Conferência segmentada' },
  { id: 'pesavel_por_produto', label: 'Pesável pelo produto (TGFPRO.AD_PESAVEL, não pela unidade)' },
  { id: 'sem_verificacao_pedido', label: 'NÃO conferir o pedido com o Sankhya ao concluir etapa/finalizar (mais rápido, menos seguro)' },
] as const;

/** Leitura — nunca traz segredo em texto plano, só se está configurado ou não. */
export interface ErpConnection {
  erpType: string;
  baseUrl: string;
  gatewayPath?: string | null;
  dialect?: string | null;
  ativo: boolean;
  credenciaisConfiguradas: boolean;
  modulos: string[];
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
  /** Lista completa de módulos habilitados — substitui a lista salva. */
  modulos: string[];
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
