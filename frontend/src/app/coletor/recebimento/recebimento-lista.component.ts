import { Component, ElementRef, OnInit, ViewChild, computed, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { Router } from '@angular/router';
import { AuthService } from '../../auth/auth.service';
import { EntradaService, Recebimento, ROTULO_SITUACAO } from '../../entrada/entrada.service';
import { ActionFeedbackService } from '../../shared/action-feedback/action-feedback.service';
import { OqIconComponent } from '../../shared/icons/oq-icon.component';
import { OqSpinnerComponent } from '../../shared/icons/oq-spinner.component';
import { ThemeService } from '../../shared/theme/theme.service';
import { definirPreferenciaTorre } from '../coletor-modo';

/**
 * Recebimento no coletor (doca) — lista das compras a receber (27/11/2026, etapa D1). Mesmo esqueleto e menu da fila
 * do coletor. Bipar/digitar o nº único ou a NF abre a compra na doca.
 */
@Component({
  selector: 'app-recebimento-lista',
  standalone: true,
  imports: [FormsModule, OqIconComponent, OqSpinnerComponent],
  templateUrl: './recebimento-lista.component.html',
  styleUrls: ['../coletor-fila.component.scss'],
})
export class RecebimentoListaComponent implements OnInit {
  private readonly api = inject(EntradaService);
  private readonly auth = inject(AuthService);
  private readonly router = inject(Router);
  private readonly feedback = inject(ActionFeedbackService);
  readonly tema = inject(ThemeService);

  @ViewChild('campo') campo?: ElementRef<HTMLInputElement>;

  readonly operador = computed(() => this.auth.usuario()?.nome ?? '');
  readonly rotulo = ROTULO_SITUACAO;
  readonly lista = signal<Recebimento[] | null>(null);
  readonly carregando = signal(false);
  readonly erro = signal<string | null>(null);
  readonly menuAberto = signal(false);
  readonly aviso = signal<string | null>(null);
  codigo = '';

  ngOnInit(): void {
    this.carregar();
    setTimeout(() => this.campo?.nativeElement.focus({ preventScroll: true }));
  }

  carregar(): void {
    this.carregando.set(true);
    this.api.listar().subscribe({
      next: (l) => {
        this.lista.set([...l].sort((a, b) => a.nunota - b.nunota));
        this.erro.set(null);
        this.carregando.set(false);
      },
      error: (e) => {
        this.erro.set(e?.error?.erro ?? 'As compras não foram carregadas.');
        this.carregando.set(false);
      },
    });
  }

  onLer(): void {
    const c = this.codigo.trim();
    this.codigo = '';
    if (!c) return;
    const n = Number(c.replace(/\D/g, ''));
    const achou = (this.lista() ?? []).find((r) => r.nunota === n || r.numeroNota === n);
    if (achou) {
      this.feedback.trigger('PRODUTO_CORRETO');
      this.abrir(achou);
    } else {
      this.feedback.trigger('PRODUTO_NAO_ENCONTRADO');
      this.aviso.set(`${c}: compra não encontrada na doca.`);
    }
  }

  abrir(r: Recebimento): void {
    this.router.navigate(['/coletor/recebimento', r.nunota], { state: { recebimento: r } });
  }

  irConferencia(): void {
    this.menuAberto.set(false);
    this.router.navigate(['/coletor']);
  }

  irArmazenar(): void {
    this.menuAberto.set(false);
    this.router.navigate(['/coletor/armazenar']);
  }

  alternarTema(): void {
    this.tema.alternar();
  }

  abrirTorre(): void {
    this.menuAberto.set(false);
    definirPreferenciaTorre(true);
    this.router.navigate(['/fila-tarefas']);
  }

  sair(): void {
    this.menuAberto.set(false);
    this.auth.logout();
  }
}
