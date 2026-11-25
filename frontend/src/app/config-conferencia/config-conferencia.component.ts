import { Component, OnDestroy, OnInit, computed, inject, signal } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { Observable, forkJoin } from 'rxjs';
import { OqIconComponent } from '../shared/icons/oq-icon.component';
import { OqSpinnerComponent } from '../shared/icons/oq-spinner.component';
import { ConfigConferenciaService } from './config-conferencia.service';
import { Aba, AjustePesoCompra, CampoCatalogo, ConfigConferenciaDetalhe, ConfigConferenciaListItem } from './config-conferencia.model';
import { ABAS, CATALOGO_CONFIG_CONFERENCIA } from './config-conferencia.catalogo';
import { agruparPorSubSecao, campoHabilitado } from './config-conferencia.util';

/** Mensagem da barra de salvar: sucesso ou erro, numa linha. */
interface Mensagem {
  texto: string;
  erro: boolean;
}

const AJUSTE_VAZIO: AjustePesoCompra = {
  habilitado: false,
  ajusteEntrada: false,
  ajusteSaida: false,
  topEntrada: null,
  topSaida: null,
  serieEntrada: null,
  serieSaida: null,
  codTipVenda: null,
  codParc: null,
  viaAddon: false,
};

/**
 * Configuração de Conferência (redesenho de 25/11/2026, no padrão da mesa da Liberações):
 * - lista à esquerda: "Diferença de peso na compra" (configuração do cliente; só pelo addon TOR desde 25/11/2026)
 *   + as CCOs sincronizadas do Sankhya;
 * - detalhe à direita, com UM Salvar por item (barra presa no rodapé);
 * - campos da TGFCCO só leitura: a sincronização (`upsertDoSankhya`) apaga e regrava tudo, então a edição local
 *   que existia antes se perdia sem aviso.
 */
@Component({
  selector: 'app-config-conferencia',
  standalone: true,
  imports: [CommonModule, FormsModule, OqIconComponent, OqSpinnerComponent],
  templateUrl: './config-conferencia.component.html',
  styleUrl: './config-conferencia.component.scss',
})
export class ConfigConferenciaComponent implements OnInit, OnDestroy {
  private readonly http = inject(HttpClient);
  private readonly service = inject(ConfigConferenciaService);

  readonly abas = ABAS;

  // ─── Lista e seleção ───
  readonly lista = signal<ConfigConferenciaListItem[]>([]);
  /** 'ajuste' = Diferença de peso na compra; número = NUCCO. */
  readonly selecao = signal<'ajuste' | number | null>(null);
  readonly detalhe = signal<ConfigConferenciaDetalhe | null>(null);
  readonly carregando = signal(true);
  readonly sincronizando = signal(false);
  readonly erro = signal<string | null>(null);
  /** Celular/tablet em pé: lista OU detalhe. */
  readonly abertoNoCelular = signal(false);

  ngOnInit(): void {
    this.carregarLista();
    this.carregarAjuste();
  }

  ngOnDestroy(): void {
    if (this.ressincTimer) clearInterval(this.ressincTimer);
  }

  private carregarLista(): void {
    this.carregando.set(true);
    this.service.listar().subscribe({
      next: (itens) => {
        this.lista.set(itens);
        this.carregando.set(false);
        if (itens.length > 0 && this.selecao() == null) this.selecionarConfiguracao(itens[0].nucco);
      },
      error: (err) => {
        this.carregando.set(false);
        this.erro.set(err.error?.erro ?? 'Não foi possível carregar as configurações.');
      },
    });
  }

  abrirConfiguracao(nucco: number): void {
    this.selecionarConfiguracao(nucco);
    this.abertoNoCelular.set(true);
  }

  abrirAjuste(): void {
    this.selecao.set('ajuste');
    this.abertoNoCelular.set(true);
  }

  voltar(): void {
    this.abertoNoCelular.set(false);
  }

  private selecionarConfiguracao(nucco: number): void {
    this.selecao.set(nucco);
    this.detalhe.set(null);
    this.regrasMensagem.set(null);
    this.service.detalhe(nucco).subscribe({
      next: (d) => {
        if (this.selecao() === nucco) this.detalhe.set(d);
      },
      error: (err) => this.erro.set(err.error?.erro ?? 'Não foi possível carregar a configuração.'),
    });
    this.carregarRegras(nucco);
  }

  sincronizarAgora(): void {
    if (this.sincronizando()) return;
    this.sincronizando.set(true);
    this.erro.set(null);
    this.service.sincronizar().subscribe({
      next: () => {
        this.sincronizando.set(false);
        this.carregarLista();
        const atual = this.selecao();
        if (typeof atual === 'number') this.selecionarConfiguracao(atual);
      },
      error: (err) => {
        this.sincronizando.set(false);
        this.erro.set(err.error?.erro ?? 'Não foi possível sincronizar com o Sankhya.');
      },
    });
  }

  // ─── Ressincronizar produtos (cache local ← Sankhya) ───
  readonly ressinc = signal<{ rodando: boolean; concluidoEm?: string | null; erro?: string | null; produtos?: number | null } | null>(null);
  private ressincTimer?: ReturnType<typeof setInterval>;

  ressincronizarProdutos(): void {
    if (this.ressinc()?.rodando) return;
    this.http.post<{ rodando: boolean }>('/api/produtos/ressincronizar', {}).subscribe({
      next: (s) => {
        this.ressinc.set(s);
        this.acompanharRessinc();
      },
      error: (err) => this.ressinc.set({ rodando: false, erro: err.error?.erro ?? 'Não foi possível iniciar a ressincronização.' }),
    });
  }

  /** Consulta o andamento a cada 3 s até terminar. */
  private acompanharRessinc(): void {
    if (this.ressincTimer) clearInterval(this.ressincTimer);
    this.ressincTimer = setInterval(() => {
      this.http.get<{ rodando: boolean; concluidoEm?: string; erro?: string; produtos?: number }>('/api/produtos/ressincronizar').subscribe((s) => {
        this.ressinc.set(s);
        if (!s.rodando && this.ressincTimer) {
          clearInterval(this.ressincTimer);
          this.ressincTimer = undefined;
        }
      });
    }, 3000);
  }

  // ─── Configuração do Sankhya (só leitura) ───
  readonly abaAtual = signal<Aba>('geral');
  readonly mostrarTodos = signal(false);

  readonly secoesAba = computed(() => {
    const aba = this.abaAtual();
    const daAba = CATALOGO_CONFIG_CONFERENCIA.filter((c) => c.aba === aba);
    // Corte e divergência sempre inteira (usuário, 10/11/2026): os campos valem no corte que a Torre dispara
    // (ConferenciaSP.cortar), mesmo os que a tela da Torre não usa diretamente.
    const visiveis = aba === 'corte' || this.mostrarTodos() ? daAba : daAba.filter((c) => c.status === 'implementado');
    return agruparPorSubSecao(visiveis);
  });

  habilitado(campo: CampoCatalogo): boolean {
    return campoHabilitado(campo, this.detalhe()?.campos ?? {});
  }

  textoValor(campo: CampoCatalogo): string {
    const v = this.detalhe()?.campos[campo.nome];
    if (v === null || v === undefined || `${v}`.trim() === '') return '—';
    if (campo.controle === 'toggle') return v === 'S' ? 'Sim' : v === 'N' ? 'Não' : v;
    if (campo.controle === 'select') return campo.opcoes?.find((o) => o.valor === v)?.label ?? v;
    return v;
  }

  /** Uso na Torre em texto discreto; implementado não leva nota. Detalhe técnico na dica. */
  notaUso(campo: CampoCatalogo): string | null {
    switch (campo.status) {
      case 'parcial':
        return 'uso parcial na Torre';
      case 'nao_implementado':
        return 'a Torre não usa';
      case 'nao_aplicavel':
        return 'não se aplica à Torre';
      default:
        return null;
    }
  }

  // ─── Regras da Torre por configuração (tolerância V50 + opções V70) — um Salvar ───
  readonly tolAcima = signal('');
  readonly tolAbaixo = signal('');
  readonly pedirQtdComercial = signal(true);
  readonly tolConfigurada = signal(false);
  private readonly regrasOriginais = signal({ tolAcima: '', tolAbaixo: '', pedirQtdComercial: true });
  readonly regrasSalvando = signal(false);
  readonly regrasMensagem = signal<Mensagem | null>(null);

  private readonly tolAlterada = computed(
    () => this.tolAcima().trim() !== this.regrasOriginais().tolAcima || this.tolAbaixo().trim() !== this.regrasOriginais().tolAbaixo,
  );
  private readonly opcoesAlteradas = computed(() => this.pedirQtdComercial() !== this.regrasOriginais().pedirQtdComercial);
  readonly regrasAlteradas = computed(() => this.tolAlterada() || this.opcoesAlteradas());

  private carregarRegras(nucco: number): void {
    forkJoin({ tol: this.service.toleranciaPeso(nucco), opcoes: this.service.opcoes(nucco) }).subscribe({
      next: ({ tol, opcoes }) => {
        if (this.selecao() !== nucco) return;
        const original = {
          tolAcima: tol.acimaPct == null ? '' : String(tol.acimaPct),
          tolAbaixo: tol.abaixoPct == null ? '' : String(tol.abaixoPct),
          pedirQtdComercial: opcoes.pedirQtdComercial,
        };
        this.regrasOriginais.set(original);
        this.tolConfigurada.set(tol.configurada);
        this.descartarRegras();
      },
      error: () => this.regrasMensagem.set({ texto: 'Não foi possível carregar as regras da Torre desta configuração.', erro: true }),
    });
  }

  descartarRegras(): void {
    const o = this.regrasOriginais();
    this.tolAcima.set(o.tolAcima);
    this.tolAbaixo.set(o.tolAbaixo);
    this.pedirQtdComercial.set(o.pedirQtdComercial);
  }

  /** '' = sem limite; número >= 0; qualquer outra coisa = inválido (undefined). */
  private lerPct(texto: string): number | null | undefined {
    const t = texto.trim().replace(',', '.');
    if (t === '') return null;
    const n = Number(t);
    return Number.isFinite(n) && n >= 0 ? n : undefined;
  }

  salvarRegras(): void {
    const nucco = this.selecao();
    if (typeof nucco !== 'number' || this.regrasSalvando() || !this.regrasAlteradas()) return;
    const acima = this.lerPct(this.tolAcima());
    const abaixo = this.lerPct(this.tolAbaixo());
    if (acima === undefined || abaixo === undefined) {
      this.regrasMensagem.set({ texto: 'Tolerância: informe um número (%) maior ou igual a 0, ou deixe vazio para sem limite.', erro: true });
      return;
    }
    const chamadas: Record<string, Observable<unknown>> = {};
    if (this.tolAlterada()) chamadas['tol'] = this.service.salvarToleranciaPeso(nucco, acima, abaixo);
    if (this.opcoesAlteradas()) chamadas['opcoes'] = this.service.salvarOpcoes(nucco, { pedirQtdComercial: this.pedirQtdComercial() });

    this.regrasSalvando.set(true);
    this.regrasMensagem.set(null);
    forkJoin(chamadas).subscribe({
      next: (r) => {
        this.regrasSalvando.set(false);
        const tol = r['tol'] as { configurada: boolean } | undefined;
        if (tol) this.tolConfigurada.set(tol.configurada);
        this.regrasOriginais.set({ tolAcima: this.tolAcima().trim(), tolAbaixo: this.tolAbaixo().trim(), pedirQtdComercial: this.pedirQtdComercial() });
        this.regrasMensagem.set({ texto: 'Salvo — vale para as próximas conferências abertas.', erro: false });
      },
      error: (err) => {
        this.regrasSalvando.set(false);
        this.regrasMensagem.set({ texto: err.error?.erro ?? 'Não foi possível salvar as regras da Torre.', erro: true });
      },
    });
  }

  /** Erro tem prioridade sobre "não salvo"; sucesso só aparece sem alteração pendente. */
  estadoRegras(): Mensagem | null {
    const m = this.regrasMensagem();
    if (m?.erro) return m;
    if (this.regrasAlteradas()) return { texto: 'Alterações não salvas', erro: false };
    return m;
  }

  // ─── Diferença de peso na compra (configuração do cliente, não da CCO) — um Salvar ───
  /** Formulário vivo (ngModel). */
  ajuste: AjustePesoCompra = { ...AJUSTE_VAZIO };
  /** O que está gravado (resumo da lista e base do "não salvo"). */
  readonly ajusteSalvo = signal<AjustePesoCompra>({ ...AJUSTE_VAZIO });
  readonly ajusteSalvando = signal(false);
  readonly ajusteMensagem = signal<Mensagem | null>(null);

  readonly resumoAjuste = computed(() => {
    const a = this.ajusteSalvo();
    return a.habilitado ? 'Ligado' : 'Desligado';
  });

  private carregarAjuste(): void {
    this.service.ajustePesoCompra().subscribe({
      next: (a) => {
        this.ajusteSalvo.set({ ...a });
        this.ajuste = { ...a, viaAddon: true };
      },
      error: () => this.ajusteMensagem.set({ texto: 'Não foi possível carregar a configuração da diferença de peso na compra.', erro: true }),
    });
  }

  private normalizarAjuste(a: AjustePesoCompra): AjustePesoCompra {
    const num = (v: unknown) => (v === null || v === undefined || `${v}`.trim() === '' ? null : Number(v));
    const serie = (v: unknown) => (v === null || v === undefined ? '' : `${v}`).trim() || null;
    return {
      habilitado: !!a.habilitado,
      ajusteEntrada: !!a.ajusteEntrada,
      ajusteSaida: !!a.ajusteSaida,
      topEntrada: num(a.topEntrada),
      topSaida: num(a.topSaida),
      serieEntrada: serie(a.serieEntrada),
      serieSaida: serie(a.serieSaida),
      codTipVenda: num(a.codTipVenda),
      codParc: num(a.codParc),
      viaAddon: !!a.viaAddon,
    };
  }

  ajusteAlterado(): boolean {
    return JSON.stringify(this.normalizarAjuste(this.ajuste)) !== JSON.stringify(this.normalizarAjuste(this.ajusteSalvo()));
  }

  estadoAjuste(): Mensagem | null {
    const m = this.ajusteMensagem();
    if (m?.erro) return m;
    if (this.ajusteAlterado()) return { texto: 'Alterações não salvas', erro: false };
    return m;
  }

  descartarAjuste(): void {
    this.ajuste = { ...this.ajusteSalvo(), viaAddon: true };
    this.ajusteMensagem.set(null);
  }

  salvarAjuste(): void {
    if (this.ajusteSalvando()) return;
    const a = this.normalizarAjuste(this.ajuste);
    const falha = (texto: string) => this.ajusteMensagem.set({ texto, erro: true });
    // Só pelo addon (usuário, 25/11/2026): TOP, série, parceiro e tipo de negociação vêm da TORCFG no Sankhya; os
    // valores antigos gravados aqui seguem como estão (o backend não exige TOP com viaAddon).
    a.viaAddon = true;

    this.ajusteSalvando.set(true);
    this.ajusteMensagem.set(null);
    this.service.salvarAjustePesoCompra(a).subscribe({
      next: (salvo) => {
        this.ajusteSalvando.set(false);
        this.ajusteSalvo.set({ ...salvo });
        this.ajuste = { ...salvo, viaAddon: true };
        this.ajusteMensagem.set({
          texto: salvo.habilitado
            ? 'Salvo. Vale para as próximas conferências de compra.'
            : 'Salvo. Ajuste de peso desligado.',
          erro: false,
        });
      },
      error: (err) => {
        this.ajusteSalvando.set(false);
        falha(err.error?.erro ?? 'Não foi possível salvar.');
      },
    });
  }
}
