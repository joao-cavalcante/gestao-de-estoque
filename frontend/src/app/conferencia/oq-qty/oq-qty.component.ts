import { Component, Input, computed, signal } from '@angular/core';
import { QtyTone } from '../conferencia.model';

@Component({
  selector: 'oq-qty',
  standalone: true,
  template: `
    <span
      class="oq-qty"
      [class]="'oq-qty--' + tone"
      [style.min-width.rem]="width * 0.65"
    >{{ textoFormatado() }}</span>
  `,
  styleUrl: './oq-qty.component.scss',
})
export class OqQtyComponent {
  @Input({ required: true }) set value(v: number) {
    this._value.set(v);
  }
  @Input() tone: QtyTone = 'default';
  @Input() width = 3;

  private readonly _value = signal(0);
  readonly textoFormatado = computed(() => String(this._value()).padStart(this.width, '0'));
}
