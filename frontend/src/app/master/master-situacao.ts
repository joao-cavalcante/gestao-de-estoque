import { ChipTone } from '../conferencia/conferencia.model';
import { StatusTenant, TenantMaster } from './master.model';

/** Situação de cliente e integração — mesmas regras na lista e no detalhe. */
export function tomStatus(status: StatusTenant): ChipTone {
  if (status === 'active') return 'success';
  if (status === 'trial') return 'warning';
  return 'critical';
}

export function tomIntegracao(t: TenantMaster): ChipTone {
  const conexao = t.tenant.erpConnections.find((c) => c.erpType === 'sankhya');
  if (!conexao || !conexao.credenciaisConfiguradas) return 'neutral';
  if (!conexao.ativo) return 'warning';
  if (t.sync && t.sync.falhasConsecutivas > 0) return 'critical';
  return t.sync?.ultimoSucessoEm ? 'success' : 'warning';
}

export function textoIntegracao(t: TenantMaster): string {
  const conexao = t.tenant.erpConnections.find((c) => c.erpType === 'sankhya');
  if (!conexao || !conexao.credenciaisConfiguradas) return 'SEM INTEGRAÇÃO';
  if (!conexao.ativo) return 'INTEGRAÇÃO DESLIGADA';
  if (t.sync && t.sync.falhasConsecutivas > 0) return 'SYNC COM FALHA';
  return t.sync?.ultimoSucessoEm ? 'SINCRONIZANDO' : 'AGUARDANDO SYNC';
}

/** "30/10 14:32" no fuso de Brasília. */
export function dataHora(iso: string | null | undefined): string {
  if (!iso) return '—';
  const d = new Date(iso);
  if (Number.isNaN(d.getTime())) return iso;
  return d.toLocaleString('pt-BR', { timeZone: 'America/Sao_Paulo', day: '2-digit', month: '2-digit', year: 'numeric', hour: '2-digit', minute: '2-digit' });
}
