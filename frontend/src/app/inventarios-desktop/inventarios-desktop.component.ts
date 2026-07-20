import { Component, OnInit, inject, signal } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { Router } from '@angular/router';
import { HttpErrorResponse } from '@angular/common/http';
import { OqIconComponent } from '../shared/icons/oq-icon.component';
import { OqInlineAlertComponent } from '../shared/oq-inline-alert/oq-inline-alert.component';
import { OqPanelSectionComponent } from '../conferencia/oq-panel-section/oq-panel-section.component';
import { OqStatusChipComponent } from '../conferencia/oq-status-chip/oq-status-chip.component';
import { ChipTone } from '../conferencia/conferencia.model';
import { InventarioService } from '../inventario/inventario.service';
import { EscopoTipo, InventarioListItem, StatusInventario } from '../inventario/inventario.model';
import { TransferenciaService } from '../transferencia/transferencia.service';

type FiltroStatus = 'todos' | StatusInventario;

@Component({
  selector: 'app-inventarios-desktop',
  standalone: true,
  imports: [CommonModule, FormsModule, OqIconComponent, OqInlineAlertComponent, OqPanelSectionComponent, OqStatusChipComponent],
  templateUrl: './inventarios-desktop.component.html',
  styleUrl: './inventarios-desktop.component.scss',
})
export class InventariosDesktopComponent implements OnInit {
  private readonly service = inject(InventarioService);
  private readonly transferenciaService = inject(TransferenciaService);
  private readonly router = inject(Router);

  lista = signal<InventarioListItem[]>([]);
  totalRegistros = signal(0);
  totalPaginas = signal(1);
  paginaAtual = signal(1);
  carregando = signal(true);
  filtroStatus = signal<FiltroStatus>('todos');
  termoBusca = signal('');

  modeloNotaDisponivel = signal<boolean | null>(null);

  modalAberto = signal(false);
  descricao = '';
  escopoTipo: EscopoTipo = 'local';
  valoresBruto = '';
  erroForm = signal<string | null>(null);
  criando = signal(false);

  ngOnInit(): void {
    this.carregarLista();
    this.transferenciaService.buscarModeloNota().subscribe((modelo) => this.modeloNotaDisponivel.set(!!modelo));
  }

  carregarLista(): void {
    this.carregando.set(true);
    const status = this.filtroStatus() === 'todos' ? undefined : (this.filtroStatus() as StatusInventario);
    const busca = this.termoBusca().trim() || undefined;

    this.service.listar({ status, busca, pagina: this.paginaAtual() }).subscribe({
      next: (res) => {
        this.lista.set(res.itens);
        this.totalRegistros.set(res.totalRegistros);
        this.totalPaginas.set(res.totalPaginas);
        this.paginaAtual.set(res.paginaAtual);
        this.carregando.set(false);
      },
      error: () => this.carregando.set(false),
    });
  }

  aoMudarFiltro(): void {
    this.paginaAtual.set(1);
    this.carregarLista();
  }

  irParaPagina(pagina: number): void {
    if (pagina < 1 || pagina > this.totalPaginas()) return;
    this.paginaAtual.set(pagina);
    this.carregarLista();
  }

  abrirNovoInventario(): void {
    this.descricao = '';
    this.escopoTipo = 'local';
    this.valoresBruto = '';
    this.erroForm.set(null);
    this.modalAberto.set(true);
  }

  fecharModal(): void {
    this.modalAberto.set(false);
  }

  criarInventario(): void {
    if (this.criando()) return;
    const valores = this.valoresBruto
      .split(',')
      .map((v) => v.trim().toUpperCase())
      .filter((v) => v.length > 0);

    if (!this.descricao.trim() || valores.length === 0) {
      this.erroForm.set('Preencha a descrição e ao menos um valor de escopo');
      return;
    }

    this.criando.set(true);
    this.erroForm.set(null);
    this.service.abrir(this.descricao.trim(), this.escopoTipo, valores).subscribe({
      next: (criado) => {
        this.criando.set(false);
        this.fecharModal();
        this.router.navigate(['/inventarios', criado.id]);
      },
      error: (err: HttpErrorResponse) => {
        this.criando.set(false);
        this.erroForm.set(err.error?.erro ?? 'Não foi possível abrir o inventário');
      },
    });
  }

  abrirDetalhe(inv: InventarioListItem): void {
    this.router.navigate(['/inventarios', inv.id]);
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

  formatarData(iso: string): string {
    return new Date(iso).toLocaleString('pt-BR');
  }
}
