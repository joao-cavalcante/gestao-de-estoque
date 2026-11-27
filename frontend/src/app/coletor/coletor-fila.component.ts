import { Component, ElementRef, HostListener, OnDestroy, OnInit, ViewChild, computed, inject, signal } from '@angular/core';
import { NgTemplateOutlet } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { Router } from '@angular/router';
import { Subscription } from 'rxjs';
import { AuthService } from '../auth/auth.service';
import { ConferenciasService } from '../fila-tarefas/conferencias.service';
import { Tarefa, jaENota } from '../fila-tarefas/tarefa.model';
import { ActionFeedbackService } from '../shared/action-feedback/action-feedback.service';
import { OqIconComponent } from '../shared/icons/oq-icon.component';
import { OqSpinnerComponent } from '../shared/icons/oq-spinner.component';
import { ThemeService } from '../shared/theme/theme.service';
import {
  ORDENS_COLETOR,
  OrdemColetor,
  ResultadoBusca,
  filaDoColetor,
  normalizarCodigo,
  ordenarFila,
  paginar,
  resolverBusca,
  resolverEscolha,
  rotuloStatusColetor,
} from './coletor-fila.logic';
import { definirPreferenciaTorre } from './coletor-modo';

/** Estado da leitura — sempre com texto (nunca só cor). */
type EstadoLeitura =
  | { tipo: 'aguardando' }
  | { tipo: 'processando'; codigo: string }
  | { tipo: 'encontrado'; titulo: string; mensagem: string }
  | { tipo: 'nao-encontrado'; titulo: string; mensagem: string }
  | { tipo: 'bloqueado'; titulo: string; mensagem: string }
  | { tipo: 'concluido'; titulo: string; mensagem: string }
  | { tipo: 'erro'; titulo: string; mensagem: string };

const TAMANHO_PAGINA = 15;
const CHAVE_ORDEM = 'wms_coletor_ordem';
const CHAVE_TECLADO = 'wms_coletor_teclado';
/** Mesma leitura repetida dentro desta janela (gatilho duplo, Enter duplicado) é ignorada. */
const JANELA_DUPLICADA_MS = 1500;
/** Atualização automática da fila enquanto a tela está visível. */
const INTERVALO_ATUALIZACAO_MS = 60_000;

/**
 * Fila do COLETOR (18/11/2026) — experiência própria de coletor, separada da Fila de Tarefas da Torre.
 * Referência: fila nativa do coletor Sankhya (busca única + lista de pendentes paginada, mais antigos primeiro).
 * Dados: mesma API da Torre (GET /api/tarefas — tenant, TOPs do usuário e critério da fila nativa). Abrir um documento
 * leva para a MESMA tela de conferência (/conferencia/:nunota), que já tem o modo coletor e todas as validações.
 */
@Component({
  selector: 'app-coletor-fila',
  standalone: true,
  imports: [FormsModule, NgTemplateOutlet, OqIconComponent, OqSpinnerComponent],
  templateUrl: './coletor-fila.component.html',
  styleUrl: './coletor-fila.component.scss',
})
export class ColetorFilaComponent implements OnInit, OnDestroy {
  private readonly conferencias = inject(ConferenciasService);
  private readonly auth = inject(AuthService);
  private readonly router = inject(Router);
  private readonly feedback = inject(ActionFeedbackService);
  readonly tema = inject(ThemeService);

  @ViewChild('campo') campo?: ElementRef<HTMLInputElement>;

  readonly ordens = ORDENS_COLETOR;
  readonly operador = computed(() => this.auth.usuario()?.nome ?? '');

  /** TODA a fila da API (para explicar bloqueio/concluído na busca); a lista mostra só o que é para conferir. */
  readonly tarefas = signal<Tarefa[] | null>(null);
  readonly carregando = signal(false);
  readonly erroCarga = signal<string | null>(null);
  readonly atualizadoEm = signal<Date | null>(null);
  readonly ordem = signal<OrdemColetor>(lerOrdem());
  readonly pagina = signal(1);
  readonly estado = signal<EstadoLeitura>({ tipo: 'aguardando' });
  readonly escolha = signal<{ motivo: string; candidatos: Tarefa[] } | null>(null);
  readonly menuAberto = signal(false);

  codigo = '';
  tecladoAberto = lerTeclado();

  readonly fila = computed(() => ordenarFila(filaDoColetor(this.tarefas() ?? []), this.ordem()));
  readonly paginaAtual = computed(() => paginar(this.fila(), this.pagina(), TAMANHO_PAGINA));
  readonly variasEmpresas = computed(() => new Set((this.tarefas() ?? []).map((t) => t.codigoEmpresa ?? '')).size > 1);

  private sub?: Subscription;
  private timer?: ReturnType<typeof setInterval>;
  private abrindo = false;
  private ultimaLeitura = { codigo: '', em: 0 };

  ngOnInit(): void {
    this.carregar();
    this.timer = setInterval(() => {
      if (document.visibilityState === 'visible' && !this.abrindo && this.estado().tipo !== 'processando') this.carregar();
    }, INTERVALO_ATUALIZACAO_MS);
    this.focar();
  }

  ngOnDestroy(): void {
    this.sub?.unsubscribe();
    clearInterval(this.timer);
  }

  // ─── Carga da fila ──────────────────────────────────────────────────────────────────────────────────────

  carregar(depois?: () => void): void {
    const tenant = this.auth.obterTenantSlug();
    if (!tenant) return;
    this.carregando.set(true);
    this.sub?.unsubscribe();
    this.sub = this.conferencias.listarFila(tenant).subscribe({
      next: (lista) => {
        this.tarefas.set(lista);
        this.erroCarga.set(null);
        this.atualizadoEm.set(new Date());
        this.carregando.set(false);
        depois?.();
      },
      error: () => {
        this.carregando.set(false);
        this.erroCarga.set('A fila não foi atualizada.');
        depois?.();
      },
    });
  }

  atualizar(): void {
    this.menuAberto.set(false);
    this.carregar();
    this.focar();
  }

  // ─── Leitura / busca ────────────────────────────────────────────────────────────────────────────────────

  onLer(): void {
    const codigo = normalizarCodigo(this.codigo);
    this.codigo = '';
    if (!codigo || this.abrindo || this.estado().tipo === 'processando') return;

    // Gatilho duplo / Enter duplicado: a mesma leitura em sequência abre uma vez só.
    const agora = Date.now();
    if (codigo === this.ultimaLeitura.codigo && agora - this.ultimaLeitura.em < JANELA_DUPLICADA_MS) return;
    this.ultimaLeitura = { codigo, em: agora };

    this.feedback.trigger('ITEM_LIDO');
    this.escolha.set(null);

    if (this.tarefas() == null) {
      this.mostrarErroComunicacao();
      return;
    }
    const resultado = resolverBusca(codigo, this.tarefas()!);
    if (resultado.tipo === 'nao-encontrado') {
      // A fila local pode estar até 1 min atrasada: relê do servidor UMA vez antes de dizer "não encontrado".
      this.estado.set({ tipo: 'processando', codigo });
      this.carregar(() => {
        if (this.erroCarga()) {
          this.mostrarErroComunicacao();
          return;
        }
        this.aplicar(resolverBusca(codigo, this.tarefas() ?? []));
      });
      return;
    }
    this.aplicar(resultado);
  }

  escolher(t: Tarefa): void {
    this.escolha.set(null);
    this.aplicar(resolverEscolha(t));
  }

  cancelarEscolha(): void {
    this.escolha.set(null);
    this.estado.set({ tipo: 'aguardando' });
    this.focar();
  }

  /** Toque numa linha da fila = mesmo caminho da leitura. */
  tocar(t: Tarefa): void {
    if (this.abrindo) return;
    this.aplicar(resolverEscolha(t));
  }

  private aplicar(r: ResultadoBusca): void {
    switch (r.tipo) {
      case 'vazio':
        this.estado.set({ tipo: 'aguardando' });
        break;
      case 'abrir':
        this.abrir(r.tarefa);
        return;
      case 'escolher':
        this.feedback.trigger('CONTROLE_NECESSARIO');
        this.escolha.set({ motivo: r.motivo, candidatos: r.candidatos });
        this.estado.set({ tipo: 'aguardando' });
        return;
      case 'bloqueado':
        this.feedback.trigger('OPERACAO_NAO_PERMITIDA');
        this.estado.set({ tipo: r.estado, titulo: `${r.tarefa.numeroUnico} · ${r.titulo}`, mensagem: r.mensagem });
        break;
      case 'nao-encontrado':
        this.feedback.trigger('PRODUTO_NAO_ENCONTRADO');
        this.estado.set({ tipo: 'nao-encontrado', titulo: `${r.codigo} não encontrado`, mensagem: r.mensagem });
        break;
    }
    this.focar();
  }

  private abrir(t: Tarefa): void {
    this.abrindo = true;
    this.feedback.trigger('PRODUTO_CORRETO');
    this.estado.set({ tipo: 'encontrado', titulo: `${t.numeroUnico} · ${t.cliente}`, mensagem: 'Documento encontrado. Abrindo a conferência…' });
    // Mesmo caminho da Fila da Torre (onConferir): a tela de conferência valida sessão, crachá, etapa e trava.
    this.router.navigate(['/conferencia', t.numeroUnico], { state: { tarefa: t } }).finally(() => {
      this.abrindo = false;
    });
  }

  private mostrarErroComunicacao(): void {
    this.feedback.trigger('ERRO', { toast: false });
    this.estado.set({
      tipo: 'erro',
      titulo: 'Sem comunicação com o servidor',
      mensagem: 'Não foi possível consultar a fila. Verifique a rede e toque em Atualizar.',
    });
    this.focar();
  }

  // ─── Lista ──────────────────────────────────────────────────────────────────────────────────────────────

  trocarOrdem(o: OrdemColetor): void {
    this.ordem.set(o);
    this.pagina.set(1);
    try {
      localStorage.setItem(CHAVE_ORDEM, o);
    } catch {
      /* ignora */
    }
  }

  irPagina(delta: number): void {
    this.pagina.set(this.paginaAtual().pagina + delta);
  }

  status(t: Tarefa) {
    return rotuloStatusColetor(t);
  }

  mostraNf(t: Tarefa): boolean {
    return jaENota(t.tipMov) && t.nf !== '—';
  }

  // ─── Menu / teclado / foco ──────────────────────────────────────────────────────────────────────────────

  alternarTeclado(): void {
    this.tecladoAberto = !this.tecladoAberto;
    try {
      localStorage.setItem(CHAVE_TECLADO, this.tecladoAberto ? 'S' : 'N');
    } catch {
      /* ignora */
    }
    const el = this.campo?.nativeElement;
    el?.blur();
    setTimeout(() => el?.focus({ preventScroll: true }), 30);
  }

  abrirArmazenar(): void {
    this.menuAberto.set(false);
    this.router.navigate(['/coletor/armazenar']);
  }

  abrirTorre(): void {
    this.menuAberto.set(false);
    definirPreferenciaTorre(true);
    this.router.navigate(['/fila-tarefas']);
  }

  sair(): void {
    this.menuAberto.set(false);
    this.auth.logout();
  }

  alternarTema(): void {
    this.tema.alternar();
  }

  private focar(): void {
    setTimeout(() => {
      if (!this.menuAberto() && !this.escolha()) this.campo?.nativeElement.focus({ preventScroll: true });
    });
  }

  // Leitor bipando com o foco fora do campo (depois de tocar na lista, fechar menu...): monta o código pelo ritmo
  // das teclas (leitor < 100 ms entre teclas) e trata como leitura — mesma técnica da conferência.
  private buffer = '';
  private ultimaTecla = 0;

  @HostListener('window:keydown', ['$event'])
  onTecla(ev: KeyboardEvent): void {
    const alvo = ev.target as HTMLElement | null;
    if (alvo === this.campo?.nativeElement || alvo?.tagName === 'SELECT' || alvo?.tagName === 'INPUT') return;
    if (ev.ctrlKey || ev.altKey || ev.metaKey) return;
    const agora = Date.now();
    const rapido = agora - this.ultimaTecla < 100;
    this.ultimaTecla = agora;
    if (ev.key === 'Enter') {
      if (this.buffer.length >= 3 && rapido) {
        ev.preventDefault();
        this.codigo = this.buffer;
        this.buffer = '';
        this.onLer();
      } else {
        this.buffer = '';
      }
      return;
    }
    if (ev.key.length === 1) this.buffer = rapido ? this.buffer + ev.key : ev.key;
  }

  @HostListener('document:keydown.escape')
  fecharSobreposicoes(): void {
    if (this.menuAberto()) this.menuAberto.set(false);
    else if (this.escolha()) this.cancelarEscolha();
  }
}

function lerOrdem(): OrdemColetor {
  try {
    const v = localStorage.getItem(CHAVE_ORDEM);
    if (v && ORDENS_COLETOR.some((o) => o.id === v)) return v as OrdemColetor;
  } catch {
    /* ignora */
  }
  return 'antigos';
}

function lerTeclado(): boolean {
  try {
    const v = localStorage.getItem(CHAVE_TECLADO);
    if (v === 'S' || v === 'N') return v === 'S';
  } catch {
    /* ignora */
  }
  // Coletor (toque, tela pequena): teclado da tela fechado — o leitor digita sozinho. Mesma chave da conferência.
  return !(typeof window !== 'undefined' && window.matchMedia?.('(max-width: 639px) and (pointer: coarse)').matches);
}
