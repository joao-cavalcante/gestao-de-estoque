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
  lerEndereco,
  lerQtd,
  normalizar,
  ordenarTarefas,
  qtdOperador,
  qtdTexto,
  segmentos,
  tarefaPorProduto,
} from './armazenar.logic';
import { ArmazenarService } from './armazenar.service';

/**
 * inicio: lista + "Iniciar armazenagem" · lista: escolha entre compras do mesmo produto · produto/endereco: leitura ·
 * troca: bipou outro endereço → motivo · parcial: quantidade antes de bipar o endereço.
 */
type Passo = 'inicio' | 'lista' | 'produto' | 'endereco' | 'troca' | 'parcial';
type Aviso = { tom: 'ok' | 'erro' | 'atencao'; titulo: string; mensagem?: string } | null;

const INTERVALO_ATUALIZACAO_MS = 60_000;
const JANELA_DUPLICADA_MS = 1500;
const MOTIVO_CHEIO = MOTIVOS_TROCA[0];

/**
 * Armazenar no coletor (endereçamento). Visual unificado com a fila do coletor (27/11/2026).
 * 3ª revisão (usuário, 27/11/2026), no padrão de putaway por RF do mercado (Oracle WMS Cloud, Infor, SAP EWM):
 *  - BIPAR O ENDEREÇO É A CONFIRMAÇÃO: guarda a quantidade toda na hora (sem botão "Guardar" — luva/dedo grande);
 *  - CAIXA "Quantidade a guardar" (4ª revisão, 27/11/2026 — putaway por item, como o Infor): já vem com tudo o que
 *    falta, na unidade da compra; − / + de 1 em 1 caixa e toque no número para digitar. Bipar guarda o que está na caixa;
 *  - "Endereço cheio" pede ao addon a PRÓXIMA sugestão, pulando o cheio (antes só pedia para bipar outro);
 *  - quantidade na unidade em que a compra veio ("30 FD", com "180 GF" de apoio).
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
  /** Endereço bipado diferente do sugerido, esperando o motivo. */
  readonly destino = signal<string | null>(null);
  readonly ocupado = signal(false);
  readonly aviso = signal<Aviso>(null);
  readonly escolha = signal<TarefaArmazenagem[] | null>(null);
  readonly menuAberto = signal(false);
  /** Quantidade do "Parcial" (null = guarda tudo o que falta). */
  readonly qtdParcial = signal<number | null>(null);
  /** Endereços que o operador achou cheios nesta tarefa (a sugestão pula). */
  private cheios: string[] = [];

  readonly lista = computed(() => ordenarTarefas(this.tarefas() ?? []));
  readonly posicao = computed(() => {
    const t = this.atual();
    const l = this.lista();
    const i = t ? l.findIndex((x) => x.nutar === t.nutar) : -1;
    return { atual: i >= 0 ? i + 1 : 1, total: Math.max(l.length, 1) };
  });
  readonly destinoMostrado = computed(() => (this.passo() === 'troca' && this.destino() ? this.destino() : this.atual()?.sugerido ?? null));
  readonly blocos = computed(() => segmentos(this.destinoMostrado()));
  /** O que vai ser guardado agora (parcial ou tudo o que falta), na unidade do operador. */
  readonly aGuardar = computed(() => {
    const t = this.atual();
    if (!t) return null;
    return qtdOperador(t, this.qtdParcial() ?? falta(t));
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

  qtd(t: TarefaArmazenagem, q: number) {
    return qtdOperador(t, q);
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
        return this.qtdParcial() != null ? 'Bipe o endereço para guardar a parte' : 'Bipe o endereço para guardar';
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
    else if (p === 'endereco') this.lerDestino(codigo);
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
      this.aviso.set({ tom: 'erro', titulo: 'Produto errado', mensagem: `${codigo} não é ${t.codprod} - ${t.descricao}.` });
      return;
    }
    this.feedback.trigger('PRODUTO_CORRETO');
    this.aviso.set(null);
    this.passo.set('endereco');
  }

  /** Bipar o endereço confirma: o sugerido guarda na hora; outro pede o motivo e então guarda. */
  private lerDestino(lido: string): void {
    const t = this.atual();
    if (!t) return;
    const codigo = lerEndereco(lido, t.sugerido);
    if (t.origem && normalizar(t.origem) === codigo) {
      this.feedback.trigger('OPERACAO_NAO_PERMITIDA');
      this.aviso.set({ tom: 'erro', titulo: 'Essa é a doca', mensagem: 'Bipe o endereço onde o produto vai ficar.' });
      return;
    }
    if (ehOSugerido(codigo, t)) {
      this.guardar(codigo, null);
      return;
    }
    this.feedback.trigger('CONTROLE_NECESSARIO');
    this.destino.set(codigo);
    this.motivoOutro = '';
    this.aviso.set(null);
    this.passo.set('troca');
  }

  // ─── Passos ─────────────────────────────────────────────────────────────────────────────────────────────

  /** Sistema entrega a próxima: em execução primeiro, depois a mais antiga. */
  iniciar(): void {
    const t = this.lista()[0];
    if (t) this.abrir(t);
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
    this.cheios = [];
    this.api.iniciar(t.nutar).subscribe({
      next: (nova) => {
        this.ocupado.set(false);
        this.atual.set(nova);
        this.destino.set(null);
        this.qtdParcial.set(null);
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

  /** O sugerido está cheio: o addon devolve a próxima sugestão, pulando os que o operador já achou cheios. */
  enderecoCheio(): void {
    const t = this.atual();
    if (!t?.sugerido || this.ocupado()) return;
    const cheio = t.sugerido;
    this.cheios = [...new Set([...this.cheios, cheio])];
    this.ocupado.set(true);
    this.api.iniciar(t.nutar, this.cheios).subscribe({
      next: (nova) => {
        this.ocupado.set(false);
        this.atual.set(nova);
        this.feedback.trigger('CONTROLE_NECESSARIO');
        this.aviso.set(
          nova.sugerido
            ? { tom: 'atencao', titulo: `${cheio} cheio`, mensagem: `Nova sugestão: ${nova.sugerido}.` }
            : { tom: 'atencao', titulo: `${cheio} cheio`, mensagem: 'Não há outro endereço livre no setor: bipe onde vai guardar.' },
        );
        this.focar();
      },
      error: (e) => {
        this.ocupado.set(false);
        this.aviso.set({ tom: 'erro', titulo: 'Não buscou outro endereço', mensagem: erro(e, 'Tente de novo.') });
        this.focar();
      },
    });
  }

  escolherMotivo(m: string): void {
    const d = this.destino();
    if (d) this.guardar(d, m);
  }

  confirmarMotivoOutro(): void {
    const m = this.motivoOutro.trim();
    if (m.length < 3) {
      this.aviso.set({ tom: 'erro', titulo: 'Escreva o motivo', mensagem: 'Pelo menos 3 letras.' });
      return;
    }
    this.escolherMotivo(m);
  }

  cancelarTroca(): void {
    this.destino.set(null);
    this.aviso.set(null);
    this.passo.set('endereco');
    this.focar();
  }

  /** Toque no número da caixa: digita a quantidade (na unidade da caixa). */
  abrirParcial(): void {
    const t = this.atual();
    if (!t) return;
    this.qtdDigitada = qtdTexto(this.caixa()?.valor ?? 0);
    this.aviso.set(null);
    this.passo.set('parcial');
    setTimeout(() => document.getElementById('arm-qtd')?.focus());
  }

  /**
   * Unidade da caixa de quantidade: a da compra (FD, CX) quando o que falta fecha embalagem inteira; senão a padrão
   * (pesável: 1.142 KG não é caixa inteira). O Sankhya movimenta na padrão (× fator).
   */
  unidadeParcial(t: TarefaArmazenagem): { unidade: string; fator: number } {
    const f = t.fatorCompra ?? 0;
    const alt = f > 0 ? falta(t) / f : 0;
    return t.unidadeCompra && f > 0 && t.unidadeCompra !== t.unidade && Math.abs(alt - Math.round(alt)) < 0.001
      ? { unidade: t.unidadeCompra, fator: f }
      : { unidade: t.unidade ?? '', fator: 1 };
  }

  /** Caixa "Quantidade a guardar": valor e máximo na unidade da caixa. */
  readonly caixa = computed(() => {
    const t = this.atual();
    if (!t) return null;
    const u = this.unidadeParcial(t);
    const max = Math.round((falta(t) / u.fator) * 1000) / 1000;
    const valor = Math.round(((this.qtdParcial() ?? falta(t)) / u.fator) * 1000) / 1000;
    return { unidade: u.unidade, fator: u.fator, max, valor, parcial: this.qtdParcial() != null };
  });

  private definirCaixa(valor: number): void {
    const c = this.caixa();
    if (!c) return;
    const v = Math.min(Math.max(valor, 0), c.max);
    if (v <= 0) return;
    this.qtdParcial.set(v >= c.max ? null : Math.round(v * c.fator * 1000) / 1000);
  }

  mais(): void {
    const c = this.caixa();
    if (c) this.definirCaixa(c.valor + 1);
    this.focar();
  }

  menos(): void {
    const c = this.caixa();
    if (c && c.valor - 1 > 0) this.definirCaixa(c.valor - 1);
    this.focar();
  }

  /** Quantidade digitada na unidade da compra; o Sankhya movimenta na padrão (× fator). */
  confirmarParcial(): void {
    const t = this.atual();
    if (!t) return;
    const lida = lerQtd(this.qtdDigitada);
    const q = lida == null ? null : Math.round(lida * this.unidadeParcial(t).fator * 1000) / 1000;
    if (q == null || q <= 0) {
      this.aviso.set({ tom: 'erro', titulo: 'Quantidade inválida', mensagem: 'Digite quanto vai guardar neste endereço.' });
      return;
    }
    if (q > falta(t)) {
      this.aviso.set({ tom: 'erro', titulo: 'Mais que o que falta', mensagem: `Falta ${qtdOperador(t, falta(t)).principal}.` });
      return;
    }
    this.qtdParcial.set(q >= falta(t) ? null : q);
    this.aviso.set(null);
    this.passo.set('endereco');
    this.focar();
  }

  cancelarParcial(): void {
    this.qtdParcial.set(null);
    this.aviso.set(null);
    this.passo.set('endereco');
    this.focar();
  }

  private guardar(endereco: string, motivo: string | null): void {
    const t = this.atual();
    if (!t || this.ocupado()) return;
    const q = this.qtdParcial() ?? falta(t);
    const texto = qtdOperador(t, q).principal;
    // "Endereço cheio" seguido de outro endereço: o motivo da troca já é o cheio.
    const m = motivo ?? (this.cheios.length && !ehOSugerido(endereco, t) ? MOTIVO_CHEIO : null);
    this.ocupado.set(true);
    this.api.armazenar(t.nutar, endereco, q, m).subscribe({
      next: (nova) => {
        this.ocupado.set(false);
        this.qtdParcial.set(null);
        this.destino.set(null);
        const resto = falta(nova);
        if (nova.situacao === 'CO' || resto <= 0) {
          this.feedback.trigger('SUCESSO_SANKHYA');
          this.aviso.set({ tom: 'ok', titulo: `Guardado em ${endereco}`, mensagem: `${t.codprod} - ${texto} · ${t.descricao}` });
          this.atual.set(null);
          this.passo.set('inicio');
          this.carregar();
        } else {
          this.feedback.trigger('PRODUTO_CORRETO');
          this.atual.set(nova);
          this.passo.set('endereco');
          this.aviso.set({ tom: 'ok', titulo: `${texto} guardado em ${endereco}`, mensagem: `Falta ${qtdOperador(nova, resto).principal}: bipe o próximo endereço.` });
        }
        this.focar();
      },
      error: (e) => {
        this.ocupado.set(false);
        this.feedback.trigger('OPERACAO_NAO_PERMITIDA');
        this.passo.set('endereco');
        this.destino.set(null);
        this.aviso.set({ tom: 'erro', titulo: `Não guardou em ${endereco}`, mensagem: erro(e, 'Confira a rede e tente de novo.') });
        // Resposta perdida pode ter gravado: relê a tarefa antes de deixar tentar de novo.
        this.api.iniciar(t.nutar, this.cheios).subscribe({ next: (r) => this.atual.set(r), error: () => undefined });
        this.focar();
      },
    });
  }

  /** Volta ao início (a tarefa segue em execução no Sankhya com o que já foi guardado). */
  voltar(): void {
    this.passo.set('inicio');
    this.atual.set(null);
    this.destino.set(null);
    this.qtdParcial.set(null);
    this.cheios = [];
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
  abrirRecebimento(): void {
    this.menuAberto.set(false);
    this.router.navigate(['/coletor/recebimento']);
  }

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

  emTarefa(): boolean {
    const p = this.passo();
    return p !== 'inicio' && p !== 'lista';
  }

  // ─── Foco / leitor com foco fora do campo ───────────────────────────────────────────────────────────────

  private focar(): void {
    setTimeout(() => {
      if (this.passo() !== 'parcial' && !this.menuAberto()) this.campo?.nativeElement.focus({ preventScroll: true });
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
