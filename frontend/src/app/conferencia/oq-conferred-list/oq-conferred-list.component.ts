import { Component, EventEmitter, Input, Output } from '@angular/core';
import { OqPanelSectionComponent } from '../oq-panel-section/oq-panel-section.component';
import { OqIconComponent } from '../../shared/icons/oq-icon.component';
import { ConferenciaItem } from '../conferencia.model';

@Component({
  selector: 'oq-conferred-list',
  standalone: true,
  // Ver comentário equivalente em oq-pending-list.component.ts.
  host: { style: 'display: flex; flex-direction: column; min-height: 0; height: 100%;' },
  imports: [OqPanelSectionComponent, OqIconComponent],
  templateUrl: './oq-conferred-list.component.html',
  styleUrl: './oq-conferred-list.component.scss',
})
export class OqConferredListComponent {
  @Input({ required: true }) items: ConferenciaItem[] = [];
  /** CCO.EXIBIRQTDCONF — false esconde a quantidade conferida de cada item. */
  @Input() exibirQtd = true;

  /** Desfaz TUDO que foi conferido pra esse item (produto+controle) — corrige bipe errado. */
  @Output() devolver = new EventEmitter<ConferenciaItem>();

  isCritico(item: ConferenciaItem): boolean {
    return item.status === 'critical';
  }

  /** Item com progresso, mas que ainda não bateu o total — aparece aqui E em pendentes ao mesmo tempo. */
  isParcial(item: ConferenciaItem): boolean {
    return item.status === 'pending' && item.scanned > 0;
  }

  /** Divergência de PESO (item pesável pesando >5% menos que o esperado — a maior nunca diverge) — indicador visual próprio, diferente da divergência de qtd. */
  isDivergenciaPeso(item: ConferenciaItem): boolean {
    return !!item.divergenciaPeso;
  }

  /** Divergência "comum" de quantidade (excedente), sem ser a de peso. */
  isDivergenciaQtd(item: ConferenciaItem): boolean {
    return item.status === 'critical' && !item.divergenciaPeso;
  }

  /** Diferença bipado - esperado (na unidade base). */
  excedente(item: ConferenciaItem): number {
    return item.scanned - item.expected;
  }

  /** Item NÃO pesável negociado noutra unidade — exibe conferido na unidade DO PEDIDO (pesável fica em KG). */
  mostraComercial(item: ConferenciaItem): boolean {
    return (
      !item.usaConfPeso &&
      !!item.unidadeComercial &&
      item.unidadeComercial !== item.unidadePadrao &&
      item.quantidadeComercial != null &&
      item.expected > 0
    );
  }

  private paraComercial(item: ConferenciaItem, valorBase: number): number {
    return (valorBase * item.quantidadeComercial!) / item.expected;
  }

  qtdPrincipal(item: ConferenciaItem): number {
    return this.mostraComercial(item) ? this.paraComercial(item, item.scanned) : item.scanned;
  }

  /** Total negociado na mesma unidade de qtdPrincipal — pro "X de Y" do item parcial. */
  totalPrincipal(item: ConferenciaItem): number {
    return this.mostraComercial(item) ? item.quantidadeComercial! : item.expected;
  }

  excedentePrincipal(item: ConferenciaItem): number {
    const e = item.scanned - item.expected;
    return this.mostraComercial(item) ? this.paraComercial(item, e) : e;
  }

  unidadePrincipal(item: ConferenciaItem): string {
    return (this.mostraComercial(item) ? item.unidadeComercial : item.unidadePadrao) ?? '';
  }

  formatarQtd(n: number): string {
    return (n ?? 0).toLocaleString('pt-BR', { minimumFractionDigits: 3, maximumFractionDigits: 3 });
  }

  /** % de desvio do peso, em pt-BR (vírgula) — ex.: "7,1" ou "-7,1". */
  formatarPct(n: number | undefined): string {
    return (n ?? 0).toLocaleString('pt-BR', { minimumFractionDigits: 1, maximumFractionDigits: 1 });
  }

  /** Mesma formatação, mas sempre positiva — pro alerta vermelho (a menor), onde o sinal já está implícito no texto. */
  formatarPctAbs(n: number | undefined): string {
    return this.formatarPct(Math.abs(n ?? 0));
  }

  /** Item pesável já conferido, mas dentro da tolerância — mostra observação neutra (sem cor) com o desvio vs. pedido. */
  temObservacaoPeso(item: ConferenciaItem): boolean {
    return !!item.usaConfPeso && item.scanned > 0 && !item.divergenciaPeso;
  }
}
