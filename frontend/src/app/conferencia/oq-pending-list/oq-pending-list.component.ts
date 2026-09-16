import { Component, EventEmitter, Input, Output } from '@angular/core';
import { OqPanelSectionComponent } from '../oq-panel-section/oq-panel-section.component';
import { OqIconComponent } from '../../shared/icons/oq-icon.component';
import { ConferenciaItem } from '../conferencia.model';

@Component({
  selector: 'oq-pending-list',
  standalone: true,
  // Sem isto, o elemento host (tag Angular, sem display por padrão) não
  // repassa a altura esticada pelo grid pro <oq-panel-section> de dentro —
  // o painel encolhe pro tamanho do conteúdo em vez de preencher a célula.
  host: { style: 'display: flex; flex-direction: column; min-height: 0; height: 100%;' },
  imports: [OqPanelSectionComponent, OqIconComponent],
  templateUrl: './oq-pending-list.component.html',
  styleUrl: './oq-pending-list.component.scss',
})
export class OqPendingListComponent {
  @Input({ required: true }) items: ConferenciaItem[] = [];
  /** CCO.EXIBIRQTD — false esconde a quantidade negociada de cada item. */
  @Input() exibirQtd = true;
  /** Clique no item — identifica o produto sem bipar/digitar o código (elimina o Tab no mobile). */
  @Output() selecionar = new EventEmitter<ConferenciaItem>();

  /** Mesmo formato da conferência antiga: decimal pt-BR com 3 casas (1.3-3). */
  formatarQtd(n: number): string {
    return (n ?? 0).toLocaleString('pt-BR', { minimumFractionDigits: 3, maximumFractionDigits: 3 });
  }

  /** true quando a unidade do pedido (comercial) difere da unidade base do produto. */
  temComercial(item: ConferenciaItem): boolean {
    return !!item.unidadeComercial && item.unidadeComercial !== item.unidadePadrao;
  }

  /**
   * Item NÃO pesável negociado noutra unidade — mostra a qtd DO PEDIDO como
   * número principal. Pesável NÃO entra: o operador pesa e registra em KG
   * (unidade padrão); o Sankhya corrige pra unidade comercial na nota.
   */
  mostraComercial(item: ConferenciaItem): boolean {
    return !item.usaConfPeso && this.temComercial(item) && item.quantidadeComercial != null;
  }

  /** Quanto falta bipar (pedido - já conferido), nunca negativo — mesma unidade base do item. */
  private restanteBase(item: ConferenciaItem): number {
    return Math.max(0, item.expected - item.scanned);
  }

  /** true = já tem algo bipado neste item, mas ainda não bateu o total (item-row--parcial). */
  ehParcial(item: ConferenciaItem): boolean {
    return item.scanned > 0 && item.scanned < item.expected;
  }

  qtdPrincipal(item: ConferenciaItem): number {
    if (!this.mostraComercial(item)) return this.restanteBase(item);
    // Comercial: restante proporcional (mesma conversão de padraoParaComercial, ver oq-conferred-list).
    return item.expected > 0 ? (this.restanteBase(item) * item.quantidadeComercial!) / item.expected : 0;
  }

  unidadePrincipal(item: ConferenciaItem): string {
    return (this.mostraComercial(item) ? item.unidadeComercial : item.unidadePadrao) ?? '';
  }
}
