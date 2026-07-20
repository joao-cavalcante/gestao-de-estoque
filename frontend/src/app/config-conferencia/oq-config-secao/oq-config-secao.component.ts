import { Component, Input } from '@angular/core';

@Component({
  selector: 'oq-config-secao',
  standalone: true,
  template: `
    <section class="oq-config-secao">
      <header class="oq-config-secao__titulo">{{ titulo }}</header>
      <div class="oq-config-secao__body">
        <ng-content />
      </div>
    </section>
  `,
  styleUrl: './oq-config-secao.component.scss',
})
export class OqConfigSecaoComponent {
  @Input({ required: true }) titulo = '';
}
