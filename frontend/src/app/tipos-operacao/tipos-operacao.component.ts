import { Component, OnInit, inject, signal } from '@angular/core';
import { OqIconComponent } from '../shared/icons/oq-icon.component';
import { OqPanelSectionComponent } from '../conferencia/oq-panel-section/oq-panel-section.component';
import { OqInlineAlertComponent } from '../shared/oq-inline-alert/oq-inline-alert.component';
import { TiposOperacaoService } from './tipos-operacao.service';
import { TipoOperacao } from './tipos-operacao.model';

/**
 * Só exibição — espelho local da TGFTOP (V16), filtrado no backend pra só trazer
 * os TOP com Configuração de Conferência vinculada (NUCCO IS NOT NULL, mesmo
 * filtro que o fila-de-conferencia já usava). Tela própria, não mais uma aba
 * dentro de Config Conferência — decisão revertida a pedido do usuário.
 */
@Component({
  selector: 'app-tipos-operacao',
  standalone: true,
  imports: [OqIconComponent, OqPanelSectionComponent, OqInlineAlertComponent],
  templateUrl: './tipos-operacao.component.html',
})
export class TiposOperacaoComponent implements OnInit {
  private readonly service = inject(TiposOperacaoService);

  tops = signal<TipoOperacao[]>([]);
  carregando = signal(true);
  sincronizando = signal(false);
  erro = signal<string | null>(null);

  ngOnInit(): void {
    this.carregar();
  }

  private carregar(): void {
    this.carregando.set(true);
    this.service.listar().subscribe({
      next: (itens) => {
        this.tops.set(itens);
        this.carregando.set(false);
      },
      error: () => this.carregando.set(false),
    });
  }

  sincronizarAgora(): void {
    if (this.sincronizando()) return;
    this.sincronizando.set(true);
    this.erro.set(null);
    this.service.sincronizar().subscribe({
      next: () => {
        this.sincronizando.set(false);
        this.carregar();
      },
      error: (err) => {
        this.sincronizando.set(false);
        this.erro.set(err.error?.erro ?? 'Não foi possível sincronizar com o Sankhya');
      },
    });
  }
}
