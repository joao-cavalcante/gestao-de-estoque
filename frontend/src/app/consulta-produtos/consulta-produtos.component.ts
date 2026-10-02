import { Component, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { ConsultaProdutosService } from './consulta-produtos.service';
import { ProdutoEstoque } from './consulta-produtos.model';
import { OqSkeletonComponent } from '../shared/oq-skeleton/oq-skeleton.component';
import { OqIconComponent } from '../shared/icons/oq-icon.component';

const FORMATO_QTD = new Intl.NumberFormat('pt-BR', { maximumFractionDigits: 3 });

/**
 * Consulta de Produtos — acha o produto no catálogo local (código, código de barras, descrição,
 * marca ou referência) e mostra o saldo AO VIVO da instância Estoque do Sankhya, total e por
 * empresa / local / lote. Mesmo layout de lista das outras telas (styles/_lista-layout.scss).
 */
@Component({
  selector: 'app-consulta-produtos',
  standalone: true,
  imports: [FormsModule, OqSkeletonComponent, OqIconComponent],
  templateUrl: './consulta-produtos.component.html',
  styleUrl: './consulta-produtos.component.scss',
})
export class ConsultaProdutosComponent {
  private readonly service = inject(ConsultaProdutosService);

  busca = '';
  /** Termo da última consulta feita (null = nenhuma ainda). */
  readonly termoConsultado = signal<string | null>(null);
  readonly produtos = signal<ProdutoEstoque[]>([]);
  readonly limitado = signal(false);
  readonly erroEstoque = signal<string | null>(null);
  readonly carregando = signal(false);
  readonly erro = signal<string | null>(null);
  /** Produtos com o detalhe por local aberto. */
  readonly abertos = signal<ReadonlySet<number>>(new Set());

  consultar(): void {
    const termo = this.busca.trim();
    if (termo.length < 2) {
      this.erro.set('Digite pelo menos 2 caracteres.');
      return;
    }
    this.carregando.set(true);
    this.erro.set(null);
    this.service.consultar(termo).subscribe({
      next: (res) => {
        this.produtos.set(res.produtos);
        this.limitado.set(res.limitado);
        this.erroEstoque.set(res.erroEstoque);
        this.termoConsultado.set(termo);
        // Um resultado só: já abre o detalhe (caso típico de bipar o código de barras).
        this.abertos.set(new Set(res.produtos.length === 1 ? [res.produtos[0].codprod] : []));
        this.carregando.set(false);
      },
      error: (e) => {
        this.erro.set(e?.error?.erro ?? 'Falha ao consultar produtos.');
        this.carregando.set(false);
      },
    });
  }

  aberto(codprod: number): boolean {
    return this.abertos().has(codprod);
  }

  alternar(codprod: number): void {
    const proximo = new Set(this.abertos());
    if (proximo.has(codprod)) proximo.delete(codprod);
    else proximo.add(codprod);
    this.abertos.set(proximo);
  }

  qtd(valor: number): string {
    return FORMATO_QTD.format(valor);
  }

  /** Complemento + marca, abaixo da descrição. */
  subtitulo(p: ProdutoEstoque): string {
    return [p.complemento, p.marca].map((v) => v?.trim()).filter((v) => !!v).join(' · ');
  }

  controle(valor: string | null): string {
    return valor?.trim() || '—';
  }
}
