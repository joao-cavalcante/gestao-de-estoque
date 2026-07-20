import { Component, OnDestroy, OnInit, computed, inject, signal } from '@angular/core';
import { CommonModule } from '@angular/common';
import { ActivatedRoute, Router } from '@angular/router';
import { HttpErrorResponse } from '@angular/common/http';
import { OqIconComponent } from '../../shared/icons/oq-icon.component';
import { OqInlineAlertComponent } from '../../shared/oq-inline-alert/oq-inline-alert.component';
import { OqStatusChipComponent } from '../../conferencia/oq-status-chip/oq-status-chip.component';
import { ChipTone } from '../../conferencia/conferencia.model';
import { InventarioService } from '../../inventario/inventario.service';
import { InventarioDetalhe, ItemInventario } from '../../inventario/inventario.model';

const TOLERANCIA_PERCENTUAL = 0.02; // simplificação v1 — sem tela de configuração ainda
const INTERVALO_POLLING_MS = 5000;

type SeveridadeDivergencia = 'neutro' | 'atencao' | 'critico';

@Component({
  selector: 'app-inventario-detalhe',
  standalone: true,
  imports: [CommonModule, OqIconComponent, OqInlineAlertComponent, OqStatusChipComponent],
  templateUrl: './inventario-detalhe.component.html',
  styleUrl: './inventario-detalhe.component.scss',
})
export class InventarioDetalheComponent implements OnInit, OnDestroy {
  private readonly service = inject(InventarioService);
  private readonly route = inject(ActivatedRoute);
  private readonly router = inject(Router);

  private id!: string;
  private pollingHandle?: ReturnType<typeof setInterval>;

  carregando = signal(true);
  inventario = signal<InventarioDetalhe | null>(null);

  modalAprovarAberto = signal(false);
  aprovando = signal(false);
  erroAprovar = signal<string | null>(null);
  avisoRecontagem = signal<string | null>(null);

  readonly divergencias = computed(() => {
    const inv = this.inventario();
    if (!inv) return [];
    return inv.itens.filter((i) => i.quantidadeContada !== i.quantidadeSistema);
  });

  readonly temPendentes = computed(() => this.inventario()?.itens.some((i) => i.statusItem === 'pendente') ?? true);

  ngOnInit(): void {
    this.id = this.route.snapshot.paramMap.get('id')!;
    this.carregar();
  }

  ngOnDestroy(): void {
    this.pararPolling();
  }

  private carregar(): void {
    this.service.detalhe(this.id).subscribe({
      next: (inv) => {
        this.inventario.set(inv);
        this.carregando.set(false);
        this.gerenciarPolling(inv.status);
      },
      error: () => this.carregando.set(false),
    });
  }

  private gerenciarPolling(status: string): void {
    const precisaPolling = status === 'aberto' || status === 'em_contagem';
    if (precisaPolling && !this.pollingHandle) {
      this.pollingHandle = setInterval(() => this.carregar(), INTERVALO_POLLING_MS);
    } else if (!precisaPolling) {
      this.pararPolling();
    }
  }

  private pararPolling(): void {
    if (this.pollingHandle) {
      clearInterval(this.pollingHandle);
      this.pollingHandle = undefined;
    }
  }

  finalizarContagem(): void {
    this.service.mudarStatus(this.id, 'finalizado').subscribe(() => this.carregar());
  }

  cancelarInventario(): void {
    this.service.cancelar(this.id).subscribe(() => this.carregar());
  }

  abrirModalAprovar(): void {
    this.erroAprovar.set(null);
    this.modalAprovarAberto.set(true);
  }

  fecharModalAprovar(): void {
    this.modalAprovarAberto.set(false);
  }

  confirmarAprovacao(): void {
    if (this.aprovando()) return;
    this.aprovando.set(true);
    this.erroAprovar.set(null);
    this.service.aprovar(this.id).subscribe({
      next: (res) => {
        this.aprovando.set(false);
        this.modalAprovarAberto.set(false);
        this.avisoRecontagem.set(res.avisoRecontagem ?? null);
        this.carregar();
      },
      error: (err: HttpErrorResponse) => {
        this.aprovando.set(false);
        this.erroAprovar.set(err.error?.erro ?? 'Não foi possível aprovar o ajuste');
      },
    });
  }

  voltar(): void {
    this.router.navigate(['/inventarios']);
  }

  severidade(item: ItemInventario): SeveridadeDivergencia {
    if (item.statusItem === 'nao_previsto' || item.statusItem === 'pendente') return 'critico';
    const sistema = Number(item.quantidadeSistema);
    const divergencia = Math.abs(Number(item.divergencia));
    if (divergencia === 0) return 'neutro';
    const percentual = sistema === 0 ? 1 : divergencia / Math.abs(sistema);
    return percentual > TOLERANCIA_PERCENTUAL ? 'atencao' : 'neutro';
  }

  toneStatus(status: string): ChipTone {
    switch (status) {
      case 'ajustado':
        return 'success';
      case 'cancelado':
        return 'critical';
      case 'finalizado':
        return 'warning';
      default:
        return 'neutral';
    }
  }

  toneStatusItem(item: ItemInventario): ChipTone {
    if (item.statusItem === 'pendente') return 'critical';
    if (item.statusItem === 'nao_previsto') return 'warning';
    return 'success';
  }
}
