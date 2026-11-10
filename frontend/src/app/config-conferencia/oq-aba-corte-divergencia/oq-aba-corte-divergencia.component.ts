import { Component, EventEmitter, Input, Output, computed, signal } from '@angular/core';
import { OqConfigSecaoComponent } from '../oq-config-secao/oq-config-secao.component';
import { OqConfigCampoRowComponent } from '../oq-config-campo-row/oq-config-campo-row.component';
import { CATALOGO_CONFIG_CONFERENCIA } from '../config-conferencia.catalogo';
import { agruparPorSubSecao, campoHabilitado } from '../config-conferencia.util';

@Component({
  selector: 'oq-aba-corte-divergencia',
  standalone: true,
  imports: [OqConfigSecaoComponent, OqConfigCampoRowComponent],
  templateUrl: './oq-aba-corte-divergencia.component.html',
})
export class OqAbaCorteDivergenciaComponent {
  private readonly camposSignal = signal<Record<string, string | null>>({});

  @Input({ required: true }) set campos(v: Record<string, string | null>) {
    this.camposSignal.set(v);
  }
  /** Mantido pelo pai; esta aba mostra sempre todos os campos. */
  @Input() mostrarTodos = false;
  @Output() campoAlterado = new EventEmitter<{ nome: string; valor: string }>();

  private readonly catalogoAba = CATALOGO_CONFIG_CONFERENCIA.filter((c) => c.aba === 'corte');

  readonly secoes = computed(() => {
    // Aba inteira sempre visível (usuário, 10/11/2026): os campos de corte/divergência do Sankhya valem no corte que
    // a Torre dispara (ConferenciaSP.cortar), mesmo os que a tela da Torre não usa diretamente.
    return agruparPorSubSecao(this.catalogoAba);
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
