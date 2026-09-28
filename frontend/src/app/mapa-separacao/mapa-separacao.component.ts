import { Component, OnDestroy, OnInit, computed, inject, signal } from '@angular/core';
import { NgTemplateOutlet } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { Subscription, from } from 'rxjs';
import { mergeMap, toArray } from 'rxjs/operators';
import { SyncTickService } from '../shared/app-header/sync-tick.service';
import { FiltrosSalvosService } from '../shared/filtros-salvos.service';
import { OqIconComponent, OqIconName } from '../shared/icons/oq-icon.component';
import { OqSpinnerComponent } from '../shared/icons/oq-spinner.component';
import { MapaSeparacaoService } from './mapa-separacao.service';
import {
  CategoriaSeparacaoDto,
  MapaSeparacaoDto,
  OrdemCargaResumoDto,
  PedidoSemOrdemCargaDto,
  formatarPeso,
  formatarQtd,
} from './mapa-separacao.model';

const ICONE_CATEGORIA: Record<string, OqIconName> = {
  '1': 'seco',
  '2': 'refrigerado',
  '3': 'congelado',
  '0': 'circle-alert',
};

type FiltroStatus = 'todas' | 'pendentes' | 'concluidas';

/** Quantos mapas S/ OC buscar ao mesmo tempo no Sankhya (cada um é ~3 chamadas). */
const CONCORRENCIA_MAPAS = 3;

/**
 * Mapa de Separação por Ordem de Carga — porte do Dashboard HTML5/JSP que
 * substituiu o iReport 513 no Sankhya (ver backend MapaSeparacaoService).
 * Classificação por TGFPRO.AD_TIPOSEPARACAO como no original, mas SEM a
 * quebra por nota (economia de papel): seco e congelado saem somados na OC
 * inteira, uma folha cada; refrigerado sai uma folha por cliente. Pesável
 * leva ícone de balança. Identidade visual do WMS (tokens de
 * styles.scss, ícones seco/refrigerado/congelado já usados na Fila de
 * Tarefas) em vez do CSS solto do JSP original.
 *
 * TELA DE CONTROLE, não busca solta: abre já mostrando as Ordens de Carga
 * ABERTAS (SITUACAO='A' — ainda precisam ser separadas; "fechada" já foi
 * processada/embarcada) como cards (mesmo idioma visual de Fila de
 * Tarefas/Liberação de Corte/Impressão de Etiquetas) — o operador vê de
 * cara quantas tem pra separar, clica na que quer e vai direto pro
 * relatório/impressão. Busca ao vivo, sem cache/mirror.
 *
 * Filtro "S/ Ordem de Carga": troca o painel pelos PEDIDOS sem OC. Cada
 * Número Único vira um mapa próprio (mesmas categorias, soma só dentro do
 * pedido) — vários selecionados saem como mapas independentes, uma folha
 * nova por pedido, nunca consolidados.
 */
@Component({
  selector: 'app-mapa-separacao',
  standalone: true,
  imports: [FormsModule, OqIconComponent, OqSpinnerComponent, NgTemplateOutlet],
  templateUrl: './mapa-separacao.component.html',
  styleUrl: './mapa-separacao.component.scss',
})
export class MapaSeparacaoComponent implements OnInit, OnDestroy {
  private readonly service = inject(MapaSeparacaoService);
  private readonly syncTick = inject(SyncTickService);
  private readonly filtrosSalvos = inject(FiltrosSalvosService);

  /** Status e "S/ Ordem de Carga" lembrados por usuário (busca por texto não é lembrada); padrão = pendentes, com OC. */
  private filtrosSalvosLidos(): { status: FiltroStatus; semOrdemCarga: boolean } {
    const salvo = this.filtrosSalvos.ler<{ status: string; semOrdemCarga?: boolean }>('mapa-separacao');
    const s = salvo?.status;
    return {
      status: s === 'todas' || s === 'concluidas' || s === 'pendentes' ? s : 'pendentes',
      semOrdemCarga: salvo?.semOrdemCarga === true,
    };
  }

  private salvarFiltros(): void {
    this.filtrosSalvos.salvar('mapa-separacao', { status: this.filtroStatus, semOrdemCarga: this.semOrdemCarga });
  }

  onFiltroStatusChange(): void {
    this.salvarFiltros();
  }

  onSemOrdemCargaChange(): void {
    this.salvarFiltros();
    this.selecionados.set(new Set());
    this.erro.set(null);
    this.carregarPainel();
  }
  private syncSub?: Subscription;

  readonly abertas = signal<OrdemCargaResumoDto[]>([]);
  readonly carregandoAbertas = signal(true);
  readonly erroAbertas = signal<string | null>(null);
  filtroLista = '';
  /** 'todas' | 'pendentes' (ainda tem nota não conferida) | 'concluidas' (100%) — ajuda a localizar rápido numa lista grande. */
  filtroStatus: FiltroStatus = this.filtrosSalvosLidos().status;
  /** true = painel mostra só pedidos SEM Ordem de Carga (um mapa por Número Único). */
  semOrdemCarga = this.filtrosSalvosLidos().semOrdemCarga;

  readonly pedidosSemOc = signal<PedidoSemOrdemCargaDto[]>([]);
  /** Números Únicos marcados pra gerar os mapas S/ OC de uma vez. */
  readonly selecionados = signal<Set<number>>(new Set());

  /** Mapas abertos: 1 (Ordem de Carga) ou N independentes (S/ Ordem de Carga, um por Número Único). */
  readonly mapas = signal<MapaSeparacaoDto[]>([]);
  readonly dados = computed(() => this.mapas()[0] ?? null);
  readonly carregando = signal(false);
  readonly erro = signal<string | null>(null);

  /** Fallback pra OC que ainda não está aberta na lista (ex.: acabou de abrir no Sankhya), ou pra quando a lista falha ao carregar. */
  ordemCargaManual: number | null = null;

  readonly formatarQtd = formatarQtd;
  readonly formatarPeso = formatarPeso;

  get listaFiltrada(): OrdemCargaResumoDto[] {
    const termo = this.filtroLista.trim().toLowerCase();
    const status = this.filtroStatus;
    return this.abertas().filter((oc) => {
      const passaBusca =
        !termo ||
        String(oc.ordemCarga).includes(termo) ||
        oc.placa?.toLowerCase().includes(termo) ||
        oc.nomeMotorista?.toLowerCase().includes(termo);

      const concluida = this.ocConcluida(oc);
      const passaStatus = status === 'todas' || (status === 'concluidas' ? concluida : !concluida);

      return passaBusca && passaStatus;
    })
      // Não concluídas primeiro (é o que ainda precisa de atenção); dentro de
      // cada grupo mantém a ordem do backend (OC mais recente primeiro) — sort estável.
      .sort((a, b) => Number(this.ocConcluida(a)) - Number(this.ocConcluida(b)));
  }

  /** Pedidos S/ OC com os mesmos filtros do painel (busca + status), não conferidos primeiro. */
  get pedidosSemOcFiltrados(): PedidoSemOrdemCargaDto[] {
    const termo = this.filtroLista.trim().toLowerCase();
    const status = this.filtroStatus;
    return this.pedidosSemOc()
      .filter((p) => {
        const passaBusca =
          !termo ||
          String(p.nunota).includes(termo) ||
          (p.numNota != null && String(p.numNota).includes(termo)) ||
          (p.codParc != null && String(p.codParc).includes(termo)) ||
          !!p.nomeParceiro?.toLowerCase().includes(termo);
        const passaStatus = status === 'todas' || (status === 'concluidas' ? p.conferido : !p.conferido);
        return passaBusca && passaStatus;
      })
      .sort((a, b) => Number(a.conferido) - Number(b.conferido));
  }

  /** Selecionados que ainda aparecem na lista filtrada — é o que o botão gera. */
  get selecionadosVisiveis(): number[] {
    const sel = this.selecionados();
    return this.pedidosSemOcFiltrados.filter((p) => sel.has(p.nunota)).map((p) => p.nunota);
  }

  get todosVisiveisSelecionados(): boolean {
    const lista = this.pedidosSemOcFiltrados;
    return lista.length > 0 && lista.every((p) => this.selecionados().has(p.nunota));
  }

  alternarSelecao(nunota: number): void {
    const novo = new Set(this.selecionados());
    if (novo.has(nunota)) novo.delete(nunota);
    else novo.add(nunota);
    this.selecionados.set(novo);
  }

  alternarTodos(): void {
    const lista = this.pedidosSemOcFiltrados.map((p) => p.nunota);
    const novo = new Set(this.selecionados());
    if (this.todosVisiveisSelecionados) lista.forEach((n) => novo.delete(n));
    else lista.forEach((n) => novo.add(n));
    this.selecionados.set(novo);
  }

  iconeCategoria(codigo: string): OqIconName {
    return ICONE_CATEGORIA[codigo] ?? 'circle-alert';
  }

  /** 100% conferida — só faz sentido pra OC que já tem pelo menos 1 nota rastreada (ver totalNotas). */
  ocConcluida(oc: OrdemCargaResumoDto): boolean {
    return oc.totalNotas > 0 && oc.notasConferidas === oc.totalNotas;
  }

  progressoPct(oc: OrdemCargaResumoDto): number {
    if (oc.totalNotas <= 0) return 0;
    return Math.min(100, Math.round((oc.notasConferidas / oc.totalNotas) * 100));
  }

  ngOnInit(): void {
    this.carregarPainel();
    // Refresh automático no mesmo ciclo do sync da Fila de Tarefas (60s, contador
    // do cabeçalho) — é esse sync que atualiza o progresso de conferência. Só
    // recarrega no painel: com um relatório aberto, não mexe (seria ida à toa ao
    // Sankhya e a lista nem está visível).
    this.syncSub = this.syncTick.onTick.subscribe(() => {
      if (!this.dados() && !this.carregando()) this.carregarPainel(true);
    });
  }

  ngOnDestroy(): void {
    this.syncSub?.unsubscribe();
  }

  /** Lista do painel conforme o filtro "S/ Ordem de Carga". */
  carregarPainel(silencioso = false): void {
    if (this.semOrdemCarga) this.carregarSemOrdemCarga(silencioso);
    else this.carregarAbertas(silencioso);
  }

  /** `silencioso`: refresh automático — não troca a lista pelo spinner nem apaga a lista se falhar. */
  carregarAbertas(silencioso = false): void {
    if (!silencioso) this.carregandoAbertas.set(true);
    this.erroAbertas.set(null);
    this.service.listarAbertas().subscribe({
      next: (lista) => {
        // OC sem nenhuma nota na conferência (0/0) não tem o que separar no WMS —
        // o mapa dela sairia vazio. Aparece sozinha no próximo refresh quando a
        // Fila de Tarefas sincronizar alguma nota dela.
        this.abertas.set(lista.filter((oc) => oc.totalNotas > 0));
        this.carregandoAbertas.set(false);
      },
      error: (err) => {
        if (!silencioso) this.abertas.set([]);
        this.carregandoAbertas.set(false);
        this.erroAbertas.set(err?.error?.erro ?? 'Falha ao carregar as Ordens de Carga abertas.');
      },
    });
  }

  private carregarSemOrdemCarga(silencioso = false): void {
    if (!silencioso) this.carregandoAbertas.set(true);
    this.erroAbertas.set(null);
    this.service.listarSemOrdemCarga().subscribe({
      next: (lista) => {
        this.pedidosSemOc.set(lista);
        // Pedido que saiu da lista (ganhou OC, saiu da fila) não fica selecionado escondido.
        const existentes = new Set(lista.map((p) => p.nunota));
        this.selecionados.set(new Set([...this.selecionados()].filter((n) => existentes.has(n))));
        this.carregandoAbertas.set(false);
      },
      error: (err) => {
        if (!silencioso) this.pedidosSemOc.set([]);
        this.carregandoAbertas.set(false);
        this.erroAbertas.set(err?.error?.erro ?? 'Falha ao carregar os pedidos sem Ordem de Carga.');
      },
    });
  }

  consultarManual(): void {
    const oc = this.ordemCargaManual;
    if (!oc || oc <= 0) {
      this.erro.set('Informe uma Ordem de Carga numérica válida.');
      return;
    }
    this.consultar(oc);
  }

  consultar(ordemCarga: number): void {
    this.carregando.set(true);
    this.erro.set(null);
    this.mapas.set([]);

    this.service.consultar(ordemCarga).subscribe({
      next: (r) => {
        this.mapas.set([r]);
        this.carregando.set(false);
      },
      error: (err) => {
        this.erro.set(err?.error?.erro ?? 'Falha ao consultar a Ordem de Carga.');
        this.carregando.set(false);
      },
    });
  }

  /**
   * Um mapa por Número Único — cada pedido é buscado e montado SOZINHO no backend (nunca soma
   * entre pedidos). Pedido que falhar não derruba os outros: sai no aviso, os demais abrem.
   */
  consultarSemOrdemCarga(nunotas: number[]): void {
    if (nunotas.length === 0) return;
    this.carregando.set(true);
    this.erro.set(null);
    this.mapas.set([]);

    from(nunotas)
      .pipe(
        mergeMap(
          (nunota) =>
            new Promise<{ nunota: number; mapa?: MapaSeparacaoDto; erro?: string }>((resolve) =>
              this.service.consultarSemOrdemCarga(nunota).subscribe({
                next: (mapa) => resolve({ nunota, mapa }),
                error: (err) => resolve({ nunota, erro: err?.error?.erro ?? `Falha ao consultar o pedido ${nunota}.` }),
              }),
            ),
          CONCORRENCIA_MAPAS,
        ),
        toArray(),
      )
      .subscribe((resultados) => {
        // Mantém a ordem escolhida na tela (mergeMap devolve na ordem de chegada).
        const ordem = new Map(nunotas.map((n, i) => [n, i]));
        resultados.sort((a, b) => ordem.get(a.nunota)! - ordem.get(b.nunota)!);
        const falhas = resultados.filter((r) => r.erro).map((r) => r.erro!);
        this.mapas.set(resultados.filter((r) => r.mapa).map((r) => r.mapa!));
        this.erro.set(falhas.length ? falhas.join(' · ') : null);
        this.carregando.set(false);
      });
  }

  /** Volta pro painel — não recarrega a lista (evita ida desnecessária ao Sankhya); "Atualizar" faz isso à parte. */
  voltar(): void {
    this.mapas.set([]);
    this.erro.set(null);
    this.ordemCargaManual = null;
  }

  tituloRelatorio(): string {
    const mapas = this.mapas();
    const primeiro = mapas[0];
    if (!primeiro) return '';
    if (!primeiro.semOrdemCarga) return `Ordem de Carga ${primeiro.ordemCarga}`;
    return mapas.length === 1
      ? `S/ Ordem de Carga — Nro. Único ${primeiro.nunota}`
      : `S/ Ordem de Carga — ${mapas.length} mapas`;
  }

  imprimir(): void {
    const mapas = this.mapas();
    if (mapas.length === 0) return;

    const tituloOriginal = document.title;
    document.title = mapas[0].semOrdemCarga
      ? mapas.length === 1
        ? `S-OC NU ${mapas[0].nunota}`
        : `S-OC ${mapas.length} mapas`
      : `O.C. ${mapas[0].ordemCarga}`;
    const restaurar = () => {
      document.title = tituloOriginal;
      window.removeEventListener('afterprint', restaurar);
    };
    window.addEventListener('afterprint', restaurar, { once: true });
    setTimeout(() => window.print(), 50);
  }

  trackMapa(_index: number, mapa: MapaSeparacaoDto): string {
    return mapa.semOrdemCarga ? `nu-${mapa.nunota}` : `oc-${mapa.ordemCarga}`;
  }

  trackCategoria(_index: number, categoria: CategoriaSeparacaoDto): string {
    return categoria.codigo;
  }
}
