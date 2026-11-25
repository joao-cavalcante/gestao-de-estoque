import { Component, OnDestroy, OnInit, computed, inject, signal } from '@angular/core';
import { DatePipe } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { Subscription } from 'rxjs';
import { OqIconComponent } from '../shared/icons/oq-icon.component';
import { OqSpinnerComponent } from '../shared/icons/oq-spinner.component';
import { SyncTickService } from '../shared/app-header/sync-tick.service';
import { AjustePeso, ItemMesa, ddmm, qtdTexto, rotuloAjuste } from '../entrada/entrada.service';
import { AuditoriaDetalhe, AuditoriaRecebimentoService, RecebimentoAuditoria } from './auditoria-recebimento.service';

type Filtro = 'todas' | 'atencao' | 'ajustes' | 'derivadas';

/**
 * Auditoria de Recebimento (25/11/2026, pedido do usuário): todas as compras conferidas pela Torre, de qualquer data —
 * quem conferiu, quem confirmou e por onde (Torre/Sankhya), as notas que a conferência gerou (ajuste de peso, devolução,
 * complemento) e o que travou (ajuste não emitido, conferência não registrada no addon, nota derivada não confirmada,
 * compra parada). A Liberações fica só com o que aguarda aprovação.
 */
@Component({
  selector: 'app-auditoria-recebimento',
  standalone: true,
  imports: [DatePipe, FormsModule, OqIconComponent, OqSpinnerComponent],
  templateUrl: './auditoria-recebimento.component.html',
  styleUrl: './auditoria-recebimento.component.scss',
})
export class AuditoriaRecebimentoComponent implements OnInit, OnDestroy {
  private readonly service = inject(AuditoriaRecebimentoService);
  private readonly syncTick = inject(SyncTickService);
  private sub?: Subscription;

  readonly ddmm = ddmm;
  readonly qtd = qtdTexto;
  readonly rotuloAjuste = rotuloAjuste;

  readonly lista = signal<RecebimentoAuditoria[]>([]);
  readonly carregando = signal(true);
  readonly erro = signal<string | null>(null);
  readonly busca = signal('');
  readonly filtro = signal<Filtro>('todas');

  readonly selecionada = signal<number | null>(null);
  readonly detalhe = signal<AuditoriaDetalhe | null>(null);
  readonly carregandoDetalhe = signal(false);
  readonly gerando = signal(false);
  readonly erroAcao = signal<string | null>(null);

  readonly qtdAtencao = computed(() => this.lista().filter((r) => r.alertas.length > 0).length);

  readonly visiveis = computed(() => {
    const termo = this.busca().trim().toLowerCase();
    return this.lista().filter((r) => {
      switch (this.filtro()) {
        case 'atencao':
          if (!r.alertas.length) return false;
          break;
        case 'ajustes':
          if (!r.ajustes.length) return false;
          break;
        case 'derivadas':
          if (!r.devolucao && !r.complemento) return false;
          break;
      }
      if (!termo) return true;
      return [r.nunota, r.numeroNota, r.fornecedor, r.conferente, r.ajustes.map((a) => a.nunotaAjuste).join(' '),
        r.devolucao?.nunota, r.complemento?.nunota]
        .some((v) => v != null && String(v).toLowerCase().includes(termo));
    });
  });

  ngOnInit(): void {
    this.carregar();
    this.sub = this.syncTick.onTick.subscribe(() => this.carregar(true));
  }

  ngOnDestroy(): void {
    this.sub?.unsubscribe();
  }

  carregar(silencioso = false): void {
    if (!silencioso) this.carregando.set(true);
    this.service.listar().subscribe({
      next: (l) => {
        this.lista.set(l);
        this.carregando.set(false);
        this.erro.set(null);
        const sel = this.selecionada();
        if (sel != null && silencioso) this.lerDetalhe(sel, true);
      },
      error: (e) => {
        this.carregando.set(false);
        this.erro.set(e?.error?.erro ?? 'Não foi possível carregar a auditoria.');
      },
    });
  }

  abrir(r: RecebimentoAuditoria): void {
    this.selecionada.set(r.nunota);
    this.erroAcao.set(null);
    this.lerDetalhe(r.nunota);
  }

  fechar(): void {
    this.selecionada.set(null);
    this.detalhe.set(null);
  }

  private lerDetalhe(nunota: number, silencioso = false): void {
    if (!silencioso) {
      this.detalhe.set(null);
      this.carregandoDetalhe.set(true);
    }
    this.service.detalhe(nunota).subscribe({
      next: (d) => {
        if (this.selecionada() !== nunota) return;
        this.detalhe.set(d);
        this.carregandoDetalhe.set(false);
      },
      error: (e) => {
        this.carregandoDetalhe.set(false);
        if (!silencioso) this.erroAcao.set(e?.error?.erro ?? 'Não foi possível abrir a compra.');
      },
    });
  }

  gerarAjuste(nunota: number): void {
    if (this.gerando()) return;
    this.gerando.set(true);
    this.erroAcao.set(null);
    this.service.gerarAjuste(nunota).subscribe({
      next: (d) => {
        this.gerando.set(false);
        this.detalhe.set(d);
        this.lista.update((l) => l.map((r) => (r.nunota === nunota ? d.recebimento : r)));
        const erro = d.recebimento.ajustes.find((a) => a.status === 'erro')?.erro;
        if (erro) this.erroAcao.set(erro);
      },
      error: (e) => {
        this.gerando.set(false);
        this.erroAcao.set(e?.error?.erro ?? 'Não foi possível gerar o ajuste agora.');
      },
    });
  }

  // ─── Textos ───
  situacaoTexto(r: RecebimentoAuditoria): string {
    if (r.statusNota === 'X') return 'Nota excluída no Sankhya';
    if (r.alertas.length) return r.alertas.length === 1 ? 'Atenção' : `Atenção (${r.alertas.length})`;
    if (r.statusNota === 'L') return r.encerrado ? 'Concluída' : 'Confirmada';
    if (r.statusNota === 'A') return 'Aguardando confirmação';
    return 'Lendo o Sankhya…';
  }

  confirmacaoTexto(r: RecebimentoAuditoria): string {
    if (r.statusNota !== 'L') return '—';
    const onde = r.confirmadoOrigem === 'T' ? 'Torre' : r.confirmadoOrigem === 'S' ? 'Sankhya' : null;
    return [r.confirmadoPor, onde].filter(Boolean).join(' · ') || 'Confirmada';
  }

  ajusteCurto(a: AjustePeso): string {
    const s = a.sentido === 'E' ? 'Ajuste entrada' : 'Ajuste saída';
    if (a.nunotaAjuste) return `${s} ${a.nunotaAjuste}`;
    return a.status === 'erro' ? `${s} · erro` : a.status === 'cancelado' ? `${s} · cancelado` : `${s} · pendente`;
  }

  temAjustePendente(r: RecebimentoAuditoria): boolean {
    return r.statusNota === 'L' && r.ajustes.some((a) => a.status !== 'confirmado' && a.status !== 'cancelado');
  }

  diferenca(i: ItemMesa): string {
    if (!i.diferenca) return '—';
    return `${i.diferenca > 0 ? '+' : '−'}${qtdTexto(Math.abs(i.diferenca))}`;
  }
}
