import { Component, OnDestroy, OnInit, computed, inject, signal } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { Observable, forkJoin } from 'rxjs';
import { OqIconComponent } from '../shared/icons/oq-icon.component';
import { OqSpinnerComponent } from '../shared/icons/oq-spinner.component';
import { OqUsuariosAutorizadosComponent } from '../shared/oq-usuarios-autorizados/oq-usuarios-autorizados.component';
import { UsuarioService } from '../usuarios/usuario.service';
import { Usuario } from '../usuarios/usuario.model';
import { TiposOperacaoService } from '../tipos-operacao/tipos-operacao.service';
import { TipoOperacao, rotuloTipmov } from '../tipos-operacao/tipos-operacao.model';
import { ConfigConferenciaService } from './config-conferencia.service';
import { Aba, CampoCatalogo, ConfigConferenciaDetalhe, ConfigConferenciaListItem, OpcoesConferencia } from './config-conferencia.model';
import { ABAS, CATALOGO_CONFIG_CONFERENCIA } from './config-conferencia.catalogo';
import { agruparPorSubSecao, campoHabilitado } from './config-conferencia.util';

/** Mensagem da barra de salvar: sucesso ou erro, numa linha. */
interface Mensagem {
  texto: string;
  erro: boolean;
}

/** Estado editável do detalhe de uma configuração (o que o Salvar grava). */
interface Regras {
  tolAcima: string;
  tolAbaixo: string;
  pedirQtdComercial: boolean;
  ajusteHabilitado: boolean;
  ajusteEntrada: boolean;
  ajusteSaida: boolean;
}

const TIPMOV_COMPRA = new Set(['C', 'O']);
const TIPMOV_VENDA = new Set(['V', 'P']);

/**
 * Conferência (25/11/2026) — Configurações de Conferência (TGFCCO) e Tipos de Operação numa tela só, organizada pela
 * configuração (a TOP pertence a ela). Lista à esquerda = configurações (+ TOPs sem configuração, recolhidas);
 * detalhe = regras da conferência, ajuste de peso (só com TOP de compra; por configuração desde a V78), as TOPs que
 * usam a configuração (etapa, quem confere, fatura em) e a configuração do Sankhya só para consulta. Um Salvar.
 * Campos da TGFCCO só leitura: a sincronização (`upsertDoSankhya`) regrava tudo.
 */
@Component({
  selector: 'app-config-conferencia',
  standalone: true,
  imports: [CommonModule, FormsModule, OqIconComponent, OqSpinnerComponent, OqUsuariosAutorizadosComponent],
  templateUrl: './config-conferencia.component.html',
  styleUrl: './config-conferencia.component.scss',
})
export class ConfigConferenciaComponent implements OnInit, OnDestroy {
  private readonly http = inject(HttpClient);
  private readonly service = inject(ConfigConferenciaService);
  private readonly topService = inject(TiposOperacaoService);
  private readonly usuarioService = inject(UsuarioService);

  readonly abas = ABAS;
  readonly rotuloTipmov = rotuloTipmov;

  // ─── Lista ───
  readonly lista = signal<ConfigConferenciaListItem[]>([]);
  readonly tops = signal<TipoOperacao[]>([]);
  readonly busca = signal('');
  /** número = NUCCO; 'sem' = TOPs sem configuração de conferência. */
  readonly selecao = signal<number | 'sem' | null>(null);
  readonly detalhe = signal<ConfigConferenciaDetalhe | null>(null);
  readonly carregando = signal(true);
  readonly sincronizando = signal(false);
  readonly erro = signal<string | null>(null);
  readonly abertoNoCelular = signal(false);
  readonly verSankhya = signal(false);

  readonly topsPorNucco = computed(() => {
    const m = new Map<number | null, TipoOperacao[]>();
    for (const t of this.tops()) {
      const k = t.nucco ?? null;
      if (!m.has(k)) m.set(k, []);
      m.get(k)!.push(t);
    }
    for (const l of m.values()) l.sort((a, b) => a.codtop - b.codtop);
    return m;
  });

  readonly topsSemConfiguracao = computed(() => this.topsPorNucco().get(null) ?? []);

  readonly listaFiltrada = computed(() => {
    const termo = this.busca().trim().toLowerCase();
    if (!termo) return this.lista();
    return this.lista().filter(
      (c) =>
        c.descricao.toLowerCase().includes(termo) ||
        String(c.nucco) === termo ||
        (this.topsPorNucco().get(c.nucco) ?? []).some((t) => String(t.codtop).startsWith(termo) || t.descricao.toLowerCase().includes(termo)),
    );
  });

  topsDe(nucco: number): TipoOperacao[] {
    return this.topsPorNucco().get(nucco) ?? [];
  }

  /** "Compra", "Venda", "Compra e venda" ou "" — pelo TIPMOV das TOPs que usam a configuração. */
  tipoDe(nucco: number): string {
    const tipos = this.topsDe(nucco).map((t) => t.tipmov ?? '');
    const compra = tipos.some((t) => TIPMOV_COMPRA.has(t));
    const venda = tipos.some((t) => TIPMOV_VENDA.has(t));
    return compra && venda ? 'Compra e venda' : compra ? 'Compra' : venda ? 'Venda' : '';
  }

  temCompra(nucco: number): boolean {
    return this.topsDe(nucco).some((t) => TIPMOV_COMPRA.has(t.tipmov ?? ''));
  }

  resumoItem(nucco: number): string {
    const n = this.topsDe(nucco).length;
    return [this.tipoDe(nucco), n === 0 ? 'nenhuma TOP' : n === 1 ? '1 TOP' : `${n} TOPs`].filter(Boolean).join(' · ');
  }

  ngOnInit(): void {
    this.carregarTudo();
  }

  ngOnDestroy(): void {
    if (this.ressincTimer) clearInterval(this.ressincTimer);
  }

  private carregarTudo(): void {
    this.carregando.set(true);
    forkJoin({ ccos: this.service.listar(), tops: this.topService.listar() }).subscribe({
      next: ({ ccos, tops }) => {
        this.lista.set(ccos);
        this.tops.set(tops);
        this.carregando.set(false);
        if (ccos.length > 0 && this.selecao() == null) this.selecionar(ccos[0].nucco);
      },
      error: (err) => {
        this.carregando.set(false);
        this.erro.set(err.error?.erro ?? 'Não foi possível carregar as configurações.');
      },
    });
  }

  abrir(sel: number | 'sem'): void {
    this.selecionar(sel);
    this.abertoNoCelular.set(true);
  }

  voltar(): void {
    this.abertoNoCelular.set(false);
  }

  private selecionar(sel: number | 'sem'): void {
    this.selecao.set(sel);
    this.detalhe.set(null);
    this.mensagem.set(null);
    this.etapaPendente.set(new Map());
    if (sel === 'sem') return;
    this.service.detalhe(sel).subscribe({
      next: (d) => {
        if (this.selecao() === sel) this.detalhe.set(d);
      },
      error: (err) => this.erro.set(err.error?.erro ?? 'Não foi possível carregar a configuração.'),
    });
    this.carregarRegras(sel);
  }

  sincronizarAgora(): void {
    if (this.sincronizando()) return;
    this.sincronizando.set(true);
    this.erro.set(null);
    forkJoin([this.service.sincronizar(), this.topService.sincronizar()]).subscribe({
      next: () => {
        this.sincronizando.set(false);
        const atual = this.selecao();
        this.carregarTudo();
        if (atual != null) this.selecionar(atual);
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
    // Corte e divergência sempre inteira (usuário, 10/11/2026): os campos valem no corte que a Torre dispara.
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

  // ─── Regras da conferência + ajuste de peso (por NUCCO) + etapa das TOPs — um Salvar ───
  readonly regras = signal<Regras | null>(null);
  private readonly regrasOriginais = signal<Regras | null>(null);
  readonly tolConfigurada = signal(false);
  /** codtop → novo valor de "conferência por etapa" ainda não salvo. */
  readonly etapaPendente = signal<Map<number, boolean>>(new Map());
  readonly salvando = signal(false);
  readonly mensagem = signal<Mensagem | null>(null);

  private carregarRegras(nucco: number): void {
    this.regras.set(null);
    forkJoin({ tol: this.service.toleranciaPeso(nucco), opcoes: this.service.opcoes(nucco) }).subscribe({
      next: ({ tol, opcoes }) => {
        if (this.selecao() !== nucco) return;
        const r: Regras = {
          tolAcima: tol.acimaPct == null ? '' : String(tol.acimaPct),
          tolAbaixo: tol.abaixoPct == null ? '' : String(tol.abaixoPct),
          pedirQtdComercial: opcoes.pedirQtdComercial,
          ajusteHabilitado: !!opcoes.ajustePesoHabilitado,
          ajusteEntrada: !!opcoes.ajustePesoEntrada,
          ajusteSaida: !!opcoes.ajustePesoSaida,
        };
        this.regrasOriginais.set(r);
        this.regras.set({ ...r });
        this.tolConfigurada.set(tol.configurada);
      },
      error: () => this.mensagem.set({ texto: 'Não foi possível carregar as regras desta configuração.', erro: true }),
    });
  }

  alterar<K extends keyof Regras>(campo: K, valor: Regras[K]): void {
    const r = this.regras();
    if (r) this.regras.set({ ...r, [campo]: valor });
  }

  etapaDe(top: TipoOperacao): boolean {
    return this.etapaPendente().get(top.codtop) ?? top.conferenciaPorEtapa;
  }

  alterarEtapa(top: TipoOperacao, valor: boolean): void {
    const m = new Map(this.etapaPendente());
    if (valor === top.conferenciaPorEtapa) m.delete(top.codtop);
    else m.set(top.codtop, valor);
    this.etapaPendente.set(m);
  }

  private readonly tolAlterada = computed(() => {
    const r = this.regras();
    const o = this.regrasOriginais();
    return !!r && !!o && (r.tolAcima.trim() !== o.tolAcima || r.tolAbaixo.trim() !== o.tolAbaixo);
  });

  private readonly opcoesAlteradas = computed(() => {
    const r = this.regras();
    const o = this.regrasOriginais();
    return (
      !!r &&
      !!o &&
      (r.pedirQtdComercial !== o.pedirQtdComercial ||
        r.ajusteHabilitado !== o.ajusteHabilitado ||
        r.ajusteEntrada !== o.ajusteEntrada ||
        r.ajusteSaida !== o.ajusteSaida)
    );
  });

  readonly alterado = computed(() => this.tolAlterada() || this.opcoesAlteradas() || this.etapaPendente().size > 0);

  estado(): Mensagem | null {
    const m = this.mensagem();
    if (m?.erro) return m;
    if (this.alterado()) return { texto: 'Alterações não salvas', erro: false };
    return m;
  }

  descartar(): void {
    const o = this.regrasOriginais();
    if (o) this.regras.set({ ...o });
    this.etapaPendente.set(new Map());
    this.mensagem.set(null);
  }

  private lerPct(texto: string): number | null | undefined {
    const t = texto.trim().replace(',', '.');
    if (t === '') return null;
    const n = Number(t);
    return Number.isFinite(n) && n >= 0 ? n : undefined;
  }

  salvar(): void {
    const nucco = this.selecao();
    const r = this.regras();
    if (this.salvando() || !this.alterado()) return;
    const chamadas: Record<string, Observable<unknown>> = {};
    if (typeof nucco === 'number' && r) {
      const acima = this.lerPct(r.tolAcima);
      const abaixo = this.lerPct(r.tolAbaixo);
      if (acima === undefined || abaixo === undefined) {
        this.mensagem.set({ texto: 'Tolerância: informe um número (%) maior ou igual a 0, ou deixe vazio para sem limite.', erro: true });
        return;
      }
      if (this.tolAlterada()) chamadas['tol'] = this.service.salvarToleranciaPeso(nucco, acima, abaixo);
      if (this.opcoesAlteradas()) {
        const opcoes: Partial<OpcoesConferencia> = {
          pedirQtdComercial: r.pedirQtdComercial,
          ajustePesoHabilitado: r.ajusteHabilitado,
          ajustePesoEntrada: r.ajusteEntrada,
          ajustePesoSaida: r.ajusteSaida,
        };
        chamadas['opcoes'] = this.service.salvarOpcoes(nucco, opcoes);
      }
    }
    for (const [codtop, valor] of this.etapaPendente()) {
      chamadas[`top${codtop}`] = this.topService.definirConferenciaPorEtapa(codtop, valor);
    }

    this.salvando.set(true);
    this.mensagem.set(null);
    forkJoin(chamadas).subscribe({
      next: (res) => {
        this.salvando.set(false);
        const tol = res['tol'] as { configurada: boolean } | undefined;
        if (tol) this.tolConfigurada.set(tol.configurada);
        if (r) {
          const salvo = { ...r, tolAcima: r.tolAcima.trim(), tolAbaixo: r.tolAbaixo.trim() };
          this.regrasOriginais.set(salvo);
          this.regras.set({ ...salvo });
        }
        const pend = this.etapaPendente();
        this.tops.update((l) => l.map((t) => (pend.has(t.codtop) ? { ...t, conferenciaPorEtapa: pend.get(t.codtop)! } : t)));
        this.etapaPendente.set(new Map());
        this.mensagem.set({ texto: 'Salvo. Vale para as próximas conferências abertas.', erro: false });
      },
      error: (err) => {
        this.salvando.set(false);
        this.mensagem.set({ texto: err.error?.erro ?? 'Não foi possível salvar.', erro: true });
      },
    });
  }

  // ─── Quem confere (usuários autorizados por TOP; nenhum = todos) — salva pelo pop-up ───
  readonly usuarios = signal<Usuario[]>([]);
  readonly usuariosTop = signal<{ top: TipoOperacao; selecionados: string[] } | null>(null);
  readonly salvandoUsuarios = signal(false);
  readonly erroUsuarios = signal<string | null>(null);

  abrirUsuarios(top: TipoOperacao): void {
    this.erroUsuarios.set(null);
    this.topService.listarUsuarios(top.codtop).subscribe({
      next: ({ usuarioIds }) => {
        const abrir = () => this.usuariosTop.set({ top, selecionados: usuarioIds });
        if (this.usuarios().length) return abrir();
        this.usuarioService.listar().subscribe({
          next: (lista) => {
            this.usuarios.set(lista);
            abrir();
          },
          error: (err) => this.mensagem.set({ texto: err.error?.erro ?? 'Não foi possível carregar os usuários.', erro: true }),
        });
      },
      error: (err) => this.mensagem.set({ texto: err.error?.erro ?? `Não foi possível carregar os usuários da TOP ${top.codtop}.`, erro: true }),
    });
  }

  salvarUsuarios(ids: string[]): void {
    const atual = this.usuariosTop();
    if (!atual || this.salvandoUsuarios()) return;
    this.salvandoUsuarios.set(true);
    this.erroUsuarios.set(null);
    this.topService.definirUsuarios(atual.top.codtop, ids).subscribe({
      next: ({ usuarioIds }) => {
        this.tops.update((l) => l.map((t) => (t.codtop === atual.top.codtop ? { ...t, usuariosAutorizados: usuarioIds.length } : t)));
        this.salvandoUsuarios.set(false);
        this.usuariosTop.set(null);
      },
      error: (err) => {
        this.salvandoUsuarios.set(false);
        this.erroUsuarios.set(err.error?.erro ?? 'Não foi possível salvar os usuários autorizados.');
      },
    });
  }
}
