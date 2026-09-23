export interface Usuario {
  id: string;
  nome: string;
  email: string;
  perfil: string;
  ativo: boolean;
  crachaoCodigo: string | null;
  /** 'MANHA' | 'NOITE' | null — exibido no header global ("Unidade / Turno"). */
  turno: string | null;
}

export interface CriarUsuarioRequest {
  nome: string;
  email: string;
  senha: string;
  perfil: string;
  turno?: string | null;
}
