import { AfterViewInit, Component, ElementRef, EventEmitter, Input, Output, ViewChild } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { OqIconComponent } from '../../shared/icons/oq-icon.component';
import { OqSearchableSelectComponent } from '../../shared/oq-searchable-select/oq-searchable-select.component';
import { FiltrosAvancados, OpcaoComCodigo, VinculoOrdemCarga } from '../tarefa.model';

const VAZIO: FiltrosAvancados = {
  codigoParceiro: null,
  codigoVendedor: null,
  codigoTipoOperacao: null,
  ordemCarga: null,
  vinculoOrdemCarga: 'todos',
};

/**
 * Painel de filtros avançados — mesmo padrão do projeto base (fila-de-conferencia):
 * backdrop invisível (fixed, cobre a tela inteira, só pra fechar no clique-fora)
 * + painel ancorado (absolute) no wrapper do botão "Filtros". Dropdown de
 * verdade, não modal — a Fila de Tarefas continua sendo uma tela só.
 */
@Component({
  selector: 'oq-filtros-avancados',
  standalone: true,
  imports: [FormsModule, OqIconComponent, OqSearchableSelectComponent],
  templateUrl: './oq-filtros-avancados.component.html',
  styleUrl: './oq-filtros-avancados.component.scss',
})
export class OqFiltrosAvancadosComponent implements AfterViewInit {
  @ViewChild('painel', { static: true }) private painel!: ElementRef<HTMLElement>;
  @Input() opcoesParceiros: OpcaoComCodigo[] = [];
  @Input() opcoesVendedores: OpcaoComCodigo[] = [];
  @Input() opcoesTiposOperacao: OpcaoComCodigo[] = [];

  rascunho: FiltrosAvancados = { ...VAZIO };

  readonly opcoesVinculo: { valor: VinculoOrdemCarga; label: string }[] = [
    { valor: 'todos', label: 'Todos' },
    { valor: 'com', label: 'Com OC' },
    { valor: 'sem', label: 'Sem OC' },
  ];

  @Input({ required: true }) set valores(v: FiltrosAvancados) {
    this.rascunho = { ...v };
  }

  @Output() aplicar = new EventEmitter<FiltrosAvancados>();
  @Output() fechar = new EventEmitter<void>();

  /**
   * O painel é ancorado pela direita do botão. Em tablet/celular a toolbar
   * quebra linha e o botão pode ficar colado à esquerda — aí o painel abriria
   * pra fora da tela. Mede ao abrir e empurra pra dentro (margem de 16px).
   */
  ngAfterViewInit(): void {
    const el = this.painel.nativeElement;
    const margem = 16;
    const r = el.getBoundingClientRect();
    let dx = 0;
    if (r.left < margem) dx = margem - r.left;
    else if (r.right > window.innerWidth - margem) dx = window.innerWidth - margem - r.right;
    if (dx) el.style.transform = `translateX(${dx}px)`;
  }

  /** Limpa E já aplica — senão o filtro anterior continua valendo até alguém clicar "Aplicar" depois. */
  limpar(): void {
    this.rascunho = { ...VAZIO };
    this.aplicar.emit(this.rascunho);
  }

  aplicarFiltros(): void {
    this.aplicar.emit({
      ...this.rascunho,
      ordemCarga: this.rascunho.ordemCarga?.trim() || null,
    });
  }
}
