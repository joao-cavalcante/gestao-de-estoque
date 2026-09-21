import { Component, OnInit, inject, signal } from '@angular/core';
import { ActivatedRoute } from '@angular/router';
import { AuthService } from '../auth/auth.service';
import { SeparacaoService } from '../separacao/separacao.service';
import { EtiquetaDados } from '../separacao/separacao.model';
import { OqSpinnerComponent } from '../shared/icons/oq-spinner.component';
import { rotuloTipoSeparacao } from '../fila-tarefas/tarefa.model';

/**
 * Página de impressão de etiquetas de volume (15x10 cm, uma por volume).
 * Renderizada no navegador e impressa via window.print() — sem PDF no backend.
 * Espelha src/templates/template-etiqueta.html do fila-de-conferencia.
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

  readonly dados = signal<EtiquetaDados | null>(null);
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

  /** Etiqueta POR ETAPA (?etapa=): só os volumes da etapa, sem "de N" (o total da nota ainda não é conhecido). */
  get porEtapa(): boolean {
    return this.dados()?.volumeInicial != null;
  }

  get rotuloEtapa(): string {
    const t = this.dados()?.etapaTipo;
    return t != null ? rotuloTipoSeparacao(t) : '';
  }

  /** Volumes a imprimir: faixa acumulada da etapa (ex.: 3..6) ou [1..totalVolumes] da nota inteira. */
  get volumes(): number[] {
    const d = this.dados();
    const ini = d?.volumeInicial ?? 1;
    const total = d?.totalVolumes ?? 0;
    return Array.from({ length: total }, (_, i) => ini + i);
  }

  /** 5 dígitos do número único (NUNOTA), zero à esquerda — igual ao JRXML do legado. */
  get digitosNumero(): string[] {
    return (this.dados()?.numeroNota ?? '').padStart(5, '0').slice(-5).split('');
  }

  /** 2 dígitos do nº do volume atual (01, 02, …). */
  digitos2(v: number): string[] {
    return String(v).padStart(2, '0').slice(-2).split('');
  }

  /** 2 dígitos do total de volumes. */
  get digitosTotal(): string[] {
    return String(this.dados()?.totalVolumes ?? 0).padStart(2, '0').slice(-2).split('');
  }

  ngOnInit(): void {
    const sessaoId = this.route.snapshot.paramMap.get('sessaoId');
    const nunotaParam = this.route.snapshot.queryParamMap.get('nunota');
    const etapaParam = this.route.snapshot.queryParamMap.get('etapa');
    const req = sessaoId
      ? this.separacao.dadosEtiqueta(this.tenant, sessaoId, etapaParam ? Number(etapaParam) : undefined)
      : this.separacao.dadosEtiquetaPorNota(this.tenant, Number(nunotaParam));

    if (this.tenant) this.logoSrc.set(`/assets/logos/${this.tenant}.png`);

    req.subscribe({
      next: (d) => {
        this.dados.set(d);
        this.carregando.set(false);
        // dá um tick pro DOM renderizar antes de abrir o diálogo de impressão
        setTimeout(() => window.print(), 300);
      },
      error: (err) => {
        this.erro.set(err?.error?.erro ?? 'Falha ao carregar os dados da etiqueta.');
        this.carregando.set(false);
      },
    });
  }

  imprimir(): void {
    window.print();
  }
}
