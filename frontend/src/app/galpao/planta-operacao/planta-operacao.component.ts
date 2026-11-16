import { Component, OnInit, computed, inject, signal } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { ActivatedRoute, Router } from '@angular/router';
import { OqIconComponent } from '../../shared/icons/oq-icon.component';
import { GalpaoService } from '../galpao.service';
import { Endereco, EstoqueProduto, ProdutoBusca, SaldoEndereco, VisaoPlanta } from '../galpao.model';
import { MovimentoModalComponent, TipoModal } from '../movimento-modal/movimento-modal.component';
import { mensagemErro } from '../plantas-lista/plantas-lista.component';

/** Ação escolhida nos botões grandes: primeiro o endereço, depois (transferir/retirar) o produto do endereço. */
type Acao = TipoModal;

const ROTULO_ACAO: Record<Acao, string> = {
  ENDERECAR: 'Endereçar',
  TRANSFERIR: 'Transferir',
  RETIRAR: 'Retirar',
  CONTAR: 'Contar',
};

const PERGUNTA_ENDERECO: Record<Acao, string> = {
  ENDERECAR: 'Em qual endereço você vai guardar?',
  TRANSFERIR: 'De qual endereço sai a mercadoria?',
  RETIRAR: 'De qual endereço você vai retirar?',
  CONTAR: 'Qual endereço você vai contar?',
};

/** Resultado da busca "Onde está?". */
type Resultado =
  | { tipo: 'produto'; estoque: EstoqueProduto }
  | { tipo: 'endereco'; endereco: Endereco }
  | { tipo: 'pedido'; nunota: number; numnota: number | null; itens: { codprod: number; produto: string; enderecos: { endereco: Endereco; saldo: SaldoEndereco }[] }[] }
  | { tipo: 'produtos'; lista: ProdutoBusca[] };

/**
 * Operação da Planta do Galpão (operador no galpão, tablet): uma pergunta — "Onde está?" — e quatro ações
 * (endereçar, transferir, retirar, contar), um passo por vez e com botões grandes. A movimentação em si é o
 * mesmo modal da visão (mesmas validações do backend).
 */
@Component({
  selector: 'app-planta-operacao',
  standalone: true,
  imports: [CommonModule, FormsModule, OqIconComponent, MovimentoModalComponent],
  templateUrl: './planta-operacao.component.html',
  styleUrl: './planta-operacao.component.scss',
})
export class PlantaOperacaoComponent implements OnInit {
  private readonly service = inject(GalpaoService);
  private readonly route = inject(ActivatedRoute);
  private readonly router = inject(Router);

  readonly plantaId = this.route.snapshot.paramMap.get('id') ?? '';
  readonly rotuloAcao = ROTULO_ACAO;
  readonly perguntaEndereco = PERGUNTA_ENDERECO;
  readonly acoes: Acao[] = ['ENDERECAR', 'TRANSFERIR', 'RETIRAR', 'CONTAR'];

  readonly visao = signal<VisaoPlanta | null>(null);
  readonly carregando = signal(true);
  readonly erro = signal<string | null>(null);
  readonly feedback = signal<{ texto: string; erro: boolean } | null>(null);
  /** Movimentação: só ADMINISTRADOR por enquanto (o backend confere de novo). */
  readonly podeMovimentar = signal(false);

  // Busca "Onde está?"
  termo = '';
  readonly buscando = signal(false);
  readonly resultado = signal<Resultado | null>(null);
  readonly semResultado = signal<string | null>(null);

  // Passo a passo de uma ação
  readonly acao = signal<Acao | null>(null);
  readonly enderecoEscolhido = signal<Endereco | null>(null);
  codigoEndereco = '';

  readonly modal = signal<{ tipo: TipoModal; endereco: Endereco; saldo: SaldoEndereco | null } | null>(null);

  readonly enderecos = computed(() => this.visao()?.enderecos ?? []);
  readonly porId = computed(() => new Map(this.enderecos().map((e) => [e.id, e])));
  readonly contagensPendentes = computed(() => this.enderecos().reduce((s, e) => s + e.contagensPendentes, 0));

  /** Endereços que batem com o que foi digitado no passo "qual endereço?". */
  readonly sugestoesEndereco = computed(() => {
    const t = this.codigoDigitado().trim().toUpperCase();
    const a = this.acao();
    return this.enderecos()
      .filter((e) => !e.bloqueado || a === 'CONTAR')
      .filter((e) => (a === 'TRANSFERIR' || a === 'RETIRAR' ? e.quantidade > 0 : true))
      .filter((e) => !t || e.codigo.includes(t))
      .slice(0, 12);
  });
  private readonly codigoDigitado = signal('');

  ngOnInit(): void {
    this.carregar();
    this.service.disponivel().subscribe({ next: (r) => this.podeMovimentar.set(r.podeEditar), error: () => undefined });
  }

  carregar(): void {
    this.carregando.set(true);
    this.service.visao(this.plantaId).subscribe({
      next: (v) => {
        this.visao.set(v);
        this.erro.set(null);
        this.carregando.set(false);
        // Endereço aberto no passo a passo é relido (saldos novos depois de um movimento).
        const e = this.enderecoEscolhido();
        if (e) this.enderecoEscolhido.set(v.enderecos.find((x) => x.id === e.id) ?? null);
        const r = this.resultado();
        if (r?.tipo === 'endereco') this.resultado.set({ tipo: 'endereco', endereco: v.enderecos.find((x) => x.id === r.endereco.id) ?? r.endereco });
      },
      error: (e) => {
        this.erro.set(mensagemErro(e, 'Não foi possível carregar a planta.'));
        this.carregando.set(false);
      },
    });
  }

  abrirPainel(codigo?: string): void {
    this.router.navigate(['/galpao', this.plantaId], codigo ? { queryParams: { endereco: codigo } } : {});
  }

  // ─── "Onde está?" ─────────────────────────────────────────────────────────

  buscar(): void {
    const t = this.termo.trim();
    this.semResultado.set(null);
    this.resultado.set(null);
    this.cancelarAcao();
    if (!t) return;
    // 1. Código de endereço exato.
    const end = this.enderecos().find((e) => e.codigo === t.toUpperCase());
    if (end) {
      this.resultado.set({ tipo: 'endereco', endereco: end });
      return;
    }
    this.buscando.set(true);
    // 2. Número: produto pelo código; senão pedido/nota.
    if (/^\d+$/.test(t)) {
      const n = Number(t);
      this.service.estoqueProduto(this.plantaId, n).subscribe({
        next: (est) => {
          if (est.controles.length || est.enderecos.length) {
            this.buscando.set(false);
            this.resultado.set({ tipo: 'produto', estoque: est });
          } else {
            this.buscarPedido(n);
          }
        },
        error: () => this.buscarPedido(n),
      });
      return;
    }
    // 3. Texto: produtos pelo nome.
    this.service.buscarProdutos(this.plantaId, t).subscribe({
      next: (lista) => {
        this.buscando.set(false);
        if (lista.length === 1) this.abrirProduto(lista[0].codprod);
        else if (lista.length) this.resultado.set({ tipo: 'produtos', lista });
        else this.semResultado.set(`Nada encontrado para "${t}".`);
      },
      error: (e) => {
        this.buscando.set(false);
        this.semResultado.set(mensagemErro(e, 'Não foi possível buscar.'));
      },
    });
  }

  private buscarPedido(numero: number): void {
    this.service.documento(this.plantaId, numero).subscribe({
      next: (d) => {
        this.buscando.set(false);
        const nomes = new Map<number, string>(Object.entries(d.nomes ?? {}).map(([k, v]) => [Number(k), v]));
        const itens = d.codprods.map((cod) => {
          const enderecos = this.enderecos()
            .flatMap((e) => e.saldos.filter((s) => s.codprod === cod && s.quantidade > 0).map((s) => ({ endereco: e, saldo: s })))
            .sort((a, b) => a.endereco.codigo.localeCompare(b.endereco.codigo, 'pt-BR', { numeric: true }));
          if (!nomes.has(cod)) enderecos.forEach((x) => nomes.set(cod, x.saldo.produto));
          return { codprod: cod, produto: nomes.get(cod) ?? `Produto ${cod}`, enderecos };
        });
        // Ordem de coleta: pelo primeiro endereço de cada produto; sem endereço vai pro fim.
        itens.sort((a, b) => (a.enderecos[0]?.endereco.codigo ?? '~').localeCompare(b.enderecos[0]?.endereco.codigo ?? '~', 'pt-BR', { numeric: true }));
        this.resultado.set({ tipo: 'pedido', nunota: d.nunota, numnota: d.numnota, itens });
      },
      error: () => {
        this.buscando.set(false);
        this.semResultado.set(`Nenhum produto, endereço ou pedido com o número ${numero}.`);
      },
    });
  }

  abrirProduto(codprod: number): void {
    this.buscando.set(true);
    this.service.estoqueProduto(this.plantaId, codprod).subscribe({
      next: (est) => {
        this.buscando.set(false);
        this.resultado.set({ tipo: 'produto', estoque: est });
      },
      error: (e) => {
        this.buscando.set(false);
        this.semResultado.set(mensagemErro(e, 'Não foi possível ler o estoque do produto.'));
      },
    });
  }

  enderecoPorId(id: string): Endereco | undefined {
    return this.porId().get(id);
  }

  saldoDe(e: Endereco, codprod: number, controle: string): SaldoEndereco | null {
    return e.saldos.find((s) => s.codprod === codprod && s.controle === controle) ?? null;
  }

  semEnderecoTotal(est: EstoqueProduto): number {
    return est.controles.reduce((s, c) => s + c.semEndereco, 0);
  }

  // ─── Ações (passo a passo) ────────────────────────────────────────────────

  iniciar(a: Acao): void {
    this.acao.set(a);
    this.enderecoEscolhido.set(null);
    this.codigoEndereco = '';
    this.codigoDigitado.set('');
    this.resultado.set(null);
    this.semResultado.set(null);
    this.feedback.set(null);
  }

  cancelarAcao(): void {
    this.acao.set(null);
    this.enderecoEscolhido.set(null);
    this.codigoEndereco = '';
    this.codigoDigitado.set('');
  }

  aoDigitarEndereco(v: string): void {
    this.codigoEndereco = v;
    this.codigoDigitado.set(v);
  }

  /** Enter no campo de endereço: código exato escolhe direto. */
  confirmarEnderecoDigitado(): void {
    const e = this.enderecos().find((x) => x.codigo === this.codigoEndereco.trim().toUpperCase());
    if (e) this.escolherEndereco(e);
  }

  escolherEndereco(e: Endereco): void {
    const a = this.acao();
    if (!a) return;
    // Endereçar e contar abrem direto; transferir/retirar pedem o produto do endereço.
    if (a === 'ENDERECAR' || a === 'CONTAR' || e.saldos.length === 0) {
      this.abrirModal(a, e, null);
      return;
    }
    if (e.saldos.length === 1) {
      this.abrirModal(a, e, e.saldos[0]);
      return;
    }
    this.enderecoEscolhido.set(e);
  }

  abrirModal(tipo: TipoModal, endereco: Endereco, saldo: SaldoEndereco | null): void {
    if (!this.podeMovimentar()) {
      this.feedback.set({ texto: 'Por enquanto só o ADMINISTRADOR movimenta na planta.', erro: true });
      return;
    }
    if (endereco.bloqueado && tipo !== 'CONTAR') {
      this.feedback.set({ texto: `O endereço ${endereco.codigo} está bloqueado.`, erro: true });
      return;
    }
    this.modal.set({ tipo, endereco, saldo });
  }

  aoConcluirMovimento(texto: string): void {
    this.modal.set(null);
    this.cancelarAcao();
    this.feedback.set({ texto, erro: false });
    this.carregar();
    // Resultado de produto/pedido na tela é relido para mostrar os saldos novos.
    const r = this.resultado();
    if (r?.tipo === 'produto') this.abrirProduto(r.estoque.codprod);
  }

  num(v: number, casas = 3): string {
    return v.toLocaleString('pt-BR', { maximumFractionDigits: casas });
  }
}
