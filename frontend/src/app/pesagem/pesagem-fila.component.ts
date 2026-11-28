import { DecimalPipe } from '@angular/common';
import { Component, OnDestroy, OnInit, computed, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { Subscription } from 'rxjs';
import { Balanca } from '../balancas/balanca.model';
import { BalancaService } from '../balancas/balanca.service';
import { LocalScaleService } from '../balancas/local-scale.service';
import { OqIconComponent } from '../shared/icons/oq-icon.component';
import { OqSpinnerComponent } from '../shared/icons/oq-spinner.component';
import { ItemFila, PesagemFilaService } from './pesagem-fila.service';

const LS_BALANCA = 'wms_pesagem_balanca';
const LS_TARA = 'wms_pesagem_tara';

/**
 * Pesagem (computador da balança, 27/11/2026 — etapa D2b do recebimento na doca). Fila dos pesáveis que os
 * conferentes mandaram do coletor; o pesador escolhe, lê a balança, informa a tara e "Pesar e imprimir": a leitura
 * entra na conferência no nome do conferente e o palete sai fechado com a etiqueta.
 */
@Component({
  selector: 'app-pesagem-fila',
  standalone: true,
  imports: [FormsModule, DecimalPipe, OqIconComponent, OqSpinnerComponent],
  templateUrl: './pesagem-fila.component.html',
  styleUrl: './pesagem-fila.component.scss',
})
export class PesagemFilaComponent implements OnInit, OnDestroy {
  private readonly api = inject(PesagemFilaService);
  private readonly balancaService = inject(BalancaService);
  private readonly localScale = inject(LocalScaleService);

  readonly fila = signal<ItemFila[]>([]);
  readonly carregando = signal(true);
  readonly selecionadoId = signal<string | null>(null);
  readonly selecionado = computed(() => this.fila().find((i) => i.id === this.selecionadoId()) ?? null);
  readonly ocupado = signal(false);
  readonly aviso = signal<{ tom: 'ok' | 'erro'; texto: string } | null>(null);

  readonly balancas = signal<Balanca[]>([]);
  balancaId: string | null = null;
  readonly aoVivo = signal<{ peso: number; estavel: boolean } | null>(null);
  bruto = '';
  tara = '';
  private brutoDaBalanca = false;

  private timer?: ReturnType<typeof setInterval>;
  private subs: Subscription[] = [];

  get balanca(): Balanca | null {
    return this.balancas().find((b) => b.id === this.balancaId) ?? null;
  }

  get liquido(): number {
    return Number((this.num(this.bruto) - (this.num(this.tara) || 0)).toFixed(3));
  }

  ngOnInit(): void {
    this.tara = this.ler(LS_TARA) ?? '';
    this.carregar();
    this.timer = setInterval(() => this.carregar(), 5000);
    this.balancaService.listarMinhas(null).subscribe({
      next: (b) => {
        this.balancas.set(b);
        const lembrada = this.ler(LS_BALANCA);
        this.balancaId = b.some((x) => x.id === lembrada) ? lembrada : b.length === 1 ? b[0].id : null;
        this.ligarBalanca();
      },
      error: () => this.balancas.set([]),
    });
  }

  ngOnDestroy(): void {
    if (this.timer) clearInterval(this.timer);
    this.desligarBalanca();
  }

  carregar(): void {
    this.api.listar().subscribe({
      next: (f) => {
        this.fila.set(f);
        this.carregando.set(false);
        if (this.selecionadoId() && !f.some((i) => i.id === this.selecionadoId())) this.selecionadoId.set(null);
        if (!this.selecionadoId() && f.length) this.selecionar(f[0]);
      },
      error: () => this.carregando.set(false),
    });
  }

  selecionar(i: ItemFila): void {
    this.selecionadoId.set(i.id);
    this.bruto = '';
    this.brutoDaBalanca = false;
  }

  trocarBalanca(id: string | null): void {
    this.desligarBalanca();
    this.balancaId = id;
    if (id) this.gravar(LS_BALANCA, id);
    this.aoVivo.set(null);
    this.ligarBalanca();
  }

  /** Serial/TCP: peso contínuo do agente local. HTTP: só no botão "Ler balança". */
  private ligarBalanca(): void {
    const b = this.balanca;
    if (!b || b.tipoComunicacao === 'HTTP' || !b.portaCom) return;
    const porta = b.portaCom;
    this.localScale.conectar();
    this.localScale.subscribe(porta);
    this.subs.push(
      this.localScale.peso$.subscribe((l) => {
        if (l.portaCom === porta) this.aoVivo.set({ peso: l.peso, estavel: l.estavel });
      }),
    );
  }

  private desligarBalanca(): void {
    this.subs.forEach((s) => s.unsubscribe());
    this.subs = [];
    const b = this.balanca;
    if (b?.portaCom && b.tipoComunicacao !== 'HTTP') this.localScale.unsubscribe(b.portaCom);
  }

  lerBalanca(): void {
    const b = this.balanca;
    if (!b) return;
    if (b.tipoComunicacao === 'HTTP') {
      this.ocupado.set(true);
      this.balancaService.capturarPeso(b.id, null).subscribe({
        next: (r) => {
          this.ocupado.set(false);
          this.bruto = String(r.peso);
          this.brutoDaBalanca = true;
        },
        error: () => {
          this.ocupado.set(false);
          this.aviso.set({ tom: 'erro', texto: 'Falha ao ler o peso da balança.' });
        },
      });
      return;
    }
    const v = this.aoVivo();
    if (!v || v.peso <= 0) return this.aviso.set({ tom: 'erro', texto: 'A balança não está mandando peso.' });
    if (!v.estavel) return this.aviso.set({ tom: 'erro', texto: 'Peso ainda instável: aguarde parar.' });
    this.bruto = v.peso.toFixed(3);
    this.brutoDaBalanca = true;
  }

  onBrutoDigitado(): void {
    this.brutoDaBalanca = false;
  }

  pesar(): void {
    const i = this.selecionado();
    if (!i || this.ocupado()) return;
    const b = this.num(this.bruto);
    if (!(b > 0)) return this.aviso.set({ tom: 'erro', texto: 'Leia o peso na balança.' });
    if (this.liquido <= 0) return this.aviso.set({ tom: 'erro', texto: 'A tara é maior ou igual ao peso.' });
    this.gravar(LS_TARA, this.tara);
    this.ocupado.set(true);
    this.api.pesar(i.id, b, this.num(this.tara) || 0, this.brutoDaBalanca ? 'BALANCA' : 'MANUAL').subscribe({
      next: (r) => {
        this.ocupado.set(false);
        const kg = r.pesoLiquido.toLocaleString('pt-BR', { minimumFractionDigits: 3, maximumFractionDigits: 3 });
        this.aviso.set({ tom: 'ok', texto: `${i.codprod} pesado: ${kg} kg${r.palete ? ' · palete ' + r.palete.uma : ''}` });
        if (r.palete) window.open(`/etiquetas-palete?ids=${r.palete.id}`, '_blank');
        this.selecionadoId.set(null);
        this.bruto = '';
        this.carregar();
      },
      error: (e) => {
        this.ocupado.set(false);
        this.aviso.set({ tom: 'erro', texto: e?.error?.erro ?? 'Não gravou a pesagem.' });
        this.carregar();
      },
    });
  }

  cancelar(i: ItemFila): void {
    this.api.cancelar(i.id).subscribe({
      next: () => {
        this.aviso.set({ tom: 'ok', texto: `Item ${i.codprod} retirado da fila` });
        this.carregar();
      },
      error: (e) => this.aviso.set({ tom: 'erro', texto: e?.error?.erro ?? 'Não retirou da fila.' }),
    });
  }

  minutos(iso: string): string {
    const m = Math.max(0, Math.round((Date.now() - new Date(iso).getTime()) / 60000));
    return m < 1 ? 'agora' : m < 60 ? `${m} min` : `${Math.floor(m / 60)} h ${m % 60} min`;
  }

  /** "1.234,5" / "1234,5" / "1234.5" → número. */
  private num(s: string): number {
    const t = String(s ?? '').trim();
    return Number(t.includes(',') ? t.replace(/\./g, '').replace(',', '.') : t);
  }

  private ler(k: string): string | null {
    try {
      return localStorage.getItem(k);
    } catch {
      return null;
    }
  }

  private gravar(k: string, v: string): void {
    try {
      localStorage.setItem(k, v);
    } catch {
      /* sem armazenamento */
    }
  }
}
