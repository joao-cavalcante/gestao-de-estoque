import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable, of } from 'rxjs';
import { catchError, map, switchMap } from 'rxjs/operators';
import { Tarefa, TarefaEtapa } from './tarefa.model';

/** Resposta de POST /api/separacao/etapas-fila — `{ [nunota]: { tipos, concluidos } }`. `{}` = tenant não segmentado. */
interface FilaEtapasResposta {
  [nunota: string]: {
    tipos: number[];
    concluidos: number[];
    progresso?: { [tipo: string]: { total: number; conferidos: number } };
  };
}

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
  statusOperacional: 'aguardando' | 'andamento' | 'aguardando_corte' | 'concluido' | 'cancelado';
  statusSankhya: string;
  numeroNota: number | null;
  codigoParceiro: string | null;
  nomeParceiro: string | null;
  codigoVendedor: string | null;
  apelidoVendedor: string | null;
  dataMovimento: string | null;
  codigoTipoOperacao: string | null;
  descricaoTipoOperacao: string | null;
  /** TGFCAB.ORDEMCARGA — número da ordem/onda de carga. */
  ordemCarga: number | null;
  /** Base pro indicador de sincronização da UI ("dados de Xs atrás"). */
  segundosDesdeSync: number;
  pendenteWriteBack: boolean;
}

@Injectable({ providedIn: 'root' })
export class ConferenciasService {
  private readonly http = inject(HttpClient);
  private readonly baseUrl = '/api/tarefas';

  listarFila(tenant: string): Observable<Tarefa[]> {
    return this.http.get<TarefaApiDto[]>(this.baseUrl, { params: { tenant } }).pipe(
      map((tarefas) => tarefas.map(mapearParaTarefa)),
      switchMap((tarefas) => {
        // Conferência por etapa (V29): pergunta ao backend o breakdown de tipos
        // de separação. Tenant sem o módulo devolve `{}` → nada muda.
        const nunotas = tarefas.map((t) => Number(t.numeroUnico)).filter((n) => Number.isFinite(n));
        if (nunotas.length === 0) return of(tarefas);
        return this.http
          .post<FilaEtapasResposta>('/api/separacao/etapas-fila', { nunotas }, { params: { tenant } })
          .pipe(
            map((etapasPorNota) => mergeEtapas(tarefas, etapasPorNota)),
            catchError(() => of(tarefas)),
          );
      }),
    );
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
  aguardando_corte: 'aguardando_corte',
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
    ordemCarga: p.ordemCarga,
    itens: 0, // exige query extra (TGFITE) — não incluída nesta integração
    valor: 0, // não faz parte do fieldset base da fila
  };
}

/** Anexa `etapas` às tarefas segmentadas (tipos presentes na nota + quais já concluídos). */
function mergeEtapas(tarefas: Tarefa[], etapasPorNota: FilaEtapasResposta): Tarefa[] {
  if (!etapasPorNota || Object.keys(etapasPorNota).length === 0) return tarefas;
  return tarefas.map((t) => {
    const info = etapasPorNota[t.numeroUnico];
    if (!info || info.tipos.length === 0) return t;
    const etapas: TarefaEtapa[] = info.tipos.map((tipo) => {
      const p = info.progresso?.[String(tipo)];
      return {
        tipo,
        status: info.concluidos.includes(tipo) ? 'C' : 'P',
        total: p?.total ?? 0,
        conferidos: p?.conferidos ?? 0,
      };
    });
    return { ...t, etapas };
  });
}
