export interface Usuario {
  id: string;
  nome: string;
  email: string;
  perfil: string;
  ativo: boolean;
}

export interface LoginResponse {
  token: string;
  usuario: Usuario;
  tenantSlug: string;
}
