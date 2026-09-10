import { Component, EventEmitter, Input, OnInit, Output, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { LiberacaoCorteService } from '../liberacao-corte.service';
import { LiberacaoPendente } from '../liberacao-corte.model';

/**
 * Modal de liberação de corte em 2 etapas — reutilizado pela tela
 * /liberacao-corte e pelo fluxo inline no fim da conferência.
 * Espelha liberacao-corte-modal.component.ts do fila-de-conferencia.
 */
@Component({
  selector: 'oq-liberacao-corte-modal',
  standalone: true,
  imports: [FormsModule],
  templateUrl: './oq-liberacao-corte-modal.component.html',
  styleUrl: './oq-liberacao-corte-modal.component.scss',
})
export class OqLiberacaoCorteModalComponent implements OnInit {
  @Input({ required: true }) nuconf!: number;
  /** Texto livre pro cabeçalho (ex.: "Pedido 12345 — CLIENTE X"). */
  @Input() rotulo = '';
  /** true = pelo menos um item foi liberado/negado (a lista de origem deve recarregar). */
  @Output() fechado = new EventEmitter<boolean>();

  constructor(private readonly service: LiberacaoCorteService) {}

  readonly etapa = signal<'AUTENTICACAO' | 'REVISAO'>('AUTENTICACAO');

  usuario = '';
  senha = '';
  mostrarSenha = false;
  autenticando = signal(false);
  erroAutenticacao = signal<string | null>(null);

  readonly pendentes = signal<LiberacaoPendente[]>([]);
  readonly selecionadas = signal<ReadonlySet<number>>(new Set());
  obs = '';
  carregandoPendentes = signal(false);
  processando = signal(false);
  erroAcao = signal<string | null>(null);
  mensagem = signal<string | null>(null);
  private houveAcao = false;

  ngOnInit(): void {
    // nada — a autenticação dispara o carregamento
  }

  get podeAvancar(): boolean {
    return !!this.usuario && !!this.senha && !this.autenticando();
  }

  avancar(): void {
    if (!this.podeAvancar) return;
    this.autenticando.set(true);
    this.erroAutenticacao.set(null);
    this.service.validarLiberador({ usuario: this.usuario, senha: this.senha }).subscribe({
      next: () => {
        this.autenticando.set(false);
        this.etapa.set('REVISAO');
        this.carregarPendentes();
      },
      error: (err) => {
        this.autenticando.set(false);
        this.erroAutenticacao.set(err?.error?.erro ?? 'Usuário ou senha inválidos.');
      },
    });
  }

  private carregarPendentes(): void {
    this.carregandoPendentes.set(true);
    this.service.pendentes(this.nuconf).subscribe({
      next: (itens) => {
        this.pendentes.set(itens);
        this.carregandoPendentes.set(false);
        if (itens.length === 0) {
          // Conferência "presa": sem itens pra liberar. Fecha sinalizando ação —
          // a tela revalida no backend e o card some.
          this.houveAcao = true;
          this.mensagem.set('Nenhum item pendente — a conferência já foi liberada. Atualizando…');
          setTimeout(() => this.fechar(), 1500);
        }
      },
      error: (err) => {
        this.carregandoPendentes.set(false);
        this.erroAcao.set(err?.error?.erro ?? 'Falha ao carregar os itens pendentes.');
      },
    });
  }

  voltar(): void {
    this.etapa.set('AUTENTICACAO');
    this.selecionadas.set(new Set());
    this.obs = '';
    this.erroAcao.set(null);
    this.mensagem.set(null);
  }

  itemSelecionado(seq: number): boolean {
    return this.selecionadas().has(seq);
  }

  alternarItem(seq: number): void {
    const proximo = new Set(this.selecionadas());
    if (proximo.has(seq)) proximo.delete(seq);
    else proximo.add(seq);
    this.selecionadas.set(proximo);
  }

  selecionarTodos(): void {
    this.selecionadas.set(new Set(this.pendentes().map((p) => p.sequencia)));
  }

  limparSelecao(): void {
    this.selecionadas.set(new Set());
  }

  liberarOuNegar(liberar: 'S' | 'N'): void {
    const sequencias = [...this.selecionadas()];
    if (!sequencias.length || this.processando()) return;
    this.processando.set(true);
    this.erroAcao.set(null);
    this.service
      .liberar({ nuconf: this.nuconf, usuario: this.usuario, senha: this.senha, liberar, sequencias, obs: this.obs || undefined })
      .subscribe({
        next: (res) => {
          this.processando.set(false);
          this.houveAcao = true;
          this.mensagem.set(`${res.itensProcessados} item(ns) ${liberar === 'S' ? 'liberado(s)' : 'negado(s)'}.`);
          this.pendentes.update((arr) => arr.filter((p) => !sequencias.includes(p.sequencia)));
          this.limparSelecao();
          if (this.pendentes().length === 0) setTimeout(() => this.fechar(), 1400);
        },
        error: (err) => {
          this.processando.set(false);
          this.erroAcao.set(err?.error?.erro ?? 'Falha ao processar a liberação.');
        },
      });
  }

  fechar(): void {
    this.fechado.emit(this.houveAcao);
  }
}
