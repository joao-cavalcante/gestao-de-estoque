import { Component, Input, inject } from '@angular/core';
import { OqIconComponent } from '../../shared/icons/oq-icon.component';
import { NavMenuService } from '../../shared/nav-menu/nav-menu.service';

@Component({
  selector: 'oq-conferencia-header',
  standalone: true,
  imports: [OqIconComponent],
  templateUrl: './oq-conferencia-header.component.html',
  styleUrl: './oq-conferencia-header.component.scss',
})
export class OqConferenciaHeaderComponent {
  private readonly nav = inject(NavMenuService);
  readonly menuAberto = this.nav.aberto;

  alternarMenu(): void {
    this.nav.alternar();
  }

  @Input() iniciais = 'LG';
  @Input() nf = '';
  @Input() parceiro = '';
  @Input() vendedor = '';
  @Input() numeroConferencia = '';

  @Input() pendingCount = 0;
  @Input() conferredCount = 0;
  @Input() divergenceCount = 0;

  formatarKpi(v: number): string {
    return String(v).padStart(3, '0');
  }
}
