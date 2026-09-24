import { Component, OnInit, inject, signal } from '@angular/core';
import { ActivatedRoute } from '@angular/router';
import { forkJoin } from 'rxjs';
import { catchError, switchMap } from 'rxjs/operators';
import { AuthService } from '../auth/auth.service';
import { SeparacaoService } from '../separacao/separacao.service';
import { EtiquetaDados } from '../separacao/separacao.model';
import { OqSpinnerComponent } from '../shared/icons/oq-spinner.component';
import { ET_COMPONENTES } from '../shared/etiqueta/et-componentes';

/**
 * Página de impressão de etiquetas de volume (100 x 100 mm, uma por volume).
 * Cabeçalho, bloco CLIENTE, caixas de dígito e código de barras são os
 * componentes de shared/etiqueta — os mesmos da etiqueta de peso.
 * Renderizada no navegador e impressa via window.print() — sem PDF no backend.
 * Espelha src/templates/template-etiqueta.html do fila-de-conferencia.
 *
 * O nome da etapa NÃO aparece na etiqueta (pedido explícito) — é sempre
 * "X de Y". `grupos`: normalmente 1 elemento (nota inteira OU uma etapa
 * específica, quando aberto com `?etapa=` — fluxo ao vivo, popup de fim de
 * etapa). Mas ao REIMPRIMIR (sem `?etapa=`, vindo da tela "Impressão de
 * Etiquetas") uma conferência que foi feita por etapa, vira 1 grupo por
 * etapa concluída — reproduz a mesma NUMERAÇÃO que já saiu impressa
 * durante a separação (X de Y da etapa, não da nota inteira), em vez de
 * reconsolidar tudo como se fosse uma nota inteira sem etapa nenhuma.
 */
@Component({
  selector: 'app-etiquetas',
  standalone: true,
  imports: [OqSpinnerComponent, ...ET_COMPONENTES],
  templateUrl: './etiquetas.component.html',
  styleUrl: './etiquetas.component.scss',
})
export class EtiquetasComponent implements OnInit {
  private readonly route = inject(ActivatedRoute);
  private readonly auth = inject(AuthService);
  private readonly separacao = inject(SeparacaoService);

  readonly grupos = signal<EtiquetaDados[]>([]);
  readonly erro = signal<string | null>(null);
  readonly carregando = signal(true);

  /** "23/09/2026, 18:15:44" */
  readonly agora = new Date().toLocaleString('pt-BR');

  get tenant(): string {
    return this.auth.obterTenantSlug() ?? '';
  }

  get totalVolumesGeral(): number {
    return this.grupos().reduce((acc, g) => acc + g.totalVolumes, 0);
  }

  /** Volumes a imprimir do grupo: faixa acumulada da etapa (ex.: 3..6) ou [1..totalVolumes] da nota inteira. */
  volumesDoGrupo(grupo: EtiquetaDados): number[] {
    const ini = grupo.volumeInicial ?? 1;
    const total = grupo.totalVolumes ?? 0;
    return Array.from({ length: total }, (_, i) => ini + i);
  }

  /** "57758-56" (NUNOTA-OC); sem OC, só o NUNOTA. Vira caixas no <oq-et-caixas>. */
  numeroUnico(grupo: EtiquetaDados): string {
    const nunota = grupo.nunota || Number(grupo.numeroNota) || 0;
    return grupo.ordemCarga ? `${nunota}-${grupo.ordemCarga}` : String(nunota);
  }

  private d2(v: number): string {
    return String(v).padStart(2, '0');
  }

  /**
   * "03/05" — o total só quando é CONHECIDO: nota inteira, ou recontagem (total
   * acumulado). Etiqueta por ETAPA não sabe quantos volumes as outras etapas
   * ainda vão ter, então mostra só "03".
   */
  textoVolume(grupo: EtiquetaDados, v: number): string {
    const total = grupo.totalExibicao || (grupo.etapaTipo != null ? null : grupo.totalVolumes || null);
    return total ? `${this.d2(v)}/${this.d2(total)}` : this.d2(v);
  }

  /** Conteúdo do código de barras: Nº Único + volume ("57758-56-03"). */
  textoBarras(grupo: EtiquetaDados, v: number): string {
    return `${this.numeroUnico(grupo)}-${this.d2(v)}`;
  }

  ngOnInit(): void {
    const sessaoId = this.route.snapshot.paramMap.get('sessaoId');
    const nunotaParam = this.route.snapshot.queryParamMap.get('nunota');
    const etapaParam = this.route.snapshot.queryParamMap.get('etapa');


    if (!sessaoId) {
      // Impressão por NUNOTA direta (sem sessão local) — sempre nota inteira, sem etapa.
      this.separacao.dadosEtiquetaPorNota(this.tenant, Number(nunotaParam)).subscribe(this.aoCarregar());
      return;
    }

    if (etapaParam) {
      // Aberto explicitamente pra uma etapa (fluxo ao vivo — popup de fim de etapa/painel final).
      this.separacao.dadosEtiqueta(this.tenant, sessaoId, Number(etapaParam)).subscribe(this.aoCarregar());
      return;
    }

    // Reimpressão sem etapa explícita (tela "Impressão de Etiquetas"): reproduz o
    // formato ORIGINAL se a conferência foi segmentada (1 grupo por etapa, mesmo
    // rótulo que já saiu impresso na hora) — senão, nota inteira, como sempre foi.
    this.separacao
      .buscarEtapas(this.tenant, sessaoId)
      .pipe(
        switchMap((etapas) => {
          if (etapas.length === 0) return this.separacao.dadosEtiqueta(this.tenant, sessaoId);
          return forkJoin(etapas.map((e) => this.separacao.dadosEtiqueta(this.tenant, sessaoId, e.tipoSeparacao)));
        }),
        // buscarEtapas indisponível (ex.: tenant sem o módulo) não pode travar a
        // reimpressão — cai pro comportamento padrão de sempre (nota inteira).
        catchError(() => this.separacao.dadosEtiqueta(this.tenant, sessaoId)),
      )
      .subscribe(this.aoCarregar());
  }

  private aoCarregar() {
    return {
      next: (resultado: EtiquetaDados | EtiquetaDados[]) => {
        const lista = (Array.isArray(resultado) ? resultado : [resultado]).filter((g) => g.totalVolumes > 0);
        this.grupos.set(lista);
        this.carregando.set(false);
        // dá um tick pro DOM renderizar antes de abrir o diálogo de impressão
        if (lista.length > 0) setTimeout(() => window.print(), 300);
      },
      error: (err: { error?: { erro?: string } }) => {
        this.erro.set(err?.error?.erro ?? 'Falha ao carregar os dados da etiqueta.');
        this.carregando.set(false);
      },
    };
  }

  imprimir(): void {
    window.print();
  }
}
