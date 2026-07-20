import { AfterViewInit, Component, ElementRef, EventEmitter, Output, ViewChild, inject } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { OqIconComponent } from '../../shared/icons/oq-icon.component';
import { OqInlineAlertComponent } from '../../shared/oq-inline-alert/oq-inline-alert.component';
import { TransferenciaService } from '../../transferencia/transferencia.service';
import { environment } from '../../../environments/environment';

/** Bipe de local único — sem etapa de destino, ao contrário de oq-local-step (Transferência). */
@Component({
  selector: 'oq-bipar-local',
  standalone: true,
  imports: [FormsModule, OqIconComponent, OqInlineAlertComponent],
  templateUrl: './oq-bipar-local.component.html',
  styleUrl: './oq-bipar-local.component.scss',
})
export class OqBiparLocalComponent implements AfterViewInit {
  private readonly service = inject(TransferenciaService);

  @Output() localIdentificado = new EventEmitter<string>();

  readonly mostrarAtalhos = !environment.producao;

  @ViewChild('input') inputRef?: ElementRef<HTMLInputElement>;

  valor = '';
  erro: string | null = null;
  carregando = false;

  ngAfterViewInit(): void {
    this.focar();
  }

  submeter(): void {
    const bruto = this.valor;
    if (!bruto.trim() || this.carregando) return;

    this.carregando = true;
    this.service.validarLocal(bruto, null).subscribe((res) => {
      this.carregando = false;
      if (!res.ok) {
        this.erro = res.erro ?? 'Local inválido';
        this.valor = '';
        this.focar();
        return;
      }
      this.erro = null;
      this.localIdentificado.emit(res.codigo);
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
