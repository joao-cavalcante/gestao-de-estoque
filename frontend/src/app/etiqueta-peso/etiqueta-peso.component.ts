import { Component, OnInit, computed, inject, signal } from '@angular/core';
import { ActivatedRoute, Router } from '@angular/router';
import { AuthService } from '../auth/auth.service';
import { SeparacaoService } from '../separacao/separacao.service';
import { EtiquetaPeso } from '../separacao/separacao.model';
import { OqSpinnerComponent } from '../shared/icons/oq-spinner.component';
import { ET_COMPONENTES, classeTamanhoTexto } from '../shared/etiqueta/et-componentes';

/**
 * Etiqueta térmica de produto pesável (100 x 100 mm, uma por item pesável conferido).
 * Mesma família visual da etiqueta de volume: cabeçalho, bloco CLIENTE e
 * caixas de dígito vêm de shared/etiqueta.
 * O peso vem de separacao_itens.qtd_conferida_local (já em KG) — aqui só se
 * formata e imprime; nada de conversão. Abrir esta página CRIA a etiqueta (nº
 * único) na primeira vez e REIMPRIME o mesmo número nas seguintes. Número novo
 * só com `nova=true` (botão explícito). Impressão via window.print().
 */
@Component({
  selector: 'app-etiqueta-peso',
  standalone: true,
  imports: [OqSpinnerComponent, ...ET_COMPONENTES],
  templateUrl: './etiqueta-peso.component.html',
  styleUrl: './etiqueta-peso.component.scss',
})
export class EtiquetaPesoComponent implements OnInit {
  private readonly route = inject(ActivatedRoute);
  private readonly router = inject(Router);
  private readonly auth = inject(AuthService);
  private readonly separacao = inject(SeparacaoService);

  readonly etiquetas = signal<EtiquetaPeso[]>([]);
  readonly erro = signal<string | null>(null);
  readonly carregando = signal(true);
  readonly reimpressao = computed(() => this.etiquetas().some((e) => e.reimpressao));

  /** Data/hora da impressão — mesma pra todas as etiquetas da página. */
  readonly agora = new Date().toLocaleString('pt-BR');
  readonly classeTexto = classeTamanhoTexto;

  get tenant(): string {
    return this.auth.obterTenantSlug() ?? '';
  }

  /** "57797-56" (NUNOTA-OC); sem OC, só o NUNOTA. */
  numeroUnico(e: EtiquetaPeso): string {
    return e.ordemCarga ? `${e.nunota}-${e.ordemCarga}` : String(e.nunota);
  }

  /** 15.640 -> "15,640" (sempre 3 casas — é o que a balança/o Sankhya trabalham). */
  formatarPeso(peso: string): string {
    return Number(peso).toLocaleString('pt-BR', { minimumFractionDigits: 3, maximumFractionDigits: 3 });
  }

  ngOnInit(): void {
    const sessaoId = this.route.snapshot.paramMap.get('sessaoId') ?? '';
    const q = this.route.snapshot.queryParamMap;
    const codprod = q.get('codprod');
    const nova = q.get('nova') === 'true';
    const etapa = q.get('etapa');


    this.separacao
      .etiquetasPeso(this.tenant, sessaoId, {
        codprod: codprod ? Number(codprod) : undefined,
        controle: q.get('controle') ?? undefined,
        nova,
        etapa: etapa ? Number(etapa) : undefined,
      })
      .subscribe({
        next: (r) => {
          this.etiquetas.set(r.etiquetas);
          this.carregando.set(false);
          // Tira `nova` da URL: recarregar a página NÃO pode gerar outro número.
          if (nova) {
            this.router.navigate([], { queryParams: { nova: null }, queryParamsHandling: 'merge', replaceUrl: true });
          }
          if (r.etiquetas.length > 0) setTimeout(() => window.print(), 300);
        },
        error: (err) => {
          this.erro.set(err?.error?.erro ?? 'Falha ao gerar a etiqueta de peso.');
          this.carregando.set(false);
        },
      });
  }

  imprimir(): void {
    window.print();
  }

  /** Número novo pra UM item — só por pedido explícito, com confirmação. */
  gerarNova(e: EtiquetaPeso): void {
    const ok = window.confirm(
      `Gerar uma NOVA etiqueta para "${e.produto}"?\nA atual continua registrada no sistema; sai uma etiqueta nova.`,
    );
    if (!ok) return;
    this.router
      .navigate([], { queryParams: { codprod: e.codprod, controle: e.controle, nova: true } })
      .then(() => window.location.reload());
  }
}
