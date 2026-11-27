import { Component, ElementRef, HostListener, OnDestroy, OnInit, ViewChild, computed, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { HttpErrorResponse } from '@angular/common/http';
import { Router } from '@angular/router';
import { AuthService } from '../../auth/auth.service';
import { ActionFeedbackService } from '../../shared/action-feedback/action-feedback.service';
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
  tarefaPorProduto,
} from './armazenar.logic';
import { ArmazenarService } from './armazenar.service';

type Passo = 'lista' | 'produto' | 'endereco' | 'motivo' | 'quantidade';
type Aviso = { tom: 'ok' | 'erro' | 'atencao'; titulo: string; mensagem?: string } | null;

const INTERVALO_ATUALIZACAO_MS = 60_000;
const JANELA_DUPLICADA_MS = 1500;

/**
 * Armazenar no coletor (endereçamento, etapa 3b — 27/11/2026). Mesmo visual e leitura da fila do coletor
 * (campo único sempre pronto para o gatilho). Passos: tarefa → confere o produto → bipa o endereço (sugerido em
 * destaque; outro exige motivo) → confirma a quantidade → o addon move doca → endereço numa transação.
 * Guardar parte da quantidade é permitido: a tarefa continua em execução com o que falta.
 */
@Component({
  selector: 'app-armazenar',
  standalone: true,
  imports: [FormsModule, OqIconComponent, OqSpinnerComponent],
  templateUrl: './armazenar.component.html',
  styleUrls: ['../coletor-fila.component.scss', './armazenar.component.scss'],
})
export class ArmazenarComponent implements OnInit, OnDestroy {
  private readonly api = inject(ArmazenarService);
  private readonly auth = inject(AuthService);
  private readonly router = inject(Router);
  private readonly feedback = inject(ActionFeedbackService);

  @ViewChild('campo') campo?: ElementRef<HTMLInputElement>;

  readonly motivos = MOTIVOS_TROCA;
  readonly operador = computed(() => this.auth.usuario()?.nome ?? '');
  readonly qtdTexto = qtdTexto;
  readonly falta = falta;

  readonly tarefas = signal<TarefaArmazenagem[] | null>(null);
  readonly carregando = signal(false);
  readonly erroCarga = signal<string | null>(null);
  readonly passo = signal<Passo>('lista');
  readonly atual = signal<TarefaArmazenagem | null>(null);
  readonly destino = signal<string | null>(null);
  readonly motivo = signal<string | null>(null);
  readonly ocupado = signal(false);
  readonly aviso = signal<Aviso>(null);
  readonly escolha = signal<TarefaArmazenagem[] | null>(null);

  readonly lista = computed(() => ordenarTarefas(this.tarefas() ?? []));
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
      if (document.visibilityState === 'visible' && this.passo() === 'lista' && !this.ocupado()) this.carregar();
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
      case 'lista':
        return 'Bipe o produto ou toque numa tarefa';
      case 'produto':
        return 'Bipe o produto para conferir';
      case 'endereco':
        return 'Bipe a etiqueta do endereço';
      default:
        return '';
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
    if (p === 'lista') this.lerNaLista(codigo);
    else if (p === 'produto') this.lerProduto(codigo);
    else if (p === 'endereco') this.lerEndereco(codigo);
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
      this.aviso.set({ tom: 'erro', titulo: 'Essa é a doca', mensagem: 'Bipe o endereço onde o produto vai ser guardado.' });
      return;
    }
    this.destino.set(codigo);
    this.qtdDigitada = qtdTexto(falta(t));
    if (ehOSugerido(codigo, t)) {
      this.feedback.trigger('PRODUTO_CORRETO');
      this.motivo.set(null);
      this.aviso.set(null);
      this.passo.set('quantidade');
    } else {
      this.feedback.trigger('CONTROLE_NECESSARIO');
      this.aviso.set({
        tom: 'atencao',
        titulo: t.sugerido ? `${codigo} não é o sugerido (${t.sugerido})` : `Sem sugestão: guardar em ${codigo}`,
        mensagem: 'Informe o motivo da escolha.',
      });
      this.motivo.set(null);
      this.motivoOutro = '';
      this.passo.set('motivo');
    }
  }

  // ─── Passos ─────────────────────────────────────────────────────────────────────────────────────────────

  escolher(t: TarefaArmazenagem): void {
    this.escolha.set(null);
    this.abrir(t, true);
  }

  /** Abre a tarefa: o addon marca em execução e recalcula a sugestão. [produtoConferido] = chegou bipando o produto. */
  abrir(t: TarefaArmazenagem, produtoConferido = false): void {
    if (this.ocupado()) return;
    this.ocupado.set(true);
    this.aviso.set(null);
    this.escolha.set(null);
    this.api.iniciar(t.nutar).subscribe({
      next: (nova) => {
        this.ocupado.set(false);
        this.atual.set(nova);
        this.destino.set(null);
        this.motivo.set(null);
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

  escolherMotivo(m: string): void {
    this.motivo.set(m);
    this.aviso.set(null);
    this.passo.set('quantidade');
    this.focarQtd();
  }

  confirmarMotivoOutro(): void {
    const m = this.motivoOutro.trim();
    if (m.length < 3) {
      this.aviso.set({ tom: 'erro', titulo: 'Escreva o motivo', mensagem: 'Pelo menos 3 letras.' });
      return;
    }
    this.escolherMotivo(m);
  }

  rebiparEndereco(): void {
    this.destino.set(null);
    this.motivo.set(null);
    this.aviso.set(null);
    this.passo.set('endereco');
    this.focar();
  }

  guardar(): void {
    const t = this.atual();
    const d = this.destino();
    if (!t || !d || this.ocupado()) return;
    const q = lerQtd(this.qtdDigitada);
    if (q == null) {
      this.aviso.set({ tom: 'erro', titulo: 'Quantidade inválida', mensagem: 'Digite quanto foi guardado neste endereço.' });
      return;
    }
    if (q > falta(t)) {
      this.aviso.set({ tom: 'erro', titulo: 'Quantidade maior que a tarefa', mensagem: `Falta guardar ${qtdTexto(falta(t))} ${t.unidade ?? ''}.` });
      return;
    }
    this.ocupado.set(true);
    this.api.armazenar(t.nutar, d, q, this.trocou() ? this.motivo() : null).subscribe({
      next: (nova) => {
        this.ocupado.set(false);
        const resto = falta(nova);
        if (nova.situacao === 'CO' || resto <= 0) {
          this.feedback.trigger('SUCESSO_SANKHYA');
          this.aviso.set({ tom: 'ok', titulo: `Guardado em ${d}`, mensagem: `${qtdTexto(q)} ${t.unidade ?? ''} · ${t.descricao}. Tarefa concluída.` });
          this.voltar(false);
        } else {
          this.feedback.trigger('PRODUTO_CORRETO');
          this.atual.set(nova);
          this.destino.set(null);
          this.motivo.set(null);
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
        this.focarQtd();
      },
    });
  }

  /** Volta para a lista (a tarefa continua em execução no Sankhya, com o que já foi guardado). */
  voltar(limparAviso = true): void {
    this.passo.set('lista');
    this.atual.set(null);
    this.destino.set(null);
    this.motivo.set(null);
    if (limparAviso) this.aviso.set(null);
    this.carregar();
    this.focar();
  }

  irConferencia(): void {
    this.router.navigate(['/coletor']);
  }

  progresso(t: TarefaArmazenagem): number {
    return t.qtd > 0 ? Math.min(100, Math.round((t.qtdFeita / t.qtd) * 100)) : 0;
  }

  // ─── Foco / leitor com foco fora do campo ───────────────────────────────────────────────────────────────

  private focar(): void {
    setTimeout(() => this.campo?.nativeElement.focus({ preventScroll: true }));
  }

  private focarQtd(): void {
    setTimeout(() => {
      const el = document.getElementById('arm-qtd') as HTMLInputElement | null;
      el?.focus();
      el?.select();
    });
  }

  private buffer = '';
  private ultimaTecla = 0;

  @HostListener('window:keydown', ['$event'])
  onTecla(ev: KeyboardEvent): void {
    const alvo = ev.target as HTMLElement | null;
    if (alvo?.tagName === 'INPUT' || alvo?.tagName === 'TEXTAREA' || alvo?.tagName === 'SELECT') return;
    if (ev.ctrlKey || ev.altKey || ev.metaKey) return;
    if (!['lista', 'produto', 'endereco'].includes(this.passo())) return;
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
}

function erro(e: unknown, padrao: string): string {
  if (e instanceof HttpErrorResponse) {
    if (e.status === 0) return 'Sem comunicação com o servidor.';
    const m = (e.error as { erro?: string } | null)?.erro;
    if (m) return m;
  }
  return padrao;
}
