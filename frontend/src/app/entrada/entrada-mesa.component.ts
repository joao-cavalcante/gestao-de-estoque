import { Component, OnDestroy, OnInit, computed, inject, signal } from '@angular/core';
import { DatePipe } from '@angular/common';
import { ActivatedRoute, Router, RouterLink } from '@angular/router';
import { Subscription } from 'rxjs';
import { OqIconComponent } from '../shared/icons/oq-icon.component';
import { OqSkeletonComponent } from '../shared/oq-skeleton/oq-skeleton.component';
import { SyncTickService } from '../shared/app-header/sync-tick.service';
import {
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
 * Mesa de liberação do recebimento (19/11/2026): o que a responsável precisa ver antes de liberar a compra —
 * item a item (nota × conferido), ajustes de peso e as notas que a conferência gerou (devolução e complemento).
 * Só leitura: "Aprovar" fica desabilitado até o addon (etapa 1); a confirmação continua no Sankhya e a mesa passa
 * para "Liberada" no próximo ciclo de sincronização.
 */
@Component({
  selector: 'app-entrada-mesa',
  standalone: true,
  imports: [DatePipe, RouterLink, OqIconComponent, OqSkeletonComponent],
  templateUrl: './entrada-mesa.component.html',
  styleUrl: './entrada.scss',
})
export class EntradaMesaComponent implements OnInit, OnDestroy {
  private readonly service = inject(EntradaService);
  private readonly route = inject(ActivatedRoute);
  private readonly router = inject(Router);
  private readonly syncTick = inject(SyncTickService);
  private syncSub?: Subscription;

  readonly rotulo = ROTULO_SITUACAO;
  readonly icone = ICONE_SITUACAO;
  readonly rotuloTipo = ROTULO_TIPO;
  readonly ddmm = ddmm;
  readonly pesoTexto = pesoTexto;
  readonly qtd = qtdTexto;
  readonly rotuloAjuste = rotuloAjuste;

  readonly nunota = Number(this.route.snapshot.paramMap.get('nunota'));
  readonly mesa = signal<MesaLiberacao | null>(null);
  readonly carregando = signal(true);
  readonly erro = signal<string | null>(null);
  readonly soDivergencias = signal(false);

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
    if (!this.nunota) {
      this.erro.set('Nº único inválido.');
      this.carregando.set(false);
      return;
    }
    this.carregar();
    this.syncSub = this.syncTick.onTick.subscribe(() => this.carregar());
  }

  ngOnDestroy(): void {
    this.syncSub?.unsubscribe();
  }

  carregar(): void {
    this.service.mesa(this.nunota).subscribe({
      next: (m) => {
        this.mesa.set(m);
        this.erro.set(null);
        this.carregando.set(false);
        this.syncTick.registrarSucesso();
      },
      error: (e) => {
        if (!this.mesa()) this.erro.set(e?.error?.erro ?? 'Não foi possível abrir a mesa.');
        this.carregando.set(false);
      },
    });
  }

  voltar(): void {
    this.router.navigate(['/entrada']);
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
