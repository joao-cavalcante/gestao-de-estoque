import { CommonModule } from '@angular/common';
import { Component, inject, signal } from '@angular/core';
import { RouterLink } from '@angular/router';
import { Tenant } from '../tenant.model';
import { TenantService } from '../tenant.service';

@Component({
  selector: 'app-tenant-list',
  standalone: true,
  imports: [CommonModule, RouterLink],
  templateUrl: './tenant-list.component.html',
  styleUrl: './tenant-list.component.scss',
})
export class TenantListComponent {
  private readonly tenantService = inject(TenantService);

  tenants = signal<Tenant[]>([]);
  carregando = signal(true);
  erro = signal<string | null>(null);

  constructor() {
    this.carregar();
  }

  carregar(): void {
    this.carregando.set(true);
    this.erro.set(null);
    this.tenantService.listar().subscribe({
      next: (tenants) => {
        this.tenants.set(tenants);
        this.carregando.set(false);
      },
      error: (err) => {
        this.erro.set('Falha ao carregar tenants: ' + (err?.message ?? 'erro desconhecido'));
        this.carregando.set(false);
      },
    });
  }

  remover(slug: string): void {
    if (!confirm(`Remover o tenant "${slug}"? Essa ação não pode ser desfeita.`)) return;
    this.tenantService.remover(slug).subscribe({
      next: () => this.carregar(),
      error: (err) => this.erro.set('Falha ao remover: ' + (err?.message ?? 'erro desconhecido')),
    });
  }
}
