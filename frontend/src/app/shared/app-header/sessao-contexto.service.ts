import { Injectable, computed, inject } from '@angular/core';
import { AuthService } from '../../auth/auth.service';
import { VERSAO } from './versao.gerada';

export interface OperadorInfo {
  iniciais: string;
  nome: string;
  codigo: string;
  cargo: string;
}

export interface UnidadeInfo {
  iniciais: string;
  nome: string;
  versao: string;
  /** Tooltip: commit e data do build. */
  versaoDetalhe: string;
  unidadeTurno: string;
}

const ROTULO_TURNO: Record<string, string> = { MANHA: 'Manhã', NOITE: 'Noite' };
const ROTULO_PERFIL: Record<string, string> = { ADMINISTRADOR: 'Administrador', OPERADOR: 'Operador', ESTACAO: 'Estação', TV: 'TV' };

function iniciaisDe(nome: string): string {
  const partes = nome.trim().split(/\s+/).filter(Boolean);
  if (partes.length === 0) return '—';
  return (partes[0][0] + (partes[partes.length - 1]?.[0] ?? '')).toUpperCase();
}

/**
 * Dados de identidade (unidade + operador) exibidos no header global — até
 * aqui era 100% fixo/mockado ("Logística Ágil", "João Cavalcante", "CD-SP-03
 * / TURNO A", ver git history) porque nada amarrava o header ao usuário
 * logado de verdade. Agora deriva do AuthService (sessão real, ver
 * auth.service.ts) — "Unidade" é o tenant (slug), "Turno" é o campo novo em
 * app.users (ver V47__usuario_turno.sql / UsuarioListComponent).
 */
/** "Commit 3712a94 · build 28/09/2026 16:40" — vazio no dev. */
function detalheVersao(): string {
  const partes: string[] = [];
  if (VERSAO.commit) partes.push(`Commit ${VERSAO.commit}`);
  if (VERSAO.buildEm) {
    const d = new Date(VERSAO.buildEm);
    if (!isNaN(d.getTime())) {
      partes.push(`build ${d.toLocaleDateString('pt-BR')} ${d.toLocaleTimeString('pt-BR', { hour: '2-digit', minute: '2-digit' })}`);
    }
  }
  return partes.join(' · ') || 'Versão de desenvolvimento';
}

@Injectable({ providedIn: 'root' })
export class SessaoContextoService {
  private readonly auth = inject(AuthService);

  readonly operador = computed<OperadorInfo>(() => {
    const u = this.auth.usuario();
    if (!u) return { iniciais: '—', nome: '—', codigo: '—', cargo: '—' };
    return {
      iniciais: iniciaisDe(u.nome),
      nome: u.nome,
      codigo: u.crachaoCodigo ?? '—',
      cargo: ROTULO_PERFIL[u.perfil] ?? u.perfil,
    };
  });

  readonly unidade = computed<UnidadeInfo>(() => {
    const slug = this.auth.obterTenantSlug();
    const nomeUnidade = slug ? slug.toUpperCase() : '—';
    const turno = this.auth.usuario()?.turno;
    const rotuloTurno = turno ? (ROTULO_TURNO[turno] ?? turno) : 'Sem turno';
    return {
      iniciais: slug ? slug.slice(0, 2).toUpperCase() : '—',
      nome: nomeUnidade,
      versao: `HMI-OPS · v${VERSAO.numero}`,
      versaoDetalhe: detalheVersao(),
      unidadeTurno: `${nomeUnidade} / ${rotuloTurno}`,
    };
  });
}
