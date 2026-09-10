import { Component, OnInit, inject, signal } from '@angular/core';
import { LiberacaoCorteService } from './liberacao-corte.service';
import { ConferenciaAguardandoCorte } from './liberacao-corte.model';
import { OqLiberacaoCorteModalComponent } from './oq-liberacao-corte-modal/oq-liberacao-corte-modal.component';

@Component({
  selector: 'app-liberacao-corte',
  standalone: true,
  imports: [OqLiberacaoCorteModalComponent],
  templateUrl: './liberacao-corte.component.html',
  styleUrl: './liberacao-corte.component.scss',
})
export class LiberacaoCorteComponent implements OnInit {
  private readonly service = inject(LiberacaoCorteService);

  readonly lista = signal<ConferenciaAguardandoCorte[]>([]);
  readonly carregando = signal(true);
  readonly erro = signal<string | null>(null);
  readonly selecionada = signal<ConferenciaAguardandoCorte | null>(null);

  ngOnInit(): void {
    this.carregar();
  }

  carregar(): void {
    this.carregando.set(true);
    this.service.listar().subscribe({
      next: (l) => {
        this.lista.set(l);
        this.carregando.set(false);
      },
      error: (err) => {
        this.erro.set(err?.error?.erro ?? 'Falha ao carregar a lista.');
        this.carregando.set(false);
      },
    });
  }

  abrir(item: ConferenciaAguardandoCorte): void {
    if (item.nuconf == null) {
      this.erro.set(`Pedido ${item.nunota}: NUCONF não encontrado localmente — a conferência não passou pelo WMS.`);
      return;
    }
    this.erro.set(null);
    this.selecionada.set(item);
  }

  rotulo(item: ConferenciaAguardandoCorte): string {
    return `Pedido ${item.numeroNota ?? item.nunota}${item.nomeParceiro ? ' — ' + item.nomeParceiro : ''}`;
  }

  /** houveAcao = pelo menos um item foi liberado/negado — some o card na hora e revalida contra o backend. */
  aoFechar(houveAcao: boolean): void {
    const nunota = this.selecionada()?.nunota;
    this.selecionada.set(null);
    if (houveAcao && nunota != null) {
      this.lista.update((l) => l.filter((c) => c.nunota !== nunota));
    }
    // Revalida: libera parcial / negar mantém a conferência em 'C' → o card volta.
    setTimeout(() => this.carregar(), 600);
  }
}
