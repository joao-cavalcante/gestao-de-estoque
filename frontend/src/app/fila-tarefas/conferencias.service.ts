import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable, forkJoin, of } from 'rxjs';
import { catchError, map, switchMap } from 'rxjs/operators';
import { Tarefa, TarefaEtapa } from './tarefa.model';

/** Resposta de POST /api/separacao/etapas-fila — `{ [nunota]: { tipos, concluidos } }`. `{}` = tenant não segmentado. */
interface FilaEtapasResposta {
  [nunota: string]: {
    tipos: number[];
    concluidos: number[];
    /** Concluídos COM divergência (V48). */
    divergentes?: number[];
    progresso?: { [tipo: string]: { total: number; conferidos: number } };
  };
}

/** Resposta de POST /api/separacao/itens-fila — `{ [nunota]: quantidadeDeItens }`. */
interface ItensFilaResposta {
  [nunota: string]: number;
}

/** TGFCAB.AD_TURNOENTREGA — período pra entrega. */
const ROTULO_TURNO_ENTREGA: Record<string, string> = { '1': 'Diurno', '2': 'Noturno', '9': 'Qualquer' };

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
  statusOperacional:
    | 'aguardando_liberacao'
    | 'aguardando'
    | 'andamento'
    | 'aguardando_corte'
    | 'aguardando_finalizacao'
    | 'concluido'
    | 'concluido_divergente'
    | 'aguardando_recontagem'
    | 'recontagem_andamento'
    | 'recontagem_concluida'
    | 'recontagem_concluida_divergente';
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
  /** TGFCAB.AD_TURNOENTREGA — "1" Diurno | "2" Noturno | "9" Qualquer. */
  turnoEntrega: string | null;
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
        const nunotas = tarefas.map((t) => Number(t.numeroUnico)).filter((n) => Number.isFinite(n));
        if (nunotas.length === 0) return of(tarefas);
        // Duas bateladas em paralelo — nenhuma bloqueia a fila se falhar:
        // etapas (V29, tenant sem o módulo devolve `{}`) e contagem de itens
        // (card "Itens: N").
        return forkJoin({
          etapas: this.http
            .post<FilaEtapasResposta>('/api/separacao/etapas-fila', { nunotas }, { params: { tenant } })
            .pipe(catchError(() => of<FilaEtapasResposta>({}))),
          itens: this.http
            .post<ItensFilaResposta>('/api/separacao/itens-fila', { nunotas }, { params: { tenant } })
            .pipe(catchError(() => of<ItensFilaResposta>({}))),
        }).pipe(
          map(({ etapas, itens }) => mergeItens(mergeEtapas(tarefas, etapas), itens)),
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

// Agrupa os 11 códigos reais do Sankhya (mais granulares) nos 4 buckets que a
// fila já usa pra filtro/KPI/ícone — a tela é operacional, não precisa de 11
// pills. O código granular original continua disponível em statusSankhya, se
// algum card quiser mostrar o rótulo fino (ex. "Recontagem em andamento").
const STATUS_MAP: Record<TarefaApiDto['statusOperacional'], Tarefa['status']> = {
  aguardando_liberacao: 'aguardando',
  aguardando: 'aguardando',
  aguardando_recontagem: 'aguardando',
  andamento: 'andamento',
  recontagem_andamento: 'andamento',
  aguardando_finalizacao: 'andamento',
  aguardando_corte: 'aguardando_corte',
  concluido: 'concluido',
  concluido_divergente: 'concluido',
  recontagem_concluida: 'concluido',
  recontagem_concluida_divergente: 'concluido',
};

function mapearParaTarefa(p: TarefaApiDto): Tarefa {
  const nf = p.numeroNota ? `NF-${String(p.numeroNota).padStart(6, '0')}` : '—';
  return {
    id: String(p.nunota),
    cliente: p.nomeParceiro ?? '—',
    codigoCliente: p.codigoParceiro,
    status: STATUS_MAP[p.statusOperacional],
    statusOperacional: p.statusOperacional,
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
    itens: 0, // preenchido depois por mergeItens (POST /api/separacao/itens-fila)
    valor: 0, // não faz parte do fieldset base da fila
    periodoEntrega: (p.turnoEntrega && ROTULO_TURNO_ENTREGA[p.turnoEntrega]) || '—',
  };
}

/** Preenche `itens` com a contagem real (POST /api/separacao/itens-fila) — falha/ausência vira 0. */
function mergeItens(tarefas: Tarefa[], itensPorNota: ItensFilaResposta): Tarefa[] {
  return tarefas.map((t) => ({ ...t, itens: itensPorNota[t.numeroUnico] ?? 0 }));
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
        divergente: info.divergentes?.includes(tipo) ?? false,
        total: p?.total ?? 0,
        conferidos: p?.conferidos ?? 0,
      };
    });
    return { ...t, etapas };
  });
}
