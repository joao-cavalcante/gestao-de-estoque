import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';

/** Situação do recebimento (backend: entrada/EntradaDtos.kt → SituacaoRecebimento). */
export type SituacaoRecebimento =
  | 'aguardando_conferencia'
  | 'em_conferencia'
  | 'divergencia'
  | 'aguardando_liberacao'
  | 'liberada';

export interface NotaDerivada {
  nunota: number;
  numero?: number | null;
  confirmada: boolean;
}

export interface AjustePeso {
  sentido: 'E' | 'S' | string;
  status: string;
  nunotaAjuste?: number | null;
  erro?: string | null;
}

export interface Recebimento {
  nunota: number;
  numeroNota?: number | null;
  codigoParceiro?: string | null;
  fornecedor?: string | null;
  codigoEmpresa?: string | null;
  nomeEmpresa?: string | null;
  dataMovimento?: string | null;
  codigoTipoOperacao?: string | null;
  tipoOperacao?: string | null;
  tipMov?: string | null;
  statusConferencia: string;
  situacao: SituacaoRecebimento;
  pesoBruto?: number | null;
  andamento?: { conferidos: number; total: number; conferente?: string | null; desde: string } | null;
  ajustesPeso?: AjustePeso[] | null;
  notaConfirmada?: boolean | null;
  /** Compra confirmada e ajuste de peso ainda não emitido (salvaguarda 25/11/2026). */
  ajustePendente?: boolean;
  devolucao?: NotaDerivada | null;
  complemento?: NotaDerivada | null;
  segundosDesdeSync: number;
}

export type TipoItemMesa = 'ok' | 'falta' | 'sobra' | 'ajuste' | 'fora_pedido';

export interface ItemMesa {
  sequencia: number;
  codprod: number;
  produto: string;
  controle?: string | null;
  unidade?: string | null;
  qtdNota: number;
  qtdConferida: number;
  diferenca: number;
  pesavel: boolean;
  ajustePeso?: number | null;
  tipo: TipoItemMesa;
}

export interface MesaLiberacao {
  recebimento: Recebimento;
  itens: ItemMesa[];
  conferenciaNaTorre: boolean;
  conferente?: string | null;
  conferenciaInicio?: string | null;
  conferenciaFim?: string | null;
  recontagem: boolean;
  situacaoLidaEm?: string | null;
  aprovacaoDisponivel: boolean;
}

export interface AprovacaoRecebimento {
  situacao: 'APROVADO' | 'LIBERACAO_PENDENTE';
  jaExistia: boolean;
  liberacoes: string[];
}

export type SituacaoArmazenagem = 'aguardando' | 'erro' | 'na_doca' | 'guardando' | 'guardado';

export interface ItemArmazenagem {
  nutar: number;
  codprod: number;
  descricao: string;
  unidade: string | null;
  qtd: number;
  qtdFeita: number;
  situacao: string;
  origem: string | null;
  sugerido: string | null;
  guardadoEm: string | null;
  motivo: string | null;
  operador: string | null;
  concluidaEm: string | null;
}

export interface CompraArmazenagem {
  nunota: number;
  numnota: number | null;
  fornecedor: string | null;
  codemp: number | null;
  aprovadaEm: string | null;
  situacao: SituacaoArmazenagem;
  erro: string | null;
  itens: ItemArmazenagem[];
}

export const ROTULO_ARMAZENAGEM: Record<SituacaoArmazenagem, string> = {
  aguardando: 'Aguardando as tarefas',
  erro: 'Erro ao gerar as tarefas',
  na_doca: 'Na doca',
  guardando: 'Guardando',
  guardado: 'Guardado',
};

/** Tela Entrada (recebimentos de compra) e mesa de liberação; "Aprovar" pelo addon TOR desde 23/11/2026. */
@Injectable({ providedIn: 'root' })
export class EntradaService {
  private readonly http = inject(HttpClient);

  /** Aba Armazenagem (27/11/2026): andamento da armazenagem por compra (addon TOR, só leitura). */
  listarArmazenagem(): Observable<CompraArmazenagem[]> {
    return this.http.get<CompraArmazenagem[]>('/api/armazenagem/compras');
  }

  listar(): Observable<Recebimento[]> {
    return this.http.get<Recebimento[]>('/api/entrada');
  }

  mesa(nunota: number): Observable<MesaLiberacao> {
    return this.http.get<MesaLiberacao>(`/api/entrada/${nunota}/mesa`);
  }

  /** Aprovar o recebimento (etapa 2): o addon TOR confirma a compra em nome do liberador. */
  /** Salvaguarda: tenta de novo, na hora, o ajuste de peso que não saiu. Devolve a mesa atualizada. */
  gerarAjuste(nunota: number): Observable<MesaLiberacao> {
    return this.http.post<MesaLiberacao>(`/api/entrada/${nunota}/ajuste-peso/gerar`, {});
  }

  aprovar(nunota: number, usuario: string, senha: string): Observable<AprovacaoRecebimento> {
    return this.http.post<AprovacaoRecebimento>(`/api/entrada/${nunota}/aprovar`, { usuario, senha });
  }
}

// ─── Textos e regras de exibição (compartilhados pela lista e pela mesa) ─────────────────────────────────

export const ROTULO_SITUACAO: Record<SituacaoRecebimento, string> = {
  aguardando_conferencia: 'Aguardando conferência',
  em_conferencia: 'Em conferência',
  divergencia: 'Divergência',
  aguardando_liberacao: 'Aguardando liberação',
  liberada: 'Liberada',
};

/** Ícone por situação — a forma comunica, não só a cor (padrão da Torre). */
export const ICONE_SITUACAO: Record<SituacaoRecebimento, 'clock' | 'circle' | 'triangle' | 'handshake' | 'check'> = {
  aguardando_conferencia: 'clock',
  em_conferencia: 'circle',
  divergencia: 'triangle',
  aguardando_liberacao: 'handshake',
  liberada: 'check',
};

export function rotuloAjuste(a: AjustePeso): string {
  const sentido = a.sentido === 'E' ? 'Ajuste de entrada' : 'Ajuste de saída';
  const nota = a.nunotaAjuste ? ` · nº único ${a.nunotaAjuste}` : '';
  const status: Record<string, string> = {
    pendente: 'aguardando a nota ser confirmada',
    criando: 'gerando',
    cabecalho: 'gerando',
    confirmado: 'gerado',
    erro: 'parou em erro',
  };
  return `${sentido}: ${status[a.status] ?? a.status}${nota}`;
}

/** "dd/MM/yyyy HH:mm:ss" (Sankhya) → "dd/MM". */
export function ddmm(data?: string | null): string {
  const m = /^(\d{2})\/(\d{2})/.exec((data ?? '').trim());
  return m ? `${m[1]}/${m[2]}` : '—';
}

export function pesoTexto(kg?: number | null): string {
  if (kg == null || !(kg > 0)) return '—';
  if (kg >= 1000) return `${(kg / 1000).toLocaleString('pt-BR', { maximumFractionDigits: 2 })} t`;
  return `${kg.toLocaleString('pt-BR', { maximumFractionDigits: 1 })} kg`;
}

export function qtdTexto(v: number): string {
  return v.toLocaleString('pt-BR', { maximumFractionDigits: 3 });
}
