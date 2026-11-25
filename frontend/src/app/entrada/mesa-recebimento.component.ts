import { Component, EventEmitter, Input, OnChanges, OnDestroy, OnInit, Output, SimpleChanges, computed, inject, signal } from '@angular/core';
import { DatePipe } from '@angular/common';
import { RouterLink } from '@angular/router';
import { Subscription } from 'rxjs';
import { OqIconComponent } from '../shared/icons/oq-icon.component';
import { OqSkeletonComponent } from '../shared/oq-skeleton/oq-skeleton.component';
import { SyncTickService } from '../shared/app-header/sync-tick.service';
import {
  AprovacaoRecebimento,
  EntradaService,
  ICONE_SITUACAO,
  ItemMesa,
  MesaLiberacao,
  ROTULO_SITUACAO,
  TipoItemMesa,
  ddmm,
  pesoTexto,
  qtdTexto,
  rotuloAjuste,
} from './entrada.service';

const ROTULO_TIPO: Record<TipoItemMesa, string> = {
  ok: 'Confere',
  falta: 'Falta',
  sobra: 'Sobra',
  ajuste: 'Ajuste de peso',
  fora_pedido: 'Fora da nota',
};

/**
 * Mesa do recebimento (19/11/2026): o que a responsável precisa ver antes de liberar a compra — item a item
 * (nota × conferido), ajustes de peso e as notas que a conferência gerou (devolução e complemento). Painel da direita
 * da tela Liberações (mesmo formato da Liberação de Corte). "Aprovar" (etapa 2, 23/11/2026): com o addon TOR ligado,
 * confirma a compra no Sankhya em nome do liberador que entrou na tela. "Devolver para recontagem" segue desabilitado
 * (o Sankhya não aceita recontagem de conferência finalizada — aguardando decisão).
 */
@Component({
  selector: 'oq-mesa-recebimento',
  standalone: true,
  imports: [DatePipe, RouterLink, OqIconComponent, OqSkeletonComponent],
  templateUrl: './mesa-recebimento.component.html',
  styleUrl: './entrada.scss',
})
export class MesaRecebimentoComponent implements OnInit, OnChanges, OnDestroy {
  private readonly service = inject(EntradaService);
  private readonly syncTick = inject(SyncTickService);
  private syncSub?: Subscription;
  private req?: Subscription;

  @Input({ required: true }) nunota!: number;
  /** Liberador que entrou na tela Liberações (só memória; a senha nunca é exibida). */
  @Input() usuario = '';
  @Input() senha = '';
  /** Celular/tablet em pé: volta para a lista. */
  @Output() readonly fechar = new EventEmitter<void>();

  readonly rotulo = ROTULO_SITUACAO;
  readonly icone = ICONE_SITUACAO;
  readonly rotuloTipo = ROTULO_TIPO;
  readonly ddmm = ddmm;
  readonly pesoTexto = pesoTexto;
  readonly qtd = qtdTexto;
  readonly rotuloAjuste = rotuloAjuste;

  readonly mesa = signal<MesaLiberacao | null>(null);
  readonly carregando = signal(true);
  readonly erro = signal<string | null>(null);
  readonly soDivergencias = signal(false);
  /** Pop-up do Aprovar: null = fechado. */
  readonly etapaAprovacao = signal<'confirmar' | 'aprovando' | 'ok' | 'liberacao' | 'erro' | null>(null);
  readonly aprovando = signal(false);
  readonly erroAprovacao = signal<string | null>(null);
  readonly resultadoAprovacao = signal<AprovacaoRecebimento | null>(null);

  readonly resumo = computed(() => {
    const itens = this.mesa()?.itens ?? [];
    const conta = (t: TipoItemMesa) => itens.filter((i) => i.tipo === t).length;
    return {
      total: itens.length,
      ok: conta('ok'),
      falta: conta('falta'),
      sobra: conta('sobra') + conta('fora_pedido'),
      ajuste: conta('ajuste'),
    };
  });

  readonly itensVisiveis = computed<ItemMesa[]>(() => {
    const itens = this.mesa()?.itens ?? [];
    return this.soDivergencias() ? itens.filter((i) => i.tipo !== 'ok') : itens;
  });

  ngOnInit(): void {
    this.syncSub = this.syncTick.onTick.subscribe(() => this.carregar());
  }

  ngOnChanges(changes: SimpleChanges): void {
    if (!changes['nunota']) return; // troca de liberador não recarrega a mesa
    this.mesa.set(null);
    this.soDivergencias.set(false);
    this.etapaAprovacao.set(null);
    this.erroAprovacao.set(null);
    this.resultadoAprovacao.set(null);
    this.carregar();
  }

  get podeAprovar(): boolean {
    const m = this.mesa();
    return !!m && m.aprovacaoDisponivel && m.recebimento.situacao === 'aguardando_liberacao'
      && !!this.usuario.trim() && !!this.senha && !this.aprovando();
  }

  // ─── Salvaguarda (25/11/2026): ajuste de peso que não saiu depois da confirmação ───
  readonly gerandoAjuste = signal(false);
  readonly erroAjuste = signal<string | null>(null);

  gerarAjuste(): void {
    if (this.gerandoAjuste()) return;
    const alvo = this.nunota;
    this.gerandoAjuste.set(true);
    this.erroAjuste.set(null);
    this.service.gerarAjuste(alvo).subscribe({
      next: (m) => {
        this.gerandoAjuste.set(false);
        if (alvo !== this.nunota) return;
        this.mesa.set(m);
        const erro = m.recebimento.ajustesPeso?.find((a) => a.status === 'erro');
        if (m.recebimento.ajustePendente && erro?.erro) this.erroAjuste.set(erro.erro);
      },
      error: (e) => {
        this.gerandoAjuste.set(false);
        this.erroAjuste.set(e?.error?.erro ?? 'Não foi possível gerar o ajuste agora.');
      },
    });
  }

  abrirAprovacao(): void {
    if (!this.podeAprovar) return;
    this.erroAprovacao.set(null);
    this.resultadoAprovacao.set(null);
    this.etapaAprovacao.set('confirmar');
  }

  fecharAprovacao(): void {
    if (this.aprovando()) return;
    this.etapaAprovacao.set(null);
  }

  aprovar(): void {
    if (!this.podeAprovar) return;
    const alvo = this.nunota;
    this.aprovando.set(true);
    this.etapaAprovacao.set('aprovando');
    this.erroAprovacao.set(null);
    this.resultadoAprovacao.set(null);
    this.service.aprovar(alvo, this.usuario.trim(), this.senha).subscribe({
      next: (r) => {
        if (alvo !== this.nunota) {
          this.aprovando.set(false);
          this.etapaAprovacao.set(null);
          return;
        }
        this.resultadoAprovacao.set(r);
        // Relê a mesa antes de mostrar o resultado: as notas de ajuste lançadas na mesma ação entram no pop-up.
        this.service.mesa(alvo).subscribe({
          next: (m) => {
            this.mesa.set(m);
            this.aprovando.set(false);
            this.etapaAprovacao.set(r.situacao === 'APROVADO' ? 'ok' : 'liberacao');
          },
          error: () => {
            this.aprovando.set(false);
            this.etapaAprovacao.set(r.situacao === 'APROVADO' ? 'ok' : 'liberacao');
          },
        });
      },
      error: (e) => {
        this.aprovando.set(false);
        this.erroAprovacao.set(e?.error?.erro ?? 'Não foi possível aprovar o recebimento.');
        this.etapaAprovacao.set('erro');
      },
    });
  }

  ngOnDestroy(): void {
    this.syncSub?.unsubscribe();
    this.req?.unsubscribe();
  }

  carregar(): void {
    if (!this.nunota) return;
    const alvo = this.nunota;
    this.carregando.set(true);
    this.req?.unsubscribe();
    this.req = this.service.mesa(alvo).subscribe({
      next: (m) => {
        if (alvo !== this.nunota) return;
        this.mesa.set(m);
        this.erro.set(null);
        this.carregando.set(false);
      },
      error: (e) => {
        if (!this.mesa()) this.erro.set(e?.error?.erro ?? 'Não foi possível abrir a mesa.');
        this.carregando.set(false);
      },
    });
  }

  diferencaTexto(i: ItemMesa): string {
    if (i.diferenca === 0) return '—';
    return `${i.diferenca > 0 ? '+' : '−'}${qtdTexto(Math.abs(i.diferenca))}`;
  }

  percentual(i: ItemMesa): string {
    if (!i.qtdNota || i.diferenca === 0) return '';
    const p = (Math.abs(i.diferenca) / i.qtdNota) * 100;
    return `${p.toLocaleString('pt-BR', { maximumFractionDigits: 1 })}%`;
  }
}
