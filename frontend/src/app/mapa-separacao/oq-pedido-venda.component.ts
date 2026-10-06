import { Component, Input } from '@angular/core';
import { PedidoVenda } from './mapa-separacao.model';

type Linha = Record<string, string | null>;

/**
 * Pedido de Venda — porte HTML do relatório Jasper PEDIDO_VENDA_NEGRI_UNICO_V8.jrxml (mesma SQL, no backend
 * PedidoVendaService). Impresso junto do Mapa de Separação, um por Nº Único, em A4 paisagem (página nomeada
 * `pedido-venda`). Valores chegam como texto do Sankhya (ponto decimal, datas "ddMMyyyy HH:mm:ss").
 */
@Component({
  selector: 'oq-pedido-venda',
  standalone: true,
  templateUrl: './oq-pedido-venda.component.html',
  styleUrl: './oq-pedido-venda.component.scss',
})
export class OqPedidoVendaComponent {
  @Input({ required: true }) pedido!: PedidoVenda;

  readonly logo = 'assets/logos/pedido-venda-negri.png';

  /**
   * Itens em ordem alfabética de "Descrição - Complemento" (regra de todas as telas — o Jasper ordenava pela
   * SEQUENCIA). Coluna "Item" continua mostrando a sequência do pedido.
   */
  get itensOrdenados(): Linha[] {
    const nome = (l: Linha) => [l['DESCRPROD'], l['COMPLDESC']].filter((v) => !!v && v.trim()).join(' - ');
    return [...(this.pedido.itens ?? [])].sort((a, b) =>
      nome(a).localeCompare(nome(b), 'pt-BR', { sensitivity: 'base', numeric: true }),
    );
  }

  c(campo: string): string {
    return this.pedido.cabecalho?.[campo] ?? '';
  }

  v(l: Linha, campo: string): string {
    return l[campo] ?? '';
  }

  /** "4493.594" → "4.493,59" (casas do relatório: 2 em valores, 4 em qtd/unitário, 3 em peso). */
  num(valor: string | null | undefined, casas = 2): string {
    if (valor == null || valor === '') return '';
    const n = Number(valor);
    if (!Number.isFinite(n)) return valor;
    return n.toLocaleString('pt-BR', { minimumFractionDigits: casas, maximumFractionDigits: casas });
  }

  /** "05102026 00:00:00" (DbExplorer) ou ISO → "05/10/2026". */
  data(valor: string | null | undefined): string {
    if (!valor) return '';
    const m = /^(\d{2})(\d{2})(\d{4})/.exec(valor);
    if (m) return `${m[1]}/${m[2]}/${m[3]}`;
    const iso = /^(\d{4})-(\d{2})-(\d{2})/.exec(valor);
    return iso ? `${iso[3]}/${iso[2]}/${iso[1]}` : valor;
  }

  /** CNPJ (14) ou CPF (11) com máscara; outro tamanho volta como veio. */
  doc(valor: string | null | undefined): string {
    const d = (valor ?? '').replace(/\D/g, '');
    if (d.length === 14) return d.replace(/^(\d{2})(\d{3})(\d{3})(\d{4})(\d{2})$/, '$1.$2.$3/$4-$5');
    if (d.length === 11) return d.replace(/^(\d{3})(\d{3})(\d{3})(\d{2})$/, '$1.$2.$3-$4');
    return valor ?? '';
  }

  cep(valor: string | null | undefined): string {
    const d = (valor ?? '').replace(/\D/g, '');
    return d.length === 8 ? `${d.slice(0, 5)}-${d.slice(5)}` : (valor ?? '');
  }

  enderecoEmpresa(): string {
    return `${this.c('EMP_BAIRRO')} - ${this.c('EMP_CIDADE')}/${this.c('EMP_UF')} - CEP: ${this.cep(this.c('EMP_CEP'))}`;
  }

  enderecoCliente(): string {
    return `${this.c('ENDERECO')} - ${this.c('NOMEBAI')} - ${this.c('NOMECID')}/${this.c('UF')} - CEP: ${this.cep(this.c('CEP'))}`;
  }

  /** Controle " " (sem lote) do Sankhya não aparece. */
  controle(l: Linha): string {
    return (l['CONTROLE'] ?? '').trim();
  }
}
