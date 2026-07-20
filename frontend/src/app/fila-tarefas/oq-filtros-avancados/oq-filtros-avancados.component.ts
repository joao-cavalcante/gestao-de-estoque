import { Component, EventEmitter, Input, Output } from '@angular/core';
import { OqIconComponent } from '../../shared/icons/oq-icon.component';
import { OqSearchableSelectComponent } from '../../shared/oq-searchable-select/oq-searchable-select.component';
import { FiltrosAvancados, OpcaoComCodigo } from '../tarefa.model';

/**
 * Painel de filtros avançados — mesmo padrão do projeto base (fila-de-conferencia):
 * backdrop invisível (fixed, cobre a tela inteira, só pra fechar no clique-fora)
 * + painel ancorado (absolute) no wrapper do botão "Filtros". Dropdown de
 * verdade, não modal — a Fila de Tarefas continua sendo uma tela só.
 */
@Component({
  selector: 'oq-filtros-avancados',
  standalone: true,
  imports: [OqIconComponent, OqSearchableSelectComponent],
  templateUrl: './oq-filtros-avancados.component.html',
  styleUrl: './oq-filtros-avancados.component.scss',
})
export class OqFiltrosAvancadosComponent {
  @Input() opcoesParceiros: OpcaoComCodigo[] = [];
  @Input() opcoesVendedores: OpcaoComCodigo[] = [];
  @Input() opcoesTiposOperacao: OpcaoComCodigo[] = [];

  rascunho: FiltrosAvancados = { codigoParceiro: null, codigoVendedor: null, codigoTipoOperacao: null };

  @Input({ required: true }) set valores(v: FiltrosAvancados) {
    this.rascunho = { ...v };
  }

  @Output() aplicar = new EventEmitter<FiltrosAvancados>();
  @Output() fechar = new EventEmitter<void>();

  /** Limpa E já aplica — senão o filtro anterior continua valendo até alguém clicar "Aplicar" depois. */
  limpar(): void {
    this.rascunho = { codigoParceiro: null, codigoVendedor: null, codigoTipoOperacao: null };
    this.aplicar.emit(this.rascunho);
  }

  aplicarFiltros(): void {
    this.aplicar.emit(this.rascunho);
  }
}
