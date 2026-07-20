import { AfterViewInit, Component, ElementRef, EventEmitter, Input, Output, ViewChild, inject } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { OqIconComponent } from '../../shared/icons/oq-icon.component';
import { SeparacaoService } from '../../separacao/separacao.service';
import { ItemConferido } from '../../separacao/separacao.model';

export interface ProdutoIdentificadoEvento {
  codprod: number;
  descricaoProduto: string | null;
  imagemUrl: string | null;
}

/**
 * Fluxo real (portado do projeto base) — 3 campos encadeados, tudo
 * navegável só com TAB (Enter faz a mesma coisa, pra quem prefere):
 *
 * 1. Código de barras / ID do produto → identifica o produto (POST
 *    /identificar). Decide se o campo 2 vira <select> (produto de
 *    controle por LISTA) ou <input> livre (controle por LOTE — TIPCONTEST='L').
 * 2. Controle/Lote → Tab avança pra quantidade.
 * 3. Quantidade → Tab confirma de verdade (POST /conferir) e volta o foco
 *    pro campo 1, pronto pro próximo bipe.
 */
@Component({
  selector: 'oq-scan-bar',
  standalone: true,
  imports: [FormsModule, OqIconComponent],
  templateUrl: './oq-scan-bar.component.html',
  styleUrl: './oq-scan-bar.component.scss',
})
export class OqScanBarComponent implements AfterViewInit {
  private readonly separacaoService = inject(SeparacaoService);

  @Input({ required: true }) tenant!: string;
  @Input({ required: true }) sessaoId!: string;

  @Output() conferido = new EventEmitter<ItemConferido>();
  @Output() naoEncontrado = new EventEmitter<string>();
  @Output() identificado = new EventEmitter<ProdutoIdentificadoEvento>();

  @ViewChild('inputIdentificador') inputIdentificador?: ElementRef<HTMLInputElement>;
  @ViewChild('selectControle') selectControleRef?: ElementRef<HTMLSelectElement>;
  @ViewChild('inputControleLote') inputControleLoteRef?: ElementRef<HTMLInputElement>;
  @ViewChild('inputQtd') inputQtdRef?: ElementRef<HTMLInputElement>;

  codigo = '';
  controle = '';
  qtd = '1';

  produtoIdentificado = false;
  controleModoLote = false;
  controlesDisponiveis: string[] = [];
  /** Só true nos 2 casos reais: produto sem controle nenhum, ou controle veio de ESTOQUE (EST) já definido — nunca um "palpite". */
  controleTravado = false;
  carregando = false;

  ngAfterViewInit(): void {
    this.focarIdentificador();
  }

  onQtdInput(valor: string): void {
    this.qtd = valor.replace(/[^\d,.]/g, '');
  }

  /** Passo 1: identifica o produto a partir do código bipado/digitado. */
  onIdentificadorTab(): void {
    const codigo = this.codigo.trim();
    if (!codigo || this.carregando) return;

    this.carregando = true;
    this.separacaoService.identificarProduto(this.tenant, this.sessaoId, codigo).subscribe({
      next: (resultado) => {
        this.carregando = false;
        this.produtoIdentificado = true;
        this.codprodAtual = resultado.codprod;
        this.controleModoLote = resultado.controleModoLote;
        this.controlesDisponiveis = resultado.controlesDisponiveis;
        this.controleTravado = resultado.controleTravado;

        if (resultado.controleAutoSelecionado != null) {
          this.controle = resultado.controleAutoSelecionado;
        } else {
          this.controle = '';
        }

        // Travado (sem controle, ou veio certo do estoque) pula direto pra
        // quantidade — não faz sentido focar um campo que o operador não
        // pode mexer. Fora isso, ele sempre escolhe manualmente.
        if (this.controleTravado) {
          this.focarQtd();
        } else {
          this.focarControle();
        }

        this.identificado.emit({
          codprod: resultado.codprod,
          descricaoProduto: resultado.descricaoProduto,
          imagemUrl: resultado.imagemBase64,
        });
      },
      error: () => {
        this.carregando = false;
        this.naoEncontrado.emit(codigo);
        this.resetarTudo();
        this.focarIdentificador();
      },
    });
  }

  /** Passo 2: controle escolhido/digitado — avança pra quantidade. */
  onControleTab(): void {
    this.focarQtd();
  }

  /** Passo 3 (final): confirma a quantidade de verdade. */
  onQtdTab(): void {
    const qtdN = Number(this.qtd.replace(',', '.'));
    if (!this.produtoIdentificado || this.codprodAtual == null || !qtdN || qtdN <= 0) {
      this.focarQtd();
      return;
    }
    if (this.controleModoLote && !this.controle.trim()) {
      this.focarControle();
      return;
    }
    if (this.carregando) return;

    this.carregando = true;
    this.separacaoService.conferir(this.tenant, this.sessaoId, this.codprodAtual, this.controle, qtdN).subscribe({
      next: (resultado) => {
        this.carregando = false;
        this.conferido.emit(resultado);
        this.resetarTudo();
        this.focarIdentificador();
      },
      error: () => {
        this.carregando = false;
        this.naoEncontrado.emit(this.codigo);
        this.resetarTudo();
        this.focarIdentificador();
      },
    });
  }

  private codprodAtual: number | null = null;

  private resetarTudo(): void {
    this.codigo = '';
    this.controle = '';
    this.qtd = '1';
    this.produtoIdentificado = false;
    this.controleModoLote = false;
    this.controlesDisponiveis = [];
    this.controleTravado = false;
    this.codprodAtual = null;
  }

  private focarIdentificador(): void {
    setTimeout(() => this.inputIdentificador?.nativeElement.focus());
  }

  private focarControle(): void {
    setTimeout(() => {
      if (this.controleModoLote) {
        this.inputControleLoteRef?.nativeElement.focus();
      } else {
        this.selectControleRef?.nativeElement.focus();
      }
    });
  }

  private focarQtd(): void {
    setTimeout(() => {
      this.inputQtdRef?.nativeElement.focus();
      this.inputQtdRef?.nativeElement.select();
    });
  }
}
