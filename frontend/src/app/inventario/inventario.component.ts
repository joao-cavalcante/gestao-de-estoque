import { Component, OnInit, computed, inject, signal } from '@angular/core';
import { OqBiparLocalComponent } from './oq-bipar-local/oq-bipar-local.component';
import { OqContagemScanBarComponent } from './oq-contagem-scan-bar/oq-contagem-scan-bar.component';
import { OqContagemListaComponent } from './oq-contagem-lista/oq-contagem-lista.component';
import { InventarioService } from './inventario.service';
import { InventarioListItem, ItemInventario } from './inventario.model';

type Etapa = 'carregando' | 'sem-inventario' | 'selecionar' | 'local' | 'contagem';

@Component({
  selector: 'app-inventario',
  standalone: true,
  imports: [OqBiparLocalComponent, OqContagemScanBarComponent, OqContagemListaComponent],
  templateUrl: './inventario.component.html',
  styleUrl: './inventario.component.scss',
})
export class InventarioComponent implements OnInit {
  private readonly service = inject(InventarioService);

  etapa = signal<Etapa>('carregando');
  inventariosAtivos = signal<InventarioListItem[]>([]);
  inventarioAtual: InventarioListItem | null = null;
  localAtual: string | null = null;

  private readonly itensDoLocal = signal<ItemInventario[]>([]);
  readonly itensDoLocalList = this.itensDoLocal.asReadonly();

  readonly confirmandoZerado = signal(false);

  readonly totalContado = computed(() => this.itensDoLocal().reduce((s, i) => s + Number(i.quantidadeContada), 0));

  ngOnInit(): void {
    this.carregarInventariosAtivos();
  }

  private carregarInventariosAtivos(): void {
    this.etapa.set('carregando');
    this.service.listar({}).subscribe({
      next: (res) => {
        const ativos = res.itens.filter((i) => i.status === 'aberto' || i.status === 'em_contagem');
        this.inventariosAtivos.set(ativos);
        if (ativos.length === 0) {
          this.etapa.set('sem-inventario');
        } else if (ativos.length === 1) {
          this.selecionarInventario(ativos[0]);
        } else {
          this.etapa.set('selecionar');
        }
      },
      error: () => this.etapa.set('sem-inventario'),
    });
  }

  selecionarInventario(inv: InventarioListItem): void {
    this.inventarioAtual = inv;
    this.etapa.set('local');
  }

  onLocalIdentificado(local: string): void {
    this.localAtual = local;
    this.itensDoLocal.set([]);
    this.etapa.set('contagem');
  }

  onItemContado(item: ItemInventario): void {
    this.itensDoLocal.update((arr) => {
      const idx = arr.findIndex((i) => i.id === item.id);
      if (idx === -1) return [item, ...arr];
      return arr.map((i, i2) => (i2 === idx ? item : i));
    });
  }

  pedirConfirmarPosicao(): void {
    if (this.itensDoLocal().length === 0) {
      this.confirmandoZerado.set(true);
      return;
    }
    this.confirmarPosicao();
  }

  confirmarPosicaoZerada(): void {
    this.confirmandoZerado.set(false);
    this.confirmarPosicao();
  }

  cancelarConfirmacaoZerada(): void {
    this.confirmandoZerado.set(false);
  }

  private confirmarPosicao(): void {
    this.localAtual = null;
    this.itensDoLocal.set([]);
    this.etapa.set('local');
  }
}
