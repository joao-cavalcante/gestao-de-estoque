import { Component, ElementRef, HostListener, OnDestroy, OnInit, ViewChild, computed, inject, signal } from '@angular/core';
import { NgTemplateOutlet } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { HttpErrorResponse } from '@angular/common/http';
import { Router } from '@angular/router';
import { AuthService } from '../../auth/auth.service';
import { ActionFeedbackService } from '../../shared/action-feedback/action-feedback.service';
import { ThemeService } from '../../shared/theme/theme.service';
import { definirPreferenciaTorre } from '../coletor-modo';
import { OqIconComponent } from '../../shared/icons/oq-icon.component';
import { OqSpinnerComponent } from '../../shared/icons/oq-spinner.component';
import {
  MOTIVOS_TROCA,
  TarefaArmazenagem,
  ehDoProduto,
  ehOSugerido,
  falta,
  lerQtd,
  normalizar,
  ordenarTarefas,
  qtdTexto,
  segmentos,
  tarefaPorProduto,
} from './armazenar.logic';
import { ArmazenarService } from './armazenar.service';

/**
 * inicio: "Iniciar armazenagem (N na doca)" · lista: todas as tarefas · produto/endereco: leitura ·
 * troca: bipou outro endereço → motivo na mesma tela · confirmar: Guardar (ou Guardar menos).
 */
type Passo = 'inicio' | 'lista' | 'produto' | 'endereco' | 'troca' | 'confirmar';
type Aviso = { tom: 'ok' | 'erro' | 'atencao'; titulo: string; mensagem?: string } | null;

const INTERVALO_ATUALIZACAO_MS = 60_000;
const JANELA_DUPLICADA_MS = 1500;
const MOTIVO_CHEIO = MOTIVOS_TROCA[0];

/**
 * Armazenar no coletor — redesenho de 27/11/2026 no padrão de mercado de putaway dirigido (Oracle WMS Cloud, Infor,
 * SAP EWM): o sistema entrega a próxima tarefa; o DESTINO é o elemento principal (rua · módulo · nível em blocos);
 * bipar outro endereço vira a exceção "trocar destino" com motivo na mesma tela; "Endereço cheio" já registra o motivo.
 * Visual UNIFICADO com a fila do coletor (2ª revisão, 27/11/2026): mesmo cabeçalho, leitura no topo, tarja do
 * sinaleiro, linhas da lista e rodapé com ⚙; sem fundo preto. Regras de negócio inalteradas (addon TOR).
 */
@Component({
  selector: 'app-armazenar',
  standalone: true,
  imports: [FormsModule, NgTemplateOutlet, OqIconComponent, OqSpinnerComponent],
  templateUrl: './armazenar.component.html',
  styleUrls: ['../coletor-fila.component.scss', './armazenar.component.scss'],
})
export class ArmazenarComponent implements OnInit, OnDestroy {
  private readonly api = inject(ArmazenarService);
  private readonly auth = inject(AuthService);
  private readonly router = inject(Router);
  private readonly feedback = inject(ActionFeedbackService);
  readonly tema = inject(ThemeService);

  @ViewChild('campo') campo?: ElementRef<HTMLInputElement>;

  readonly motivos = MOTIVOS_TROCA;
  readonly operador = computed(() => this.auth.usuario()?.nome ?? '');
  readonly qtdTexto = qtdTexto;
  readonly falta = falta;

  readonly tarefas = signal<TarefaArmazenagem[] | null>(null);
  readonly carregando = signal(false);
  readonly erroCarga = signal<string | null>(null);
  readonly passo = signal<Passo>('inicio');
  readonly atual = signal<TarefaArmazenagem | null>(null);
  readonly destino = signal<string | null>(null);
  readonly motivo = signal<string | null>(null);
  readonly ocupado = signal(false);
  readonly aviso = signal<Aviso>(null);
  readonly escolha = signal<TarefaArmazenagem[] | null>(null);
  readonly menos = signal(false);
  readonly menuAberto = signal(false);
  /** "Endereço cheio" já deu o motivo: o próximo endereço bipado vai direto para o Guardar. */
  private trocaAutorizada = false;

  readonly lista = computed(() => ordenarTarefas(this.tarefas() ?? []));
  readonly proxima = computed(() => this.lista().find((t) => t.nutar !== this.atual()?.nutar) ?? null);
  readonly posicao = computed(() => {
    const t = this.atual();
    const l = this.lista();
    const i = t ? l.findIndex((x) => x.nutar === t.nutar) : -1;
    return { atual: i >= 0 ? i + 1 : 1, total: Math.max(l.length, 1) };
  });
  readonly destinoMostrado = computed(() => {
    const p = this.passo();
    return (p === 'troca' || p === 'confirmar') && this.destino() ? this.destino() : this.atual()?.sugerido ?? null;
  });
  readonly blocos = computed(() => segmentos(this.destinoMostrado()));
  readonly trocou = computed(() => {
    const t = this.atual();
    const d = this.destino();
    return !!t && !!d && !ehOSugerido(d, t);
  });

  codigo = '';
  qtdDigitada = '';
  motivoOutro = '';

  private timer?: ReturnType<typeof setInterval>;
  private ultimaLeitura = { codigo: '', em: 0 };

  ngOnInit(): void {
    this.carregar();
    this.timer = setInterval(() => {
      const p = this.passo();
      if (document.visibilityState === 'visible' && (p === 'inicio' || p === 'lista') && !this.ocupado()) this.carregar();
    }, INTERVALO_ATUALIZACAO_MS);
    this.focar();
  }

  ngOnDestroy(): void {
    clearInterval(this.timer);
  }

  // ─── Carga ──────────────────────────────────────────────────────────────────────────────────────────────

  carregar(depois?: () => void): void {
    this.carregando.set(true);
    this.api.listar().subscribe({
      next: (l) => {
        this.tarefas.set(l);
        this.erroCarga.set(null);
        this.carregando.set(false);
        depois?.();
      },
      error: (e) => {
        this.carregando.set(false);
        this.erroCarga.set(erro(e, 'As tarefas não foram carregadas.'));
        depois?.();
      },
    });
  }

  // ─── Leitura (um campo para todos os passos) ────────────────────────────────────────────────────────────

  rotuloLeitura(): string {
    switch (this.passo()) {
      case 'produto':
        return 'Bipe o produto';
      case 'endereco':
        return this.trocaAutorizada ? 'Bipe o novo endereço' : 'Bipe a etiqueta do endereço';
      case 'troca':
      case 'confirmar':
        return 'Bipe outro endereço para trocar';
      default:
        return 'Bipe um produto para começar por ele';
    }
  }

  onLer(): void {
    const codigo = normalizar(this.codigo);
    this.codigo = '';
    if (!codigo || this.ocupado()) return;
    const agora = Date.now();
    if (codigo === this.ultimaLeitura.codigo && agora - this.ultimaLeitura.em < JANELA_DUPLICADA_MS) return;
    this.ultimaLeitura = { codigo, em: agora };
    this.feedback.trigger('ITEM_LIDO');

    const p = this.passo();
    if (p === 'inicio' || p === 'lista') this.lerNaLista(codigo);
    else if (p === 'produto') this.lerProduto(codigo);
    else this.lerEndereco(codigo);
    this.focar();
  }

  private lerNaLista(codigo: string): void {
    const achadas = tarefaPorProduto(codigo, this.lista());
    if (achadas.length === 0) {
      this.feedback.trigger('PRODUTO_NAO_ENCONTRADO');
      this.aviso.set({ tom: 'erro', titulo: `${codigo}: nenhuma tarefa`, mensagem: 'Não há tarefa de armazenagem aberta para este produto.' });
      return;
    }
    if (achadas.length > 1) {
      this.feedback.trigger('CONTROLE_NECESSARIO');
      this.escolha.set(achadas);
      this.passo.set('lista');
      this.aviso.set({ tom: 'atencao', titulo: 'Mais de uma tarefa deste produto', mensagem: 'Escolha a compra.' });
      return;
    }
    this.abrir(achadas[0], true);
  }

  private lerProduto(codigo: string): void {
    const t = this.atual();
    if (!t) return;
    if (!ehDoProduto(codigo, t)) {
      this.feedback.trigger('PRODUTO_NAO_ENCONTRADO');
      this.aviso.set({ tom: 'erro', titulo: 'Produto errado', mensagem: `${codigo} não é ${t.codprod} · ${t.descricao}.` });
      return;
    }
    this.feedback.trigger('PRODUTO_CORRETO');
    this.aviso.set(null);
    this.passo.set('endereco');
  }

  private lerEndereco(codigo: string): void {
    const t = this.atual();
    if (!t) return;
    if (t.origem && normalizar(t.origem) === codigo) {
      this.feedback.trigger('OPERACAO_NAO_PERMITIDA');
      this.aviso.set({ tom: 'erro', titulo: 'Essa é a doca', mensagem: 'Bipe o endereço onde o produto vai ficar.' });
      return;
    }
    this.destino.set(codigo);
    this.menos.set(false);
    this.qtdDigitada = qtdTexto(falta(t));
    if (ehOSugerido(codigo, t)) {
      this.feedback.trigger('PRODUTO_CORRETO');
      this.motivo.set(null);
      this.trocaAutorizada = false;
      this.aviso.set(null);
      this.passo.set('confirmar');
    } else if (this.trocaAutorizada && this.motivo()) {
      this.feedback.trigger('PRODUTO_CORRETO');
      this.aviso.set(null);
      this.passo.set('confirmar');
    } else {
      this.feedback.trigger('CONTROLE_NECESSARIO');
      this.motivo.set(null);
      this.motivoOutro = '';
      this.aviso.set(null);
      this.passo.set('troca');
    }
  }

  // ─── Passos ─────────────────────────────────────────────────────────────────────────────────────────────

  /** Sistema entrega a próxima: em execução primeiro, depois a mais antiga. */
  iniciar(): void {
    const t = this.lista()[0];
    if (t) this.abrir(t);
  }

  verTodas(): void {
    this.aviso.set(null);
    this.passo.set('lista');
  }

  escolher(t: TarefaArmazenagem): void {
    this.escolha.set(null);
    this.abrir(t, true);
  }

  /** Abre a tarefa: o addon marca em execução e recalcula a sugestão. [produtoConferido] = chegou bipando o produto. */
  abrir(t: TarefaArmazenagem, produtoConferido = false): void {
    if (this.ocupado()) return;
    this.ocupado.set(true);
    this.escolha.set(null);
    this.api.iniciar(t.nutar).subscribe({
      next: (nova) => {
        this.ocupado.set(false);
        this.atual.set(nova);
        this.destino.set(null);
        this.motivo.set(null);
        this.trocaAutorizada = false;
        this.menos.set(false);
        if (this.aviso()?.tom !== 'ok') this.aviso.set(null);
        this.passo.set(produtoConferido ? 'endereco' : 'produto');
        if (produtoConferido) this.feedback.trigger('PRODUTO_CORRETO');
        this.focar();
      },
      error: (e) => {
        this.ocupado.set(false);
        this.feedback.trigger('ERRO', { toast: false });
        this.aviso.set({ tom: 'erro', titulo: `Tarefa ${t.nutar} não abriu`, mensagem: erro(e, 'Tente de novo.') });
        this.carregar();
        this.focar();
      },
    });
  }

  /** Exceção mais comum do mercado: o sugerido está cheio → motivo já registrado, operador bipa outro. */
  enderecoCheio(): void {
    this.motivo.set(MOTIVO_CHEIO);
    this.trocaAutorizada = true;
    this.destino.set(null);
    this.passo.set('endereco');
    this.feedback.trigger('CONTROLE_NECESSARIO');
    this.aviso.set({ tom: 'atencao', titulo: 'Endereço cheio registrado', mensagem: 'Bipe o endereço onde o produto vai ficar.' });
    this.focar();
  }

  escolherMotivo(m: string): void {
    this.motivo.set(m);
    this.passo.set('confirmar');
    this.focar();
  }

  confirmarMotivoOutro(): void {
    const m = this.motivoOutro.trim();
    if (m.length < 3) {
      this.aviso.set({ tom: 'erro', titulo: 'Escreva o motivo', mensagem: 'Pelo menos 3 letras.' });
      return;
    }
    this.aviso.set(null);
    this.escolherMotivo(m);
  }

  manterSugerido(): void {
    this.destino.set(null);
    this.motivo.set(null);
    this.trocaAutorizada = false;
    this.passo.set('endereco');
    this.focar();
  }

  guardarMenos(): void {
    this.menos.set(true);
    setTimeout(() => {
      const el = document.getElementById('arm-qtd') as HTMLInputElement | null;
      el?.focus();
      el?.select();
    });
  }

  guardar(): void {
    const t = this.atual();
    const d = this.destino();
    if (!t || !d || this.ocupado()) return;
    const q = this.menos() ? lerQtd(this.qtdDigitada) : falta(t);
    if (q == null || q <= 0) {
      this.aviso.set({ tom: 'erro', titulo: 'Quantidade inválida', mensagem: 'Digite quanto foi guardado neste endereço.' });
      return;
    }
    if (q > falta(t)) {
      this.aviso.set({ tom: 'erro', titulo: 'Mais que a tarefa', mensagem: `Falta guardar ${qtdTexto(falta(t))} ${t.unidade ?? ''}.` });
      return;
    }
    this.ocupado.set(true);
    this.api.armazenar(t.nutar, d, q, this.trocou() ? this.motivo() : null).subscribe({
      next: (nova) => {
        this.ocupado.set(false);
        const resto = falta(nova);
        this.trocaAutorizada = false;
        if (nova.situacao === 'CO' || resto <= 0) {
          this.feedback.trigger('SUCESSO_SANKHYA');
          this.aviso.set({ tom: 'ok', titulo: `Guardado em ${d}`, mensagem: `${qtdTexto(q)} ${t.unidade ?? ''} · ${t.descricao}` });
          this.atual.set(null);
          this.destino.set(null);
          this.motivo.set(null);
          this.passo.set('inicio');
          this.carregar();
        } else {
          this.feedback.trigger('PRODUTO_CORRETO');
          this.atual.set(nova);
          this.destino.set(null);
          this.motivo.set(null);
          this.menos.set(false);
          this.passo.set('endereco');
          this.aviso.set({ tom: 'ok', titulo: `${qtdTexto(q)} guardado em ${d}`, mensagem: `Faltam ${qtdTexto(resto)} ${t.unidade ?? ''}: bipe o próximo endereço.` });
        }
        this.focar();
      },
      error: (e) => {
        this.ocupado.set(false);
        this.feedback.trigger('OPERACAO_NAO_PERMITIDA');
        this.aviso.set({ tom: 'erro', titulo: 'Não guardou', mensagem: erro(e, 'Confira a rede e tente de novo.') });
        // Resposta perdida pode ter gravado: relê a tarefa antes de deixar tentar de novo.
        this.api.iniciar(t.nutar).subscribe({ next: (r) => this.atual.set(r), error: () => undefined });
        this.focar();
      },
    });
  }

  /** Volta ao início (a tarefa segue em execução no Sankhya com o que já foi guardado). */
  voltar(): void {
    this.passo.set('inicio');
    this.atual.set(null);
    this.destino.set(null);
    this.motivo.set(null);
    this.trocaAutorizada = false;
    this.escolha.set(null);
    this.aviso.set(null);
    this.carregar();
    this.focar();
  }

  irConferencia(): void {
    this.menuAberto.set(false);
    this.router.navigate(['/coletor']);
  }

  /** Menu único do coletor: aqui a atividade ativa é o Armazenar. */
  abrirArmazenar(): void {
    this.menuAberto.set(false);
  }

  alternarTema(): void {
    this.tema.alternar();
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

  textoGuardar(): string {
    const t = this.atual();
    return t ? `${qtdTexto(falta(t))} ${t.unidade ?? ''}`.trim() : '';
  }

  emTarefa(): boolean {
    const p = this.passo();
    return p !== 'inicio' && p !== 'lista';
  }

  // ─── Foco / leitor com foco fora do campo ───────────────────────────────────────────────────────────────

  private focar(): void {
    setTimeout(() => {
      if (!this.menos() && !this.menuAberto()) this.campo?.nativeElement.focus({ preventScroll: true });
    });
  }

  private buffer = '';
  private ultimaTecla = 0;

  @HostListener('window:keydown', ['$event'])
  onTecla(ev: KeyboardEvent): void {
    const alvo = ev.target as HTMLElement | null;
    if (alvo?.tagName === 'INPUT' || alvo?.tagName === 'TEXTAREA' || alvo?.tagName === 'SELECT') return;
    if (ev.ctrlKey || ev.altKey || ev.metaKey) return;
    const agora = Date.now();
    const rapido = agora - this.ultimaTecla < 100;
    this.ultimaTecla = agora;
    if (ev.key === 'Enter') {
      if (this.buffer.length >= 2 && rapido) {
        ev.preventDefault();
        this.codigo = this.buffer;
        this.buffer = '';
        this.onLer();
      } else this.buffer = '';
      return;
    }
    if (ev.key.length === 1) this.buffer = rapido ? this.buffer + ev.key : ev.key;
  }

  @HostListener('document:keydown.escape')
  fechar(): void {
    if (this.menuAberto()) this.menuAberto.set(false);
  }
}

function erro(e: unknown, padrao: string): string {
  if (e instanceof HttpErrorResponse) {
    if (e.status === 0) return 'Sem comunicação com o servidor.';
    const m = (e.error as { erro?: string } | null)?.erro;
    if (m) return m;
  }
  return padrao;
}
