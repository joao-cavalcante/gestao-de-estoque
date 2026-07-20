import { Component, EventEmitter, Input, Output } from '@angular/core';

@Component({
  selector: 'oq-transferencia-footer',
  standalone: true,
  templateUrl: './oq-transferencia-footer.component.html',
  styleUrl: './oq-transferencia-footer.component.scss',
})
export class OqTransferenciaFooterComponent {
  @Input() totalItens = 0;
  @Input() totalUnidades = 0;
  @Input() podeRevisar = false;

  @Output() revisar = new EventEmitter<void>();
}
