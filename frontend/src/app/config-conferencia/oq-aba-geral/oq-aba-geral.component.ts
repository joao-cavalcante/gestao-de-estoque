import { Component, EventEmitter, Input, Output, computed, signal } from '@angular/core';
import { OqConfigSecaoComponent } from '../oq-config-secao/oq-config-secao.component';
import { OqConfigCampoRowComponent } from '../oq-config-campo-row/oq-config-campo-row.component';
import { CATALOGO_CONFIG_CONFERENCIA } from '../config-conferencia.catalogo';
import { agruparPorSubSecao, campoHabilitado } from '../config-conferencia.util';

@Component({
  selector: 'oq-aba-geral',
  standalone: true,
  imports: [OqConfigSecaoComponent, OqConfigCampoRowComponent],
  templateUrl: './oq-aba-geral.component.html',
})
export class OqAbaGeralComponent {
  private readonly camposSignal = signal<Record<string, string | null>>({});
  private readonly mostrarTodosSignal = signal(false);

  @Input({ required: true }) set campos(v: Record<string, string | null>) {
    this.camposSignal.set(v);
  }
  @Input() set mostrarTodos(v: boolean) {
    this.mostrarTodosSignal.set(v);
  }
  @Output() campoAlterado = new EventEmitter<{ nome: string; valor: string }>();

  private readonly catalogoAba = CATALOGO_CONFIG_CONFERENCIA.filter((c) => c.aba === 'geral');

  readonly secoes = computed(() => {
    const filtrado = this.mostrarTodosSignal() ? this.catalogoAba : this.catalogoAba.filter((c) => c.status === 'implementado');
    return agruparPorSubSecao(filtrado);
  });

  habilitado(nome: string): boolean {
    const campo = this.catalogoAba.find((c) => c.nome === nome);
    return campo ? campoHabilitado(campo, this.camposSignal()) : true;
  }

  valor(nome: string): string | null {
    return this.camposSignal()[nome] ?? null;
  }

  onValorChange(nome: string, valor: string): void {
    this.campoAlterado.emit({ nome, valor });
  }
}
