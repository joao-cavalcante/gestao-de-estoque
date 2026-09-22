import { Component, OnInit, inject, signal } from '@angular/core';
import { ActivatedRoute } from '@angular/router';
import { forkJoin } from 'rxjs';
import { catchError, switchMap } from 'rxjs/operators';
import { AuthService } from '../auth/auth.service';
import { SeparacaoService } from '../separacao/separacao.service';
import { EtiquetaDados } from '../separacao/separacao.model';
import { OqSpinnerComponent } from '../shared/icons/oq-spinner.component';
import { rotuloTipoSeparacao } from '../fila-tarefas/tarefa.model';

/**
 * Página de impressão de etiquetas de volume (15x10 cm, uma por volume).
 * Renderizada no navegador e impressa via window.print() — sem PDF no backend.
 * Espelha src/templates/template-etiqueta.html do fila-de-conferencia.
 *
 * `grupos`: normalmente 1 elemento (nota inteira OU uma etapa específica,
 * quando aberto com `?etapa=` — fluxo ao vivo, popup de fim de etapa). Mas
 * ao REIMPRIMIR (sem `?etapa=`, vindo da tela "Impressão de Etiquetas") uma
 * conferência que foi feita por etapa, vira 1 grupo por etapa concluída —
 * reproduz exatamente o que já saiu impresso durante a separação (nome da
 * etapa em vez de "X de Y"), em vez de reconsolidar tudo como se fosse uma
 * nota inteira sem etapa nenhuma.
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

  /** Etiqueta POR ETAPA: só os volumes da etapa, sem "de N" (o total da nota não é o que importa aqui). */
  porEtapa(grupo: EtiquetaDados): boolean {
    return grupo.etapaTipo != null;
  }

  rotuloEtapa(grupo: EtiquetaDados): string {
    return grupo.etapaTipo != null ? rotuloTipoSeparacao(grupo.etapaTipo) : '';
  }

  /** Volumes a imprimir do grupo: faixa acumulada da etapa (ex.: 3..6) ou [1..totalVolumes] da nota inteira. */
  volumesDoGrupo(grupo: EtiquetaDados): number[] {
    const ini = grupo.volumeInicial ?? 1;
    const total = grupo.totalVolumes ?? 0;
    return Array.from({ length: total }, (_, i) => ini + i);
  }

  /** 5 dígitos do número único (NUNOTA), zero à esquerda — igual ao JRXML do legado. */
  digitosNumero(grupo: EtiquetaDados): string[] {
    return (grupo.numeroNota ?? '').padStart(5, '0').slice(-5).split('');
  }

  /** 2 dígitos do nº do volume atual (01, 02, …). */
  digitos2(v: number): string[] {
    return String(v).padStart(2, '0').slice(-2).split('');
  }

  /** 2 dígitos do total de volumes exibido no grupo. */
  digitosTotal(grupo: EtiquetaDados): string[] {
    return String(grupo.totalExibicao ?? grupo.totalVolumes ?? 0).padStart(2, '0').slice(-2).split('');
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
