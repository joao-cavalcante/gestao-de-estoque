import { Component, Input } from '@angular/core';

export type OqSkeletonVariant = 'line' | 'admin-row' | 'task-card' | 'detail-block' | 'table-row';

/**
 * Skeleton de carregamento — reaproveita a classe global `.oq-skeleton`
 * (styles.scss) e monta a "silhueta" de cada estrutura repetida no app
 * (linha de lista admin, card da Fila de Tarefas, bloco de detalhe, linha
 * de tabela), pra evitar reimplementar o shimmer em cada tela e pra que o
 * layout final não "pule" quando os dados chegam (dimensões próximas ao
 * conteúdo real). `:host { display: contents }` faz os itens repetidos
 * caírem direto no grid/lista do pai, como se não houvesse wrapper.
 */
@Component({
  selector: 'oq-skeleton',
  standalone: true,
  host: { style: 'display: contents;', 'aria-hidden': 'true' },
  templateUrl: './oq-skeleton.component.html',
  styleUrl: './oq-skeleton.component.scss',
})
export class OqSkeletonComponent {
  @Input() variant: OqSkeletonVariant = 'line';
  @Input() count = 1;
  /** Só pro variant 'line'. */
  @Input() width = '100%';
  @Input() height = '14px';

  get items(): number[] {
    return Array.from({ length: Math.max(1, this.count) }, (_, i) => i);
  }
}
