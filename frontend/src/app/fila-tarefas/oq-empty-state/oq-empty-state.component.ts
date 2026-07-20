import { Component, Input } from '@angular/core';

@Component({
  selector: 'oq-empty-state',
  standalone: true,
  templateUrl: './oq-empty-state.component.html',
  styleUrl: './oq-empty-state.component.scss',
})
export class OqEmptyStateComponent {
  @Input() titulo = 'Nenhuma tarefa neste filtro';
  @Input() mensagem = 'Ajuste os filtros ou aguarde a próxima sincronização automática.';
}
