import { Component, OnInit, computed, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { ActivatedRoute, Router } from '@angular/router';
import { OqIconComponent } from '../shared/icons/oq-icon.component';
import { OqModalidadePinsComponent } from '../shared/oq-modalidade-pins/oq-modalidade-pins.component';
import { OqSkeletonComponent } from '../shared/oq-skeleton/oq-skeleton.component';
import { OqConferidosChecklistComponent } from './oq-conferidos-checklist.component';
import { ReconferenciaDetalhe, ReconferenciaResumo, ReconferenciaService } from './reconferencia.service';

/**
 * Carregamento: conferências finalizadas pra reconferir (check item a item) na hora de carregar.
 * Filtros iguais aos do Mapa/Fila (Express, Cliente retira, Entrega) + OC e "Não checadas".
 * /carregamento = lista · /carregamento/:sessaoId = checklist da nota.
 */
@Component({
  selector: 'app-carregamento',
  standalone: true,
  imports: [FormsModule, OqIconComponent, OqModalidadePinsComponent, OqSkeletonComponent, OqConferidosChecklistComponent],
  templateUrl: './carregamento.component.html',
  styleUrl: './carregamento.component.scss',
})
export class CarregamentoComponent implements OnInit {
  private readonly service = inject(ReconferenciaService);
  private readonly route = inject(ActivatedRoute);
  private readonly router = inject(Router);

  readonly lista = signal<ReconferenciaResumo[]>([]);
  readonly carregando = signal(true);
  readonly erro = signal<string | null>(null);
  readonly detalhe = signal<ReconferenciaDetalhe | null>(null);
  readonly sessaoAberta = signal<string | null>(null);

  readonly busca = signal('');
  readonly dias = signal(3);
  readonly filtroModalidade = signal<ReadonlySet<string>>(new Set());
  /** 'todas' | 'com' (com OC) | 'sem' (sem OC). */
  readonly filtroOc = signal<'todas' | 'com' | 'sem'>('todas');
  readonly soNaoChecadas = signal(true);

  readonly modalidadesFiltro = [
    { id: 'express', label: 'Express', icone: 'express' },
    { id: 'retira', label: 'Cliente retira', icone: 'retira' },
    { id: 'entrega', label: 'Entrega', icone: 'entrega' },
  ] as const;

  readonly filtradas = computed(() => {
    const termo = this.busca().trim().toLowerCase();
    const mod = this.filtroModalidade();
    const oc = this.filtroOc();
    return this.lista().filter((r) => {
      if (this.soNaoChecadas() && r.totalItens > 0 && r.checados >= r.totalItens) return false;
      if (oc === 'com' && r.ordemCarga == null) return false;
      if (oc === 'sem' && r.ordemCarga != null) return false;
      if (mod.size && !((mod.has('express') && r.express) || (mod.has('retira') && r.retira) || (mod.has('entrega') && r.entrega))) return false;
      if (!termo) return true;
      return (
        String(r.nunota).includes(termo) ||
        String(r.numNota ?? '').includes(termo) ||
        String(r.ordemCarga ?? '').includes(termo) ||
        (r.cliente ?? '').toLowerCase().includes(termo)
      );
    });
  });

  ngOnInit(): void {
    this.route.paramMap.subscribe((p) => {
      const id = p.get('sessaoId');
      this.sessaoAberta.set(id);
      if (id) this.abrir(id);
      else this.buscar();
    });
  }

  buscar(): void {
    this.carregando.set(true);
    this.erro.set(null);
    this.service.listar(this.dias()).subscribe({
      next: (l) => {
        this.lista.set(l);
        this.carregando.set(false);
      },
      error: () => {
        this.erro.set('Não foi possível carregar as conferências finalizadas.');
        this.carregando.set(false);
      },
    });
  }

  private abrir(id: string): void {
    this.detalhe.set(null);
    this.erro.set(null);
    this.service.detalhe(id).subscribe({
      next: (d) => this.detalhe.set(d),
      error: () => this.erro.set('Não foi possível carregar a conferência.'),
    });
  }

  reconferir(r: ReconferenciaResumo): void {
    this.router.navigate(['/carregamento', r.sessaoId]);
  }

  voltar(): void {
    this.router.navigate(['/carregamento']);
  }

  alternarModalidade(id: string): void {
    const s = new Set(this.filtroModalidade());
    if (s.has(id)) s.delete(id);
    else s.add(id);
    this.filtroModalidade.set(s);
  }

  mudarDias(d: number): void {
    this.dias.set(d);
    this.buscar();
  }

  dataHora(iso: string | null): string {
    if (!iso) return '—';
    const d = new Date(iso);
    return d.toLocaleString('pt-BR', { day: '2-digit', month: '2-digit', hour: '2-digit', minute: '2-digit' });
  }

  pct(r: ReconferenciaResumo): number {
    return r.totalItens ? Math.round((r.checados / r.totalItens) * 100) : 0;
  }
}
