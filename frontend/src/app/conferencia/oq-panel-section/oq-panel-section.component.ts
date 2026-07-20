import { Component, Input } from '@angular/core';
import { OqIconComponent, OqIconName } from '../../shared/icons/oq-icon.component';

@Component({
  selector: 'oq-panel-section',
  standalone: true,
  // Mesma razão do host style em oq-pending-list/oq-conferred-list: sem
  // isto a tag host não estica, e o <section> interno encolhe pro
  // conteúdo em vez de preencher a célula do grid pai.
  host: { style: 'display: flex; flex-direction: column; min-height: 0; height: 100%;' },
  imports: [OqIconComponent],
  templateUrl: './oq-panel-section.component.html',
  styleUrl: './oq-panel-section.component.scss',
})
export class OqPanelSectionComponent {
  @Input() icon: OqIconName | null = null;
  @Input({ required: true }) title = '';
  @Input({ required: true }) count = 0;
  @Input() countTone: 'default' | 'muted' = 'default';

  get contagemFormatada(): string {
    return String(this.count).padStart(3, '0');
  }
}
