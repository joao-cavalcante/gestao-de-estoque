/**
 * Painel Master (administração da PLATAFORMA) — tipos da API /api/master. A identidade Master é separada da do
 * tenant: token próprio (escopo "plataforma"), nunca um perfil de usuário de cliente.
 */

export interface MasterUsuario {
  id: string;
  nome: string;
  email: string;
}

export interface LoginMasterResponse {
  token: string;
  master: MasterUsuario;
}

export interface Modulo {
  id: string;
  descricao: string;
}

export interface Instalacao {
  build: string | null;
  commit: string | null;
  modelo: string;
  modulos: Modulo[];
}

export interface ToleranciaPadrao {
  acimaPct: number | null;
  acimaSemLimite: boolean;
  abaixoPct: number | null;
  abaixoSemLimite: boolean;
}

export interface ParametrosTenant {
  liberacaoHerdada: { eventos: number[]; compararValor: boolean };
  liberador: { fallbackVariavelAmbiente: boolean };
  toleranciaPesavel: { semConfiguracaoNucco: ToleranciaPadrao | null };
  fuso: string | null;
  usuarioSankhya: {
    habilitado: boolean;
    usuarioModelo: number | null;
    codGrupo: number | null;
    dominioEmail: string | null;
    camposFixos: Record<string, string>;
  };
  ajustePesoCompra: {
    habilitado: boolean;
    desconsiderarAcimaPct: number | null;
    desconsiderarAbaixoPct: number | null;
    ajusteEntrada: boolean;
    ajusteSaida: boolean;
    topEntrada: number | null;
    topSaida: number | null;
    serieEntrada: string | null;
    serieSaida: string | null;
  };
}

export interface ErpConnection {
  erpType: string;
  baseUrl: string;
  gatewayPath: string | null;
  dialect: string | null;
  ativo: boolean;
  credenciaisConfiguradas: boolean;
  modulos: string[];
  parametros: ParametrosTenant;
  liberadorConfigurado: boolean;
}

export type StatusTenant = 'trial' | 'active' | 'suspended' | 'cancelled';

export interface Tenant {
  id: string;
  slug: string;
  nome: string;
  tier: string;
  status: StatusTenant;
  erpConnections: ErpConnection[];
}

export interface SituacaoSync {
  ultimoSucessoEm: string | null;
  falhasConsecutivas: number;
  ultimoErro: string | null;
}

export interface TenantMaster {
  tenant: Tenant;
  sync: SituacaoSync | null;
}

export interface UsuarioTenant {
  nome: string;
  email: string;
  perfil: string;
  nivel: 'ADMIN_TENANT' | 'TV' | 'USUARIO_OPERACIONAL';
  ativo: boolean;
  codusuSankhya: number | null;
}

export interface ItemDiagnostico {
  grupo: string;
  titulo: string;
  situacao: 'OK' | 'ALERTA' | 'ERRO' | 'NAO_VERIFICADO';
  detalhe: string;
}

export interface Diagnostico {
  tenantId: string;
  tenantSlug: string;
  executadoEm: string;
  itens: ItemDiagnostico[];
}

export interface RegistroAuditoria {
  id: number;
  ocorridoEm: string;
  atorEmail: string | null;
  tenantSlug: string | null;
  acao: string;
  resultado: 'ok' | 'negado' | 'erro';
  detalhe: Record<string, unknown>;
  ip: string | null;
}

export interface CampoPersonalizado {
  tabela: string;
  campo: string;
  uso: string;
  modulo: string | null;
  gravacao: boolean;
}

export const STATUS_ROTULO: Record<StatusTenant, string> = {
  trial: 'IMPLANTAÇÃO',
  active: 'ATIVO',
  suspended: 'DESATIVADO',
  cancelled: 'CANCELADO',
};

/** Nome da ação da trilha de auditoria, como a equipe lê. */
export const ACAO_ROTULO: Record<string, string> = {
  login: 'Acesso ao painel',
  'tenant.criar': 'Cliente cadastrado',
  'tenant.dados_gerais': 'Dados gerais alterados',
  'tenant.status': 'Situação alterada',
  'tenant.integracao': 'Integração Sankhya alterada',
  'tenant.integracao.testar': 'Integração testada',
  'tenant.modulos': 'Módulos alterados',
  'tenant.parametros': 'Parâmetros alterados',
  'tenant.liberador.gravar': 'Credencial do liberador gravada',
  'tenant.liberador.remover': 'Credencial do liberador removida',
  'tenant.diagnostico': 'Diagnóstico executado',
};
