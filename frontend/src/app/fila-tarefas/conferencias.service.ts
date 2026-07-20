import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import { map } from 'rxjs/operators';
import { Tarefa } from './tarefa.model';

/**
 * DTO cru devolvido por GET /api/tarefas (TarefasRoutes.kt) — lido
 * EXCLUSIVAMENTE da base local (Postgres), nunca aciona o Sankhya na hora
 * da requisição. Quem mantém isso atualizado é o job de sync em background
 * (TarefaSyncScheduler), que reconcilia o status operacional local contra
 * o status de negócio do Sankhya a cada ciclo.
 */
export interface TarefaApiDto {
  nunota: number;
  tipo: string;
  statusOperacional: 'aguardando' | 'andamento' | 'concluido' | 'cancelado';
  statusSankhya: string;
  numeroNota: number | null;
  codigoParceiro: string | null;
  nomeParceiro: string | null;
  codigoVendedor: string | null;
  apelidoVendedor: string | null;
  dataMovimento: string | null;
  codigoTipoOperacao: string | null;
  descricaoTipoOperacao: string | null;
  /** Base pro indicador de sincronização da UI ("dados de Xs atrás"). */
  segundosDesdeSync: number;
  pendenteWriteBack: boolean;
}

@Injectable({ providedIn: 'root' })
export class ConferenciasService {
  private readonly http = inject(HttpClient);
  private readonly baseUrl = '/api/tarefas';

  listarFila(tenant: string): Observable<Tarefa[]> {
    return this.http
      .get<TarefaApiDto[]>(this.baseUrl, { params: { tenant } })
      .pipe(map((tarefas) => tarefas.map(mapearParaTarefa)));
  }

  concluir(tenant: string, nunota: number, operador: string): Observable<{ ok: boolean; pendenteWriteBack: boolean }> {
    return this.http.post<{ ok: boolean; pendenteWriteBack: boolean }>(
      `${this.baseUrl}/${nunota}/concluir`,
      { operador },
      { params: { tenant } },
    );
  }
}

const STATUS_MAP: Record<TarefaApiDto['statusOperacional'], Tarefa['status']> = {
  aguardando: 'aguardando',
  andamento: 'andamento',
  concluido: 'concluido',
  // O card ainda não tem um visual dedicado pra "cancelado" — cai em
  // "concluído" (fora da fila ativa) até essa distinção ser desenhada.
  cancelado: 'concluido',
};

function mapearParaTarefa(p: TarefaApiDto): Tarefa {
  const nf = p.numeroNota ? `NF-${String(p.numeroNota).padStart(6, '0')}` : '—';
  return {
    id: String(p.nunota),
    cliente: p.nomeParceiro ?? '—',
    codigoCliente: p.codigoParceiro,
    status: STATUS_MAP[p.statusOperacional],
    alerta: null, // sem rastreamento de divergência/SLA local ainda
    pedido: nf,
    numeroUnico: String(p.nunota),
    nf,
    data: p.dataMovimento ?? '—',
    transporte: '—', // campo AD_ (AD_TIPOENTREGA) — fora de escopo por enquanto
    responsavel: p.apelidoVendedor ?? '—',
    codigoResponsavel: p.codigoVendedor,
    tipoOperacao: p.descricaoTipoOperacao ?? '—',
    codigoTipoOperacao: p.codigoTipoOperacao,
    itens: 0, // exige query extra (TGFITE) — não incluída nesta integração
    valor: 0, // não faz parte do fieldset base da fila
  };
}
