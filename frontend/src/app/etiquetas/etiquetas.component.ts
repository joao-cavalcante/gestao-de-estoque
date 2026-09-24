import { Component, OnInit, inject, signal } from '@angular/core';
import { ActivatedRoute } from '@angular/router';
import { forkJoin } from 'rxjs';
import { catchError, switchMap } from 'rxjs/operators';
import { AuthService } from '../auth/auth.service';
import { SeparacaoService } from '../separacao/separacao.service';
import { EtiquetaDados } from '../separacao/separacao.model';
import { OqSpinnerComponent } from '../shared/icons/oq-spinner.component';
import { code128B, larguraModulos } from '../shared/code128';

/**
 * Página de impressão de etiquetas de volume (15x10 cm, uma por volume).
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
  imports: [OqSpinnerComponent],
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

  /** Logo do cliente — o arquivo fica em frontend/src/assets/logos/<slug>.png|jpg. */
  readonly logoSrc = signal<string | null>(null);
  private logoTentouJpg = false;

  get tenant(): string {
    return this.auth.obterTenantSlug() ?? '';
  }

  onLogoErro(): void {
    if (!this.logoTentouJpg) {
      this.logoTentouJpg = true;
      this.logoSrc.set(`/assets/logos/${this.tenant}.jpg`);
    } else {
      this.logoSrc.set(null); // sem logo pra esse tenant — some, sem erro
    }
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

  /**
   * Caixas do Nº Único: um caractere por caixa, "NUNOTA-OC" (traço vira separador,
   * não caixa). Sem OC, só o NUNOTA. Quantidade dinâmica — sem caixa vazia.
   */
  caixasUnico(grupo: EtiquetaDados): string[] {
    return this.numeroUnico(grupo).split('');
  }

  private numeroUnico(grupo: EtiquetaDados): string {
    const nunota = grupo.nunota || Number(grupo.numeroNota) || 0;
    return grupo.ordemCarga ? `${nunota}-${grupo.ordemCarga}` : String(nunota);
  }

  /** 2 dígitos (01, 02, …) — cresce sozinho a partir de 100. */
  digitos2(v: number): string[] {
    return String(v).padStart(2, '0').split('');
  }

  /**
   * Total de volumes pra "03 / 05" — só quando é CONHECIDO: nota inteira, ou
   * recontagem (total acumulado). Etiqueta por ETAPA não sabe quantos volumes as
   * outras etapas ainda vão ter, então mostra só o número do volume.
   */
  totalConhecido(grupo: EtiquetaDados): number | null {
    if (grupo.totalExibicao) return grupo.totalExibicao;
    if (grupo.etapaTipo != null) return null;
    return grupo.totalVolumes || null;
  }

  /** Conteúdo do código de barras: Nº Único + volume ("57758-56-03"). */
  textoBarras(grupo: EtiquetaDados, v: number): string {
    return `${this.numeroUnico(grupo)}-${this.digitos2(v).join('')}`;
  }

  private readonly cacheBarras = new Map<string, { total: number; retangulos: { x: number; w: number }[] }>();

  /** Code 128 em retângulos (unidade = módulo). Cacheado: o template chama várias vezes por etiqueta. */
  barras(grupo: EtiquetaDados, v: number): { total: number; retangulos: { x: number; w: number }[] } {
    const texto = this.textoBarras(grupo, v);
    const salvo = this.cacheBarras.get(texto);
    if (salvo) return salvo;
    const larguras = code128B(texto);
    const retangulos: { x: number; w: number }[] = [];
    let x = 0;
    larguras.forEach((w, i) => {
      if (i % 2 === 0) retangulos.push({ x, w }); // índice par = barra, ímpar = espaço
      x += w;
    });
    const r = { total: larguraModulos(larguras), retangulos };
    this.cacheBarras.set(texto, r);
    return r;
  }

  ngOnInit(): void {
    const sessaoId = this.route.snapshot.paramMap.get('sessaoId');
    const nunotaParam = this.route.snapshot.queryParamMap.get('nunota');
    const etapaParam = this.route.snapshot.queryParamMap.get('etapa');

    if (this.tenant) this.logoSrc.set(`/assets/logos/${this.tenant}.png`);

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
