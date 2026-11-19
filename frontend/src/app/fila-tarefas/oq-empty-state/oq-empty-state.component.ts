import { Component, EventEmitter, Input, Output } from '@angular/core';

@Component({
  selector: 'oq-empty-state',
  standalone: true,
  templateUrl: './oq-empty-state.component.html',
  styleUrl: './oq-empty-state.component.scss',
})
export class OqEmptyStateComponent {
  @Input() titulo = 'Nenhum pedido na fila';
  @Input() mensagem = '';
  /** Mostra "Limpar filtros" (o pai diz se há filtro ativo). */
  @Input() podeLimpar = false;
  @Output() limpar = new EventEmitter<void>();
}
