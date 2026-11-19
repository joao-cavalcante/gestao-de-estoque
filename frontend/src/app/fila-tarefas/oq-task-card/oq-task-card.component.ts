import { Component, EventEmitter, Input, Output } from '@angular/core';
import { OqModalidadePinsComponent } from '../../shared/oq-modalidade-pins/oq-modalidade-pins.component';
import { CommonModule } from '@angular/common';
import { OqIconComponent } from '../../shared/icons/oq-icon.component';
import { FasePedido, SituacaoEntrega, Tarefa, entregaPedido, faseTarefa, idadePedido, mostraNumeroNota, partesMensagem, pesoTexto, textoAjustePeso, tomNota } from '../tarefa.model';
import { EtapaVisual, StatusVisual, etapasVisiveis, statusVisual } from '../tarefa-visual';
import { OqEtapaChipsComponent } from '../oq-etapa-chips/oq-etapa-chips.component';

@Component({
  selector: 'oq-task-card',
  standalone: true,
  // O item de grid de verdade é a tag <oq-task-card> (o host), não o
  // <article class="oq-card"> de dentro. Sem `min-width:0` o grid expande a
  // coluna pro min-content do nome do cliente (nowrap) em vez de respeitar o
  // 1fr; sem `display:flex` o <article> fica com a altura do conteúdo (não
  // estica com a linha do grid), e cards sem etapa ficam mais baixos que os
  // com etapa na mesma linha. Mesmo problema de propagação já visto na tela
  // de Conferência.
  host: { style: 'display: flex; min-width: 0;' },
  imports: [CommonModule, OqIconComponent, OqEtapaChipsComponent, OqModalidadePinsComponent],
  templateUrl: './oq-task-card.component.html',
  styleUrl: './oq-task-card.component.scss',
})
export class OqTaskCardComponent {
  @Input({ required: true }) tarefa!: Tarefa;
  /** Fila filtrada por OC: esconde OC e motorista/veículo, que já estão no cabeçalho da tela. */
  @Input() dentroDaOc = false;

  @Output() conferir = new EventEmitter<Tarefa | { tarefa: Tarefa; etapa: number }>();
  /** "✓ Carregado" — um toque, pedido inteiro. */
  @Output() carregado = new EventEmitter<Tarefa>();
  /** "Gerar nota" — abre o modal de faturamento (TOP por pedido). */
  @Output() faturar = new EventEmitter<Tarefa>();

  /** Compra: ajuste de peso que parou em erro — "Gerar ajuste de novo". */
  @Output() reprocessarAjuste = new EventEmitter<Tarefa>();

  readonly tomNota = tomNota;
  readonly partesMensagem = partesMensagem;
  readonly textoAjustePeso = textoAjustePeso;
  readonly pesoTexto = pesoTexto;
  readonly mostraNumeroNota = mostraNumeroNota;

  /** Obs. do pedido expandida (uma linha cortada por padrão). */
  obsAberta = false;

  /** "há 1h05" — recalcula a cada ciclo de detecção (o sync de 60 s mantém atualizado). */
  get idade(): string | null {
    return this.tarefa.pedidoEm ? idadePedido(this.tarefa.pedidoEm) : null;
  }

  horaTexto(d: Date): string {
    return d.toLocaleTimeString('pt-BR', { hour: '2-digit', minute: '2-digit' });
  }

  get pedidoEmTexto(): string {
    const d = this.tarefa.pedidoEm;
    return d ? d.toLocaleString('pt-BR', { day: '2-digit', month: '2-digit', year: 'numeric', hour: '2-digit', minute: '2-digit' }) : '';
  }

  /** Previsão de entrega × hoje — some no pedido já concluído (não é mais prazo de ninguém). */
  get entrega(): { texto: string; situacao: SituacaoEntrega } | null {
    if (!this.tarefa.previsaoEntrega || this.fase === 'pronto') return null;
    return entregaPedido(this.tarefa.previsaoEntrega);
  }

  get ajusteComErro(): boolean {
    return !!this.tarefa.ajustesPeso?.some((a) => a.status === 'erro');
  }

  /** Fase do fluxo (conferir → corte → carregar → nota → pronto): decide o único botão principal. */
  get fase(): FasePedido {
    return faseTarefa(this.tarefa);
  }

  /** Etapas da conferência por etapa (V29) — ver tarefa-visual.ts (mesmo cálculo da lista). */
  get etapasVisiveis(): EtapaVisual[] {
    return etapasVisiveis(this.tarefa);
  }

  conferirEtapa(tipo: number): void {
    this.conferir.emit({ tarefa: this.tarefa, etapa: tipo });
  }

  get statusVisual(): StatusVisual {
    return statusVisual(this.tarefa);
  }

  /**
   * Pedido sem ação na fila (carregado esperando o Fechar OC, ou concluído), sem alerta nem erro de nota:
   * card compacto — status com a próxima etapa escrita, números numa linha, sem rodapé (usuário, 17/11/2026).
   */
  get compacto(): boolean {
    const semErroNota = !this.tarefa.notaFiscal || tomNota(this.tarefa.notaFiscal) !== 'erro';
    return (this.fase === 'fechamento' || this.fase === 'pronto') && !this.tarefa.alerta && semErroNota && !this.ajusteComErro;
  }

  /** Pedido com OC já é entrega: o pino "Entrega" repetiria a OC. Express/Retira continuam. */
  get modalidadeExibida(): Tarefa {
    return this.tarefa.ordemCarga != null && this.tarefa.entrega ? { ...this.tarefa, entrega: false } : this.tarefa;
  }

  get classeCard(): Record<string, boolean> {
    return {
      'oq-card--critical': this.tarefa.alerta === 'critico',
      'oq-card--attention': this.tarefa.alerta === 'atencao',
      'oq-card--compacto': this.compacto,
    };
  }
}
