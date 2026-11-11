import { Component, OnDestroy, OnInit, inject, signal } from '@angular/core';
import { DatePipe } from '@angular/common';
import { OqPanelSectionComponent } from '../conferencia/oq-panel-section/oq-panel-section.component';
import { OqIconComponent } from '../shared/icons/oq-icon.component';
import { OqSkeletonComponent } from '../shared/oq-skeleton/oq-skeleton.component';
import { AuthService } from '../auth/auth.service';
import { ConferenciasService, PendenciaAjustePeso } from '../fila-tarefas/conferencias.service';

/**
 * Pendências de integração com o Sankhya que precisam de alguém (usuário, 11/11/2026): hoje, o ajuste de peso da
 * compra que parou em erro, está falhando ou travou no meio. Atualiza sozinha a cada 60 s; "Gerar ajuste de novo"
 * recoloca na fila do sync (o mesmo botão do card da Fila, que some quando a compra vai pra Concluídos).
 */
@Component({
  selector: 'app-pendencias-integracao',
  standalone: true,
  imports: [DatePipe, OqPanelSectionComponent, OqIconComponent, OqSkeletonComponent],
  templateUrl: './pendencias-integracao.component.html',
})
export class PendenciasIntegracaoComponent implements OnInit, OnDestroy {
  private readonly service = inject(ConferenciasService);
  private readonly auth = inject(AuthService);
  private timer?: ReturnType<typeof setInterval>;

  readonly itens = signal<PendenciaAjustePeso[]>([]);
  readonly carregando = signal(true);
  readonly erro = signal<string | null>(null);
  readonly mensagem = signal<string | null>(null);
  readonly reprocessando = signal<number | null>(null);

  ngOnInit(): void {
    this.carregar();
    this.timer = setInterval(() => this.carregar(true), 60_000);
  }

  ngOnDestroy(): void {
    if (this.timer) clearInterval(this.timer);
  }

  carregar(silencioso = false): void {
    if (!silencioso) this.carregando.set(true);
    this.service.pendenciasAjustePeso().subscribe({
      next: (l) => {
        this.itens.set(l);
        this.erro.set(null);
        this.carregando.set(false);
      },
      error: (e) => {
        this.erro.set(e?.error?.erro ?? 'Não foi possível carregar as pendências.');
        this.carregando.set(false);
      },
    });
  }

  rotuloSentido(p: PendenciaAjustePeso): string {
    return p.sentido === 'E' ? 'Entrada (peso a maior)' : 'Saída (peso a menor)';
  }

  rotuloStatus(p: PendenciaAjustePeso): string {
    if (p.status === 'erro') return `Parou em erro (${p.tentativas} tentativas)`;
    if (p.tentativas > 0) return `Falhando — tentativa ${p.tentativas} de 5`;
    return 'Travado no meio da geração';
  }

  reprocessar(p: PendenciaAjustePeso): void {
    const tenant = this.auth.obterTenantSlug();
    if (!tenant || this.reprocessando() != null) return;
    this.reprocessando.set(p.nunota);
    this.mensagem.set(null);
    this.service.reprocessarAjustePeso(tenant, p.nunota).subscribe({
      next: (r) => {
        this.reprocessando.set(null);
        this.mensagem.set(
          r.reprocessados > 0
            ? `Compra ${p.numeroNota ?? p.nunota}: ajuste recolocado na fila — sai em até 1 minuto.`
            : `Compra ${p.numeroNota ?? p.nunota}: nada a reprocessar (já está em andamento).`,
        );
        this.carregar(true);
      },
      error: (e) => {
        this.reprocessando.set(null);
        this.mensagem.set(e?.error?.erro ?? 'Não foi possível reprocessar.');
      },
    });
  }
}
