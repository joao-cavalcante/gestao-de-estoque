import { Component, Input } from '@angular/core';

@Component({
  selector: 'oq-seq-badge',
  standalone: true,
  template: `<span class="oq-seq-badge" [class.oq-seq-badge--critical]="!muted">{{ texto }}</span>`,
  styleUrl: './oq-seq-badge.component.scss',
})
export class OqSeqBadgeComponent {
  @Input({ required: true }) n = 0;
  @Input() muted = true;

  get texto(): string {
    return String(this.n).padStart(2, '0');
  }
}
