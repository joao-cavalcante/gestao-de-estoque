import { Component, OnInit, inject, signal } from '@angular/core';
import { ActivatedRoute } from '@angular/router';
import { ET_COMPONENTES, classeTamanhoTexto } from '../shared/etiqueta/et-componentes';
import { OqSpinnerComponent } from '../shared/icons/oq-spinner.component';
import { Palete, PaletesService, qtdPalete } from '../paletes/paletes.service';

/**
 * Etiqueta de PALETE (LPN) do recebimento — endereçamento etapa 4a, 27/11/2026. Mesma família das etiquetas de
 * volume e de peso (shared/etiqueta, 100 x 100 mm, window.print()). Código de barras = código do palete (UMA, "P" +
 * 7 dígitos), que o coletor bipa na armazenagem. Normal: produto + quantidade em destaque. Misto: lista dos produtos.
 *  /etiquetas-palete?ids=a,b — os paletes que acabaram de ser fechados (imprime sozinho);
 *  /etiquetas-palete/compra/:nunota — reimpressão de todos os paletes da compra.
 */
@Component({
  selector: 'app-etiqueta-palete',
  standalone: true,
  imports: [...ET_COMPONENTES, OqSpinnerComponent],
  templateUrl: './etiqueta-palete.component.html',
  styleUrl: './etiqueta-palete.component.scss',
})
export class EtiquetaPaleteComponent implements OnInit {
  private readonly route = inject(ActivatedRoute);
  private readonly api = inject(PaletesService);

  readonly paletes = signal<Palete[]>([]);
  readonly carregando = signal(true);
  readonly erro = signal<string | null>(null);
  readonly reimpressao = signal(false);
  readonly qtd = qtdPalete;
  readonly classeTexto = classeTamanhoTexto;
  readonly agora = new Date().toLocaleString('pt-BR');

  ngOnInit(): void {
    const nunota = Number(this.route.snapshot.paramMap.get('nunota'));
    const ids = (this.route.snapshot.queryParamMap.get('ids') ?? '').split(',').filter((x) => x);
    this.reimpressao.set(!!nunota);
    const req = nunota ? this.api.porNunota(nunota) : this.api.porIds(ids);
    req.subscribe({
      next: (l) => {
        this.paletes.set(l);
        this.carregando.set(false);
        if (l.length > 0 && !nunota) {
          setTimeout(() => window.print(), 300);
          this.api.marcarImpresso(l.map((p) => p.id)).subscribe({ error: () => undefined });
        }
      },
      error: (e) => {
        this.erro.set(e?.error?.erro ?? 'Não foi possível carregar os paletes.');
        this.carregando.set(false);
      },
    });
  }

  imprimir(): void {
    window.print();
    const l = this.paletes();
    if (l.length) this.api.marcarImpresso(l.map((p) => p.id)).subscribe({ error: () => undefined });
  }

  /** Quantidade de cada item do misto (até 6 linhas na etiqueta; o resto vira "+ N produtos"). */
  itensVisiveis(p: Palete) {
    return p.itens.slice(0, 6);
  }
}
