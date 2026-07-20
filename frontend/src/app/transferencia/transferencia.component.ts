import { Component, OnInit, computed, inject, signal } from '@angular/core';
import { Router } from '@angular/router';
import { OqLocalStepComponent, LocaisDefinidos } from './oq-local-step/oq-local-step.component';
import { OqItemScanBarComponent } from './oq-item-scan-bar/oq-item-scan-bar.component';
import { OqItemListComponent } from './oq-item-list/oq-item-list.component';
import { OqTransferenciaFooterComponent } from './oq-transferencia-footer/oq-transferencia-footer.component';
import { OqResumoComponent } from './oq-resumo/oq-resumo.component';
import { OqInlineAlertComponent } from '../shared/oq-inline-alert/oq-inline-alert.component';
import { TransferenciaService } from './transferencia.service';
import { ItemTransferencia } from './transferencia.model';

type Etapa = 'carregando' | 'bloqueado' | 'local' | 'itens' | 'resumo';

@Component({
  selector: 'app-transferencia',
  standalone: true,
  imports: [
    OqLocalStepComponent,
    OqItemScanBarComponent,
    OqItemListComponent,
    OqTransferenciaFooterComponent,
    OqResumoComponent,
    OqInlineAlertComponent,
  ],
  templateUrl: './transferencia.component.html',
  styleUrl: './transferencia.component.scss',
})
export class TransferenciaComponent implements OnInit {
  private readonly router = inject(Router);
  private readonly service = inject(TransferenciaService);

  etapa = signal<Etapa>('carregando');
  origem: string | null = null;
  destino: string | null = null;
  transferenciaId: string | null = null;

  private readonly items = signal<ItemTransferencia[]>([]);
  readonly itemsList = this.items.asReadonly();

  readonly totalItens = computed(() => this.items().length);
  readonly totalUnidades = computed(() => this.items().reduce((soma, item) => soma + Number(item.quantidade), 0));
  readonly podeRevisar = computed(() => this.items().length > 0);

  ngOnInit(): void {
    this.service.buscarModeloNota().subscribe((modelo) => {
      this.etapa.set(modelo ? 'local' : 'bloqueado');
    });
  }

  onLocaisDefinidos(locais: LocaisDefinidos): void {
    this.service.criarTransferencia(locais.origem, locais.destino, 'coletor').subscribe((criada) => {
      this.origem = criada.origem;
      this.destino = criada.destino;
      this.transferenciaId = criada.id;
      this.etapa.set('itens');
    });
  }

  onItemAdicionado(item: ItemTransferencia): void {
    this.items.update((arr) => {
      const idx = arr.findIndex((i) => i.id === item.id);
      if (idx === -1) return [item, ...arr];
      return arr.map((i, i2) => (i2 === idx ? item : i));
    });
  }

  /** Não existe endpoint de "corrigir quantidade" — corrige removendo e relançando o item com a quantidade nova. */
  onAlterarQtd(evento: { id: string; qtd: number }): void {
    if (!this.transferenciaId) return;
    const item = this.items().find((i) => i.id === evento.id);
    if (!item) return;

    this.service.removerItem(this.transferenciaId, item.id).subscribe(() => {
      this.items.update((arr) => arr.filter((i) => i.id !== item.id));
      this.service.adicionarItem(this.transferenciaId!, item.codigoProduto, evento.qtd).subscribe((novo) => {
        this.items.update((arr) => [novo, ...arr]);
      });
    });
  }

  onRemover(itemId: string): void {
    if (!this.transferenciaId) return;
    this.service.removerItem(this.transferenciaId, itemId).subscribe(() => {
      this.items.update((arr) => arr.filter((i) => i.id !== itemId));
    });
  }

  onRevisar(): void {
    if (!this.podeRevisar()) return;
    this.etapa.set('resumo');
  }

  onVoltarParaItens(): void {
    this.etapa.set('itens');
  }

  onConcluido(): void {
    this.router.navigate(['/fila-tarefas']);
  }
}
