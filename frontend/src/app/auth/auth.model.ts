export interface Usuario {
  id: string;
  nome: string;
  email: string;
  perfil: string;
  ativo: boolean;
  crachaoCodigo?: string | null;
  /** 'MANHA' | 'NOITE' | null — exibido no header global ("Unidade / Turno"). */
  turno?: string | null;
}

export interface LoginResponse {
  token: string;
  usuario: Usuario;
  tenantSlug: string;
}
