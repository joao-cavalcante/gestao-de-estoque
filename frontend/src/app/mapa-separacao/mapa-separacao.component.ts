import { Component, OnInit, inject, signal } from '@angular/core';
import { NgTemplateOutlet } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { OqIconComponent, OqIconName } from '../shared/icons/oq-icon.component';
import { OqSpinnerComponent } from '../shared/icons/oq-spinner.component';
import { MapaSeparacaoService } from './mapa-separacao.service';
import { CategoriaSeparacaoDto, MapaSeparacaoDto, OrdemCargaResumoDto, formatarPeso, formatarQtd } from './mapa-separacao.model';

const ICONE_CATEGORIA: Record<string, OqIconName> = {
  '1': 'seco',
  '2': 'refrigerado',
  '3': 'congelado',
  '0': 'circle-alert',
};

/**
 * Mapa de Separação por Ordem de Carga — porte do Dashboard HTML5/JSP que
 * substituiu o iReport 513 no Sankhya (ver backend MapaSeparacaoService).
 * Classificação por TGFPRO.AD_TIPOSEPARACAO como no original, mas SEM a
 * quebra por nota (economia de papel): não pesáveis saem somados na OC
 * inteira (uma folha por categoria) e só os pesáveis ficam segregados por
 * parceiro, com ícone de balança. Identidade visual do WMS (tokens de
 * styles.scss, ícones seco/refrigerado/congelado já usados na Fila de
 * Tarefas) em vez do CSS solto do JSP original.
 *
 * TELA DE CONTROLE, não busca solta: abre já mostrando as Ordens de Carga
 * ABERTAS (SITUACAO='A' — ainda precisam ser separadas; "fechada" já foi
 * processada/embarcada) como cards (mesmo idioma visual de Fila de
 * Tarefas/Liberação de Corte/Impressão de Etiquetas) — o operador vê de
 * cara quantas tem pra separar, clica na que quer e vai direto pro
 * relatório/impressão. Busca ao vivo, sem cache/mirror.
 */
@Component({
  selector: 'app-mapa-separacao',
  standalone: true,
  imports: [FormsModule, OqIconComponent, OqSpinnerComponent, NgTemplateOutlet],
  templateUrl: './mapa-separacao.component.html',
  styleUrl: './mapa-separacao.component.scss',
})
export class MapaSeparacaoComponent implements OnInit {
  private readonly service = inject(MapaSeparacaoService);

  readonly abertas = signal<OrdemCargaResumoDto[]>([]);
  readonly carregandoAbertas = signal(true);
  readonly erroAbertas = signal<string | null>(null);
  filtroLista = '';
  /** 'todas' | 'pendentes' (ainda tem nota não conferida) | 'concluidas' (100%) — ajuda a localizar rápido numa lista grande. */
  filtroStatus: 'todas' | 'pendentes' | 'concluidas' = 'todas';

  readonly dados = signal<MapaSeparacaoDto | null>(null);
  readonly carregando = signal(false);
  readonly erro = signal<string | null>(null);

  /** Fallback pra OC que ainda não está aberta na lista (ex.: acabou de abrir no Sankhya), ou pra quando a lista falha ao carregar. */
  ordemCargaManual: number | null = null;

  readonly formatarQtd = formatarQtd;
  readonly formatarPeso = formatarPeso;

  get listaFiltrada(): OrdemCargaResumoDto[] {
    const termo = this.filtroLista.trim().toLowerCase();
    const status = this.filtroStatus;
    return this.abertas().filter((oc) => {
      const passaBusca =
        !termo ||
        String(oc.ordemCarga).includes(termo) ||
        oc.placa?.toLowerCase().includes(termo) ||
        oc.nomeMotorista?.toLowerCase().includes(termo);

      const concluida = this.ocConcluida(oc);
      const passaStatus = status === 'todas' || (status === 'concluidas' ? concluida : !concluida);

      return passaBusca && passaStatus;
    });
  }

  iconeCategoria(codigo: string): OqIconName {
    return ICONE_CATEGORIA[codigo] ?? 'circle-alert';
  }

  /** 100% conferida — só faz sentido pra OC que já tem pelo menos 1 nota rastreada (ver totalNotas). */
  ocConcluida(oc: OrdemCargaResumoDto): boolean {
    return oc.totalNotas > 0 && oc.notasConferidas === oc.totalNotas;
  }

  progressoPct(oc: OrdemCargaResumoDto): number {
    if (oc.totalNotas <= 0) return 0;
    return Math.min(100, Math.round((oc.notasConferidas / oc.totalNotas) * 100));
  }

  ngOnInit(): void {
    this.carregarAbertas();
  }

  carregarAbertas(): void {
    this.carregandoAbertas.set(true);
    this.erroAbertas.set(null);
    this.service.listarAbertas().subscribe({
      next: (lista) => {
        this.abertas.set(lista);
        this.carregandoAbertas.set(false);
      },
      error: (err) => {
        this.abertas.set([]);
        this.carregandoAbertas.set(false);
        this.erroAbertas.set(err?.error?.erro ?? 'Falha ao carregar as Ordens de Carga abertas.');
      },
    });
  }

  consultarManual(): void {
    const oc = this.ordemCargaManual;
    if (!oc || oc <= 0) {
      this.erro.set('Informe uma Ordem de Carga numérica válida.');
      return;
    }
    this.consultar(oc);
  }

  consultar(ordemCarga: number): void {
    this.carregando.set(true);
    this.erro.set(null);
    this.dados.set(null);

    this.service.consultar(ordemCarga).subscribe({
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

  /** Volta pro painel — não recarrega a lista (evita ida desnecessária ao Sankhya); "Atualizar" faz isso à parte. */
  voltar(): void {
    this.dados.set(null);
    this.erro.set(null);
    this.ordemCargaManual = null;
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
