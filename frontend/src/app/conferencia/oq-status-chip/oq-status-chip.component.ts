import { Component, Input } from '@angular/core';
import { ChipTone } from '../conferencia.model';

@Component({
  selector: 'oq-status-chip',
  standalone: true,
  template: `
    <span class="oq-status-chip" [class]="'oq-status-chip--' + tone">
      <ng-content select="[icon]" />
      <ng-content />
    </span>
  `,
  styleUrl: './oq-status-chip.component.scss',
})
export class OqStatusChipComponent {
  @Input() tone: ChipTone = 'neutral';
}
