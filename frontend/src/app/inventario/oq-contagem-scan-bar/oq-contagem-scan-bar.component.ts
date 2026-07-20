import { AfterViewInit, Component, ElementRef, EventEmitter, Input, Output, ViewChild, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { HttpErrorResponse } from '@angular/common/http';
import { OqIconComponent } from '../../shared/icons/oq-icon.component';
import { OqInlineAlertComponent } from '../../shared/oq-inline-alert/oq-inline-alert.component';
import { InventarioService } from '../inventario.service';
import { TransferenciaService } from '../../transferencia/transferencia.service';
import { ItemInventario } from '../inventario.model';
import { ProdutoEstoque } from '../../transferencia/transferencia.model';
import { environment } from '../../../environments/environment';

/**
 * Bipe de produto/etiqueta pra contagem — mesma regra de soma automática/+1/granel já validada
 * em oq-item-scan-bar (Transferência), mais: entrada manual com justificativa obrigatória
 * (etiqueta ilegível) e sinalização de item "não previsto" no local atual.
 */
@Component({
  selector: 'oq-contagem-scan-bar',
  standalone: true,
  imports: [FormsModule, OqIconComponent, OqInlineAlertComponent],
  templateUrl: './oq-contagem-scan-bar.component.html',
  styleUrl: './oq-contagem-scan-bar.component.scss',
})
export class OqContagemScanBarComponent implements AfterViewInit {
  private readonly inventarioService = inject(InventarioService);
  private readonly transferenciaService = inject(TransferenciaService);

  @Input({ required: true }) inventarioId!: string;
  @Input({ required: true }) local!: string;
  @Output() itemContado = new EventEmitter<ItemInventario>();

  readonly mostrarAtalhos = !environment.producao;

  @ViewChild('inputCodigo') inputCodigoRef?: ElementRef<HTMLInputElement>;
  @ViewChild('inputGranel') inputGranelRef?: ElementRef<HTMLInputElement>;

  codigo = '';
  carregando = false;

  produtoGranel: ProdutoEstoque | null = null;
  qtdGranel = '';

  modoManual = signal(false);
  codigoManual = '';
  justificativaManual = '';
  qtdManual = '';

  readonly erroToast = signal<string | null>(null);
  private erroTimeout?: ReturnType<typeof setTimeout>;

  ngAfterViewInit(): void {
    this.focarCodigo();
  }

  submeter(): void {
    const bruto = this.codigo.trim();
    if (!bruto || this.carregando) return;

    this.carregando = true;
    this.transferenciaService.identificarItem(bruto).subscribe((res) => {
      this.carregando = false;
      this.codigo = '';

      if (!res.ok || !res.produto) {
        this.mostrarErro(res.erro ?? 'Código não reconhecido');
        this.focarCodigo();
        return;
      }

      if (res.produto.modo === 'bulk') {
        this.produtoGranel = res.produto;
        this.qtdGranel = '';
        setTimeout(() => this.inputGranelRef?.nativeElement.focus());
        return;
      }

      const qtd = res.produto.modo === 'labelqty' ? Number(res.produto.qtdEtiqueta ?? 1) : 1;
      this.confirmarContagem(bruto, qtd);
    });
  }

  atalho(codigo: string): void {
    this.codigo = codigo;
    this.submeter();
  }

  confirmarGranel(): void {
    const n = parseFloat(String(this.qtdGranel).replace(',', '.'));
    if (!n || n <= 0) {
      this.inputGranelRef?.nativeElement.focus();
      return;
    }
    const produto = this.produtoGranel!;
    this.produtoGranel = null;
    this.qtdGranel = '';
    this.confirmarContagem(produto.codigo, n);
  }

  abrirManual(): void {
    this.modoManual.set(true);
    this.codigoManual = '';
    this.justificativaManual = '';
    this.qtdManual = '';
  }

  cancelarManual(): void {
    this.modoManual.set(false);
    this.focarCodigo();
  }

  get manualValido(): boolean {
    return this.codigoManual.trim().length > 0 && this.justificativaManual.trim().length > 0;
  }

  confirmarManual(): void {
    if (!this.manualValido) return;
    const qtd = this.qtdManual.trim() ? parseFloat(this.qtdManual.replace(',', '.')) : undefined;
    this.modoManual.set(false);
    this.confirmarContagem(this.codigoManual.trim(), qtd);
  }

  private confirmarContagem(codigoLido: string, qtd?: number): void {
    this.inventarioService.registrarContagem(this.inventarioId, this.local, codigoLido, qtd).subscribe({
      next: (item) => {
        this.itemContado.emit(item);
        this.focarCodigo();
      },
      error: (err: HttpErrorResponse) => {
        this.mostrarErro(err.error?.erro ?? 'Não foi possível registrar a contagem');
        this.focarCodigo();
      },
    });
  }

  private mostrarErro(titulo: string): void {
    this.erroToast.set(titulo);
    if (this.erroTimeout) clearTimeout(this.erroTimeout);
    this.erroTimeout = setTimeout(() => this.erroToast.set(null), 2600);
  }

  private focarCodigo(): void {
    setTimeout(() => this.inputCodigoRef?.nativeElement.focus());
  }
}
