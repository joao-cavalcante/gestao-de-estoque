import { AfterViewInit, Component, ElementRef, EventEmitter, Output, ViewChild, inject } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { OqIconComponent } from '../../shared/icons/oq-icon.component';
import { OqInlineAlertComponent } from '../../shared/oq-inline-alert/oq-inline-alert.component';
import { TransferenciaService } from '../transferencia.service';
import { environment } from '../../../environments/environment';

export interface LocaisDefinidos {
  origem: string;
  destino: string;
}

type Passo = 'origem' | 'destino';

/** Etapa 1 — bipa origem, depois destino (não pode repetir a origem), 2 campos encadeados no mesmo fluxo. */
@Component({
  selector: 'oq-local-step',
  standalone: true,
  imports: [FormsModule, OqIconComponent, OqInlineAlertComponent],
  templateUrl: './oq-local-step.component.html',
  styleUrl: './oq-local-step.component.scss',
})
export class OqLocalStepComponent implements AfterViewInit {
  private readonly service = inject(TransferenciaService);

  @Output() concluido = new EventEmitter<LocaisDefinidos>();

  readonly mostrarAtalhos = !environment.producao;

  @ViewChild('input') inputRef?: ElementRef<HTMLInputElement>;

  passo: Passo = 'origem';
  valor = '';
  origem: string | null = null;
  erro: { titulo: string; detalhe: string } | null = null;
  carregando = false;

  ngAfterViewInit(): void {
    this.focar();
  }

  get titulo(): string {
    return this.passo === 'origem' ? 'Bipe o local de origem' : 'Bipe o local de destino';
  }

  get dica(): string {
    return this.passo === 'origem'
      ? 'aceita leitor de código de barras ou digitação'
      : 'confirme o local que vai receber os itens';
  }

  get stepLabel(): string {
    return this.passo === 'origem' ? 'ETAPA 1 · 1/2' : 'ETAPA 1 · 2/2';
  }

  submeter(): void {
    const bruto = this.valor;
    if (!bruto.trim() || this.carregando) return;

    this.carregando = true;
    this.service.validarLocal(bruto, this.passo === 'destino' ? this.origem : null).subscribe((res) => {
      this.carregando = false;

      if (!res.ok) {
        this.erro = { titulo: res.erro ?? 'Local inválido', detalhe: `CÓDIGO LIDO: ${res.codigo}` };
        this.valor = '';
        this.focar();
        return;
      }

      this.erro = null;
      if (this.passo === 'origem') {
        this.origem = res.codigo;
        this.passo = 'destino';
        this.valor = '';
        this.focar();
      } else {
        this.concluido.emit({ origem: this.origem!, destino: res.codigo });
      }
    });
  }

  atalho(codigo: string): void {
    this.valor = codigo;
    this.submeter();
  }

  private focar(): void {
    setTimeout(() => this.inputRef?.nativeElement.focus());
  }
}
