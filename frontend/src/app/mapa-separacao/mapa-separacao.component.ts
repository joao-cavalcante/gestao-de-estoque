import { Component, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { OqIconComponent, OqIconName } from '../shared/icons/oq-icon.component';
import { OqSpinnerComponent } from '../shared/icons/oq-spinner.component';
import { MapaSeparacaoService } from './mapa-separacao.service';
import { CategoriaSeparacaoDto, MapaSeparacaoDto, formatarPeso, formatarQtd } from './mapa-separacao.model';

const ICONE_CATEGORIA: Record<string, OqIconName> = {
  '1': 'seco',
  '2': 'refrigerado',
  '3': 'congelado',
  '0': 'circle-alert',
};

/**
 * Mapa de Separação por Ordem de Carga — porte do Dashboard HTML5/JSP que
 * substituiu o iReport 513 no Sankhya (ver backend MapaSeparacaoService).
 * Mesma regra de negócio (classificação por TGFPRO.AD_TIPOSEPARACAO, quebra
 * por nota → categoria), identidade visual do WMS (tokens de
 * styles.scss, ícones seco/refrigerado/congelado já usados na Fila de
 * Tarefas) em vez do CSS solto do JSP original.
 *
 * Consulta ao vivo (sem cache/mirror) — o operador digita a Ordem de Carga,
 * consulta e imprime via window.print(), igual ao fluxo original no Sankhya.
 */
@Component({
  selector: 'app-mapa-separacao',
  standalone: true,
  imports: [FormsModule, OqIconComponent, OqSpinnerComponent],
  templateUrl: './mapa-separacao.component.html',
  styleUrl: './mapa-separacao.component.scss',
})
export class MapaSeparacaoComponent {
  private readonly service = inject(MapaSeparacaoService);

  ordemCarga: number | null = null;

  readonly dados = signal<MapaSeparacaoDto | null>(null);
  readonly carregando = signal(false);
  readonly erro = signal<string | null>(null);

  readonly formatarQtd = formatarQtd;
  readonly formatarPeso = formatarPeso;

  iconeCategoria(codigo: string): OqIconName {
    return ICONE_CATEGORIA[codigo] ?? 'circle-alert';
  }

  consultar(): void {
    const oc = this.ordemCarga;
    if (!oc || oc <= 0) {
      this.erro.set('Informe uma Ordem de Carga numérica válida.');
      return;
    }

    this.carregando.set(true);
    this.erro.set(null);
    this.dados.set(null);

    this.service.consultar(oc).subscribe({
      next: (r) => {
        this.dados.set(r);
        this.carregando.set(false);
      },
      error: (err) => {
        this.erro.set(err?.error?.erro ?? 'Falha ao consultar a Ordem de Carga.');
        this.carregando.set(false);
      },
    });
  }

  limpar(): void {
    this.ordemCarga = null;
    this.dados.set(null);
    this.erro.set(null);
  }

  imprimir(): void {
    const oc = this.dados()?.ordemCarga;
    if (!oc) return;

    const tituloOriginal = document.title;
    document.title = `O.C. ${oc}`;
    const restaurar = () => {
      document.title = tituloOriginal;
      window.removeEventListener('afterprint', restaurar);
    };
    window.addEventListener('afterprint', restaurar, { once: true });
    setTimeout(() => window.print(), 50);
  }

  trackCategoria(_index: number, categoria: CategoriaSeparacaoDto): string {
    return categoria.codigo;
  }
}
