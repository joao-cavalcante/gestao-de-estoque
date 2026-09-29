import { Component, OnInit, computed, inject, signal } from '@angular/core';
import { OqIconComponent } from '../shared/icons/oq-icon.component';
import { OqPanelSectionComponent } from '../conferencia/oq-panel-section/oq-panel-section.component';
import { OqInlineAlertComponent } from '../shared/oq-inline-alert/oq-inline-alert.component';
import { OqSkeletonComponent } from '../shared/oq-skeleton/oq-skeleton.component';
import { TiposOperacaoService } from './tipos-operacao.service';
import { FiltroTipmov, TIPMOV_COMPRAS, TIPMOV_VENDAS, TipoOperacao, rotuloTipmov } from './tipos-operacao.model';

/**
 * Espelho local da TGFTOP (V16), filtrado no backend pra só trazer os TOP com
 * Configuração de Conferência vinculada. Tela própria, não mais uma aba dentro de
 * Config Conferência — decisão revertida a pedido do usuário.
 *
 * V49: filtro por TIPMOV (Compras C/O · Vendas V/P) e, por TOP, "Usar conferência
 * por etapa" — conferência de ENTRADA (compra) não usa etapas Secos/Refrigerado/
 * Congelado. Desligado = a nota desse TOP abre em conferência única.
 */
@Component({
  selector: 'app-tipos-operacao',
  standalone: true,
  imports: [OqIconComponent, OqPanelSectionComponent, OqInlineAlertComponent, OqSkeletonComponent],
  templateUrl: './tipos-operacao.component.html',
  styles: [
    `
      .top-toolbar {
        justify-content: space-between;
        gap: 10px;
        flex-wrap: wrap;
      }
      .top-tipmov {
        display: inline-block;
        margin-left: 6px;
        padding: 0 6px;
        border: 1px solid var(--oq-border);
        border-radius: 999px;
        font-family: var(--oq-font-display);
        font-size: 10px;
        font-weight: 700;
        text-transform: uppercase;
        letter-spacing: 0.04em;
        color: var(--oq-text-secondary);
        background: var(--oq-surface-2);
      }
      .top-etapa {
        flex: none;
        display: inline-flex;
        align-items: center;
        gap: 8px;
        font-family: var(--oq-font-display);
        font-size: 11px;
        font-weight: 600;
        color: var(--oq-text-primary);
        cursor: pointer;
        user-select: none;
      }
      .top-etapa input {
        width: 16px;
        height: 16px;
        margin: 0;
        accent-color: var(--oq-brand);
        cursor: pointer;
      }
      .top-etapa--off {
        color: var(--oq-text-secondary);
      }
    `,
  ],
})
export class TiposOperacaoComponent implements OnInit {
  private readonly service = inject(TiposOperacaoService);

  tops = signal<TipoOperacao[]>([]);
  carregando = signal(true);
  sincronizando = signal(false);
  erro = signal<string | null>(null);
  /** codtop em gravação — evita duplo clique na mesma linha. */
  salvando = signal<number | null>(null);

  readonly filtro = signal<FiltroTipmov>('todos');
  readonly filtros: { valor: FiltroTipmov; label: string }[] = [
    { valor: 'todos', label: 'Todos' },
    { valor: 'compras', label: 'Compras (C/O)' },
    { valor: 'vendas', label: 'Vendas (V/P)' },
  ];

  readonly topsFiltrados = computed(() => {
    const f = this.filtro();
    if (f === 'todos') return this.tops();
    const aceitos = f === 'compras' ? TIPMOV_COMPRAS : TIPMOV_VENDAS;
    return this.tops().filter((t) => !!t.tipmov && aceitos.includes(t.tipmov));
  });

  readonly rotuloTipmov = rotuloTipmov;

  ngOnInit(): void {
    this.carregar();
  }

  private carregar(): void {
    this.carregando.set(true);
    this.service.listar().subscribe({
      next: (itens) => {
        this.tops.set(itens);
        this.carregando.set(false);
      },
      error: () => this.carregando.set(false),
    });
  }

  sincronizarAgora(): void {
    if (this.sincronizando()) return;
    this.sincronizando.set(true);
    this.erro.set(null);
    this.service.sincronizar().subscribe({
      next: () => {
        this.sincronizando.set(false);
        this.carregar();
      },
      error: (err) => {
        this.sincronizando.set(false);
        this.erro.set(err.error?.erro ?? 'Não foi possível sincronizar com o Sankhya');
      },
    });
  }

  /** Otimista: marca na hora, volta se a gravação falhar. */
  alternarConferenciaPorEtapa(top: TipoOperacao, valor: boolean): void {
    if (this.salvando() === top.codtop) return;
    this.salvando.set(top.codtop);
    this.erro.set(null);
    this.atualizarLocal(top.codtop, valor);
    this.service.definirConferenciaPorEtapa(top.codtop, valor).subscribe({
      next: () => this.salvando.set(null),
      error: (err) => {
        this.atualizarLocal(top.codtop, !valor);
        this.salvando.set(null);
        this.erro.set(err.error?.erro ?? `Não foi possível salvar o TOP ${top.codtop}`);
      },
    });
  }

  private atualizarLocal(codtop: number, valor: boolean): void {
    this.tops.update((lista) => lista.map((t) => (t.codtop === codtop ? { ...t, conferenciaPorEtapa: valor } : t)));
  }
}
