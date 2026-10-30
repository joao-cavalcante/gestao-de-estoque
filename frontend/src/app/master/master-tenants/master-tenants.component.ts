import { Component, OnInit, computed, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { Router } from '@angular/router';
import { HttpErrorResponse } from '@angular/common/http';
import { MasterService } from '../master.service';
import { MasterAuthService } from '../master-auth.service';
import { Instalacao, STATUS_ROTULO, TenantMaster } from '../master.model';
import { OqPanelSectionComponent } from '../../conferencia/oq-panel-section/oq-panel-section.component';
import { OqStatusChipComponent } from '../../conferencia/oq-status-chip/oq-status-chip.component';
import { OqIconComponent } from '../../shared/icons/oq-icon.component';
import { OqSkeletonComponent } from '../../shared/oq-skeleton/oq-skeleton.component';
import { OqSpinnerComponent } from '../../shared/icons/oq-spinner.component';
import { ChipTone } from '../../conferencia/conferencia.model';
import { tomIntegracao, textoIntegracao, tomStatus } from '../master-situacao';

/** Painel Master — clientes da plataforma (todos os tenants desta instalação). */
@Component({
  selector: 'app-master-tenants',
  standalone: true,
  imports: [FormsModule, OqPanelSectionComponent, OqStatusChipComponent, OqIconComponent, OqSkeletonComponent, OqSpinnerComponent],
  templateUrl: './master-tenants.component.html',
  styleUrl: '../master.scss',
})
export class MasterTenantsComponent implements OnInit {
  private readonly service = inject(MasterService);
  private readonly router = inject(Router);
  readonly auth = inject(MasterAuthService);

  readonly tenants = signal<TenantMaster[]>([]);
  readonly instalacao = signal<Instalacao | null>(null);
  readonly carregando = signal(true);
  readonly erro = signal<string | null>(null);
  readonly busca = signal('');

  readonly filtrados = computed(() => {
    const termo = this.busca().trim().toLowerCase();
    const lista = [...this.tenants()].sort((a, b) => a.tenant.nome.localeCompare(b.tenant.nome, 'pt-BR'));
    return termo ? lista.filter((t) => `${t.tenant.nome} ${t.tenant.slug}`.toLowerCase().includes(termo)) : lista;
  });

  // Modal "Novo cliente"
  readonly modalAberto = signal(false);
  readonly salvando = signal(false);
  readonly modalErro = signal<string | null>(null);
  novoSlug = '';
  novoNome = '';

  readonly STATUS_ROTULO = STATUS_ROTULO;

  ngOnInit(): void {
    this.carregar();
    this.service.instalacao().subscribe({ next: (i) => this.instalacao.set(i), error: () => this.instalacao.set(null) });
  }

  carregar(): void {
    this.carregando.set(true);
    this.service.listar().subscribe({
      next: (t) => {
        this.tenants.set(t);
        this.carregando.set(false);
        this.erro.set(null);
      },
      error: () => {
        this.carregando.set(false);
        this.erro.set('Não foi possível carregar os clientes.');
      },
    });
  }

  abrir(t: TenantMaster): void {
    this.router.navigate(['/master/tenants', t.tenant.id]);
  }

  iniciais(nome: string): string {
    return nome.split(/\s+/).filter(Boolean).slice(0, 2).map((p) => p[0]).join('').toUpperCase();
  }

  tomStatus(t: TenantMaster): ChipTone {
    return tomStatus(t.tenant.status);
  }

  tomIntegracao(t: TenantMaster): ChipTone {
    return tomIntegracao(t);
  }

  textoIntegracao(t: TenantMaster): string {
    return textoIntegracao(t);
  }

  modulos(t: TenantMaster): number {
    return t.tenant.erpConnections.flatMap((c) => c.modulos).length;
  }

  abrirCriacao(): void {
    this.novoSlug = '';
    this.novoNome = '';
    this.modalErro.set(null);
    this.modalAberto.set(true);
  }

  fecharModal(): void {
    if (!this.salvando()) this.modalAberto.set(false);
  }

  criar(): void {
    this.salvando.set(true);
    this.modalErro.set(null);
    this.service.criar(this.novoSlug.trim().toLowerCase(), this.novoNome.trim()).subscribe({
      next: (t) => {
        this.salvando.set(false);
        this.modalAberto.set(false);
        this.router.navigate(['/master/tenants', t.id]);
      },
      error: (e: HttpErrorResponse) => {
        this.salvando.set(false);
        this.modalErro.set(e.error?.erro ?? 'Não foi possível cadastrar o cliente.');
      },
    });
  }
}
