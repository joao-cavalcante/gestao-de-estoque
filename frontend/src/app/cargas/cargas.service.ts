import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';

/** Tela Cargas (24/11/2026) — GET /api/cargas. A regra (pendente/pronta/saiu) fica no backend (CargasRegras). */
export type SituacaoCarga = 'pendente' | 'pronta' | 'saiu';
export type SituacaoPedido = 'corte' | 'a_conferir' | 'em_conferencia' | 'a_carregar' | 'carregado' | 'com_nota';
export type DiaSaida = 'hoje' | 'ontem';

export interface Faltas {
  corte: number;
  aConferir: number;
  emConferencia: number;
  aCarregar: number;
}

export interface PedidoCarga {
  nunota: number;
  cliente: string | null;
  pesoKg: number;
  volumes: number | null;
  situacao: SituacaoPedido;
  express: boolean;
  retira: boolean;
  notaNumero: number | null;
  notaSituacao: string | null;
  notaStatusNfe: string | null;
}

export interface Carga {
  ordemCarga: number;
  situacao: SituacaoCarga;
  motorista: string | null;
  placa: string | null;
  veiculo: string | null;
  /** yyyy-MM-dd */
  saidaPrevista: string | null;
  pesoMaxKg: number | null;
  pesoKg: number;
  volumes: number | null;
  clientes: number;
  prontos: number;
  express: boolean;
  retira: boolean;
  faltas: Faltas;
  saiuEm: string | null;
  fechadaPor: string | null;
  impressao: { status: string; erro: string | null; impressoEm: string | null } | null;
  notas: { total: number; aprovadas: number; aguardando: number; problema: number };
  pedidos: PedidoCarga[];
}

export interface PainelCargas {
  atualizadoEm: string;
  diaSaida: string;
  cargas: Carga[];
}

@Injectable({ providedIn: 'root' })
export class CargasService {
  private readonly http = inject(HttpClient);

  painel(dia: DiaSaida): Observable<PainelCargas> {
    return this.http.get<PainelCargas>('/api/cargas', { params: { dia } });
  }
}

/** "1 corte aguardando liberação · 2 a conferir · 1 em conferência · 3 a carregar". */
export function textoFaltas(f: Faltas): string {
  const partes: string[] = [];
  if (f.corte) partes.push(`${f.corte} ${f.corte === 1 ? 'corte aguardando' : 'cortes aguardando'} liberação`);
  if (f.aConferir) partes.push(`${f.aConferir} a conferir`);
  if (f.emConferencia) partes.push(`${f.emConferencia} em conferência`);
  if (f.aCarregar) partes.push(`${f.aCarregar} a carregar`);
  return partes.join(' · ');
}

export function pesoTexto(kg: number): string {
  return kg >= 1000
    ? `${(kg / 1000).toLocaleString('pt-BR', { maximumFractionDigits: 2 })} t`
    : `${kg.toLocaleString('pt-BR', { maximumFractionDigits: 1 })} kg`;
}

export const ROTULO_PEDIDO: Record<SituacaoPedido, string> = {
  corte: 'Aguardando corte',
  a_conferir: 'Aguardando conferência',
  em_conferencia: 'Em conferência',
  a_carregar: 'Aguardando carregamento',
  carregado: 'Carregado',
  com_nota: 'Com nota',
};

const ROTULO_IMPRESSAO: Record<string, string> = {
  aguardando_sefaz: 'Notas aguardando a SEFAZ',
  pronto: 'Impressão na fila',
  imprimindo: 'Imprimindo',
  impresso: 'Impresso',
  erro: 'Erro na impressão',
};

export function rotuloImpressao(status: string): string {
  return ROTULO_IMPRESSAO[status] ?? status;
}
