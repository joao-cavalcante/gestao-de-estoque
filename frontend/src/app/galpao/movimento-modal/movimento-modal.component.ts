import { Component, EventEmitter, Input, OnDestroy, OnInit, Output, computed, inject, signal } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { Subject, Subscription, debounceTime, distinctUntilChanged, switchMap, of } from 'rxjs';
import { OqIconComponent } from '../../shared/icons/oq-icon.component';
import { GalpaoService } from '../galpao.service';
import { ControleEstoque, Endereco, EstoqueProduto, MovimentoRequest, ProdutoBusca, SaldoEndereco, TipoMovimentacao } from '../galpao.model';
import { mensagemErro } from '../plantas-lista/plantas-lista.component';

export type TipoModal = Extract<TipoMovimentacao, 'ENDERECAR' | 'TRANSFERIR' | 'RETIRAR' | 'CONTAR'>;

/** UUID v4 — crypto.randomUUID só existe em contexto seguro (HTTPS/localhost). */
export function novoId(): string {
  const c: Crypto = globalThis.crypto;
  if (typeof c.randomUUID === 'function' && window.isSecureContext) return c.randomUUID();
  const b = c.getRandomValues(new Uint8Array(16));
  b[6] = (b[6] & 0x0f) | 0x40;
  b[8] = (b[8] & 0x3f) | 0x80;
  const h = [...b].map((x) => x.toString(16).padStart(2, '0')).join('');
  return `${h.slice(0, 8)}-${h.slice(8, 12)}-${h.slice(12, 16)}-${h.slice(16, 20)}-${h.slice(20)}`;
}

const TITULO: Record<TipoModal, string> = {
  ENDERECAR: 'Endereçar produto',
  TRANSFERIR: 'Transferir entre endereços',
  RETIRAR: 'Retirar do endereço',
  CONTAR: 'Registrar contagem',
};

/**
 * Um movimento por vez a partir do endereço selecionado (fase 4). O comandoId nasce quando o modal abre e
 * só muda se o usuário alterar o que vai mandar — reenviar depois de falha de rede não duplica no servidor.
 */
@Component({
  selector: 'app-movimento-modal',
  standalone: true,
  imports: [CommonModule, FormsModule, OqIconComponent],
  templateUrl: './movimento-modal.component.html',
  styleUrl: './movimento-modal.component.scss',
})
export class MovimentoModalComponent implements OnInit, OnDestroy {
  private readonly service = inject(GalpaoService);

  @Input({ required: true }) plantaId!: string;
  @Input({ required: true }) tipo!: TipoModal;
  @Input({ required: true }) endereco!: Endereco;
  /** Linha de saldo de origem (Transferir/Retirar) ou produto contado (Contar). */
  @Input() saldo: SaldoEndereco | null = null;
  @Input() enderecos: Endereco[] = [];
  @Output() concluido = new EventEmitter<string>();
  @Output() fechar = new EventEmitter<void>();

  readonly titulo = computed(() => TITULO[this.tipo]);
  readonly enviando = signal(false);
  readonly erro = signal<string | null>(null);

  // Produto (Endereçar, ou Contar de produto que não está no endereço)
  readonly termo = signal('');
  readonly resultados = signal<ProdutoBusca[]>([]);
  readonly buscando = signal(false);
  readonly produto = signal<{ codprod: number; produto: string } | null>(null);
  readonly estoque = signal<EstoqueProduto | null>(null);
  readonly carregandoEstoque = signal(false);
  readonly controle = signal('');

  quantidade: number | null = null;
  destinoCodigo = '';
  motivo = '';
  private comandoId = novoId();
  private ultimoEnvio = '';
  private readonly busca$ = new Subject<string>();
  private sub?: Subscription;

  readonly controleSelecionado = computed<ControleEstoque | null>(
    () => this.estoque()?.controles.find((c) => c.controle === this.controle()) ?? null,
  );
  destinosSugeridos(): Endereco[] {
    const t = this.destinoCodigo.trim().toUpperCase();
    return this.enderecos.filter((e) => e.id !== this.endereco.id && !e.bloqueado && (!t || e.codigo.includes(t))).slice(0, 40);
  }

  ngOnInit(): void {
    if (this.saldo) {
      this.produto.set({ codprod: this.saldo.codprod, produto: this.saldo.produto });
      this.controle.set(this.saldo.controle);
      if (this.tipo !== 'CONTAR') this.quantidade = this.saldo.quantidade;
    }
    this.sub = this.busca$
      .pipe(
        debounceTime(250),
        distinctUntilChanged(),
        switchMap((t) => {
          if (t.trim().length < 2 && !/^\d+$/.test(t.trim())) return of([] as ProdutoBusca[]);
          this.buscando.set(true);
          return this.service.buscarProdutos(this.plantaId, t.trim());
        }),
      )
      .subscribe({
        next: (r) => {
          this.resultados.set(r);
          this.buscando.set(false);
        },
        error: () => this.buscando.set(false),
      });
  }

  ngOnDestroy(): void {
    this.sub?.unsubscribe();
  }

  precisaEscolherProduto(): boolean {
    return !this.saldo && (this.tipo === 'ENDERECAR' || this.tipo === 'CONTAR');
  }

  aoDigitar(t: string): void {
    this.termo.set(t);
    this.busca$.next(t);
  }

  escolherProduto(p: ProdutoBusca): void {
    this.produto.set(p);
    this.resultados.set([]);
    this.termo.set('');
    this.erro.set(null);
    if (this.tipo !== 'ENDERECAR') return;
    this.carregandoEstoque.set(true);
    this.service.estoqueProduto(this.plantaId, p.codprod).subscribe({
      next: (e) => {
        this.estoque.set(e);
        this.carregandoEstoque.set(false);
        // Sugere o controle com mais saldo sem endereço.
        const melhor = [...e.controles].sort((a, b) => b.semEndereco - a.semEndereco)[0];
        this.controle.set(melhor?.controle ?? '');
        this.quantidade = melhor && melhor.semEndereco > 0 ? melhor.semEndereco : null;
      },
      error: (err) => {
        this.carregandoEstoque.set(false);
        this.erro.set(mensagemErro(err, 'Não foi possível ler o estoque do produto.'));
      },
    });
  }

  trocarProduto(): void {
    this.produto.set(null);
    this.estoque.set(null);
    this.controle.set('');
    this.quantidade = null;
  }

  escolherControle(c: ControleEstoque): void {
    this.controle.set(c.controle);
    if (c.semEndereco > 0) this.quantidade = c.semEndereco;
  }

  escolherDestino(e: Endereco): void {
    this.destinoCodigo = e.codigo;
  }

  /** Saldo atual do produto/controle num endereço (pra versão e pra mostrar). */
  private saldoEm(e: Endereco | undefined, codprod: number, controle: string): SaldoEndereco | undefined {
    return e?.saldos.find((s) => s.codprod === codprod && s.controle === controle);
  }

  saldoAtualNoEndereco(): number {
    const p = this.produto();
    return p ? (this.saldoEm(this.endereco, p.codprod, this.controle())?.quantidade ?? 0) : 0;
  }

  confirmar(): void {
    const p = this.produto();
    if (!p) return this.erro.set('Escolha o produto.');
    const q = Number(this.quantidade);
    if (this.quantidade === null || Number.isNaN(q) || q < 0 || (this.tipo !== 'CONTAR' && q === 0)) {
      return this.erro.set(this.tipo === 'CONTAR' ? 'Informe a quantidade contada.' : 'Informe uma quantidade maior que zero.');
    }
    const controle = this.controle();
    const req: MovimentoRequest = { comandoId: '', tipo: this.tipo, codprod: p.codprod, controle, quantidade: q, motivo: this.motivo.trim() || null };

    if (this.tipo === 'ENDERECAR' || this.tipo === 'CONTAR') {
      req.destinoId = this.endereco.id;
      req.versaoDestino = this.saldoEm(this.endereco, p.codprod, controle)?.versao ?? 0;
    } else {
      req.origemId = this.endereco.id;
      req.versaoOrigem = this.saldoEm(this.endereco, p.codprod, controle)?.versao ?? 0;
    }
    if (this.tipo === 'TRANSFERIR') {
      const destino = this.enderecos.find((e) => e.codigo === this.destinoCodigo.trim().toUpperCase());
      if (!destino) return this.erro.set('Informe um endereço de destino válido desta planta.');
      if (destino.id === this.endereco.id) return this.erro.set('O destino deve ser diferente da origem.');
      req.destinoId = destino.id;
      req.versaoDestino = this.saldoEm(destino, p.codprod, controle)?.versao ?? 0;
    }

    // Mesmo conteúdo = mesmo comando (repetição segura); mudou algo = comando novo.
    const assinatura = JSON.stringify({ ...req, comandoId: undefined });
    if (assinatura !== this.ultimoEnvio) {
      this.comandoId = novoId();
      this.ultimoEnvio = assinatura;
    }
    req.comandoId = this.comandoId;

    this.enviando.set(true);
    this.erro.set(null);
    this.service.movimentar(this.plantaId, req).subscribe({
      next: (r) => {
        this.enviando.set(false);
        this.concluido.emit(r.mensagem);
      },
      error: (e) => {
        this.enviando.set(false);
        // 4xx = recusado (comando não gravou): a próxima tentativa, se igual, pode reusar o id sem risco.
        this.erro.set(mensagemErro(e, 'Não foi possível registrar. Verifique a conexão e tente de novo.'));
      },
    });
  }

  enderecosTexto(e: EstoqueProduto): string {
    const lista = e.enderecos.slice(0, 6).map((x) => `${x.endereco} (${this.num(x.quantidade)})`).join(', ');
    return e.enderecos.length > 6 ? lista + '…' : lista;
  }

  num(v: number | null | undefined, casas = 3): string {
    return v == null ? '—' : v.toLocaleString('pt-BR', { maximumFractionDigits: casas });
  }
}
