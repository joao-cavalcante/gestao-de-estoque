import { AfterViewInit, Component, ElementRef, EventEmitter, Input, Output, ViewChild, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { HttpErrorResponse } from '@angular/common/http';
import { OqIconComponent } from '../../shared/icons/oq-icon.component';
import { OqInlineAlertComponent } from '../../shared/oq-inline-alert/oq-inline-alert.component';
import { TransferenciaService } from '../transferencia.service';
import { ItemTransferencia, ProdutoEstoque } from '../transferencia.model';
import { environment } from '../../../environments/environment';

/** Etapa 2 — bipe de produto/controle: etiqueta com qtd. soma automático, peça avulsa soma +1, granel pede quantidade. */
@Component({
  selector: 'oq-item-scan-bar',
  standalone: true,
  imports: [FormsModule, OqIconComponent, OqInlineAlertComponent],
  templateUrl: './oq-item-scan-bar.component.html',
  styleUrl: './oq-item-scan-bar.component.scss',
})
export class OqItemScanBarComponent implements AfterViewInit {
  private readonly service = inject(TransferenciaService);

  @Input({ required: true }) transferenciaId!: string;
  @Output() itemAdicionado = new EventEmitter<ItemTransferencia>();

  readonly mostrarAtalhos = !environment.producao;

  @ViewChild('inputCodigo') inputCodigoRef?: ElementRef<HTMLInputElement>;
  @ViewChild('inputGranel') inputGranelRef?: ElementRef<HTMLInputElement>;

  codigo = '';
  carregando = false;

  produtoGranel: ProdutoEstoque | null = null;
  qtdGranel = '';

  readonly erroToast = signal<string | null>(null);
  private erroTimeout?: ReturnType<typeof setTimeout>;

  ngAfterViewInit(): void {
    this.focarCodigo();
  }

  submeter(): void {
    const bruto = this.codigo.trim();
    if (!bruto || this.carregando) return;

    this.carregando = true;
    this.service.identificarItem(bruto).subscribe((res) => {
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
      this.confirmarItem(res.produto, qtd);
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
    this.confirmarItem(produto, n);
  }

  private confirmarItem(produto: ProdutoEstoque, qtd: number): void {
    this.service.adicionarItem(this.transferenciaId, produto.codigo, qtd).subscribe({
      next: (item) => {
        this.itemAdicionado.emit(item);
        this.focarCodigo();
      },
      error: (err: HttpErrorResponse) => {
        this.mostrarErro(err.error?.erro ?? 'Não foi possível adicionar o item');
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
