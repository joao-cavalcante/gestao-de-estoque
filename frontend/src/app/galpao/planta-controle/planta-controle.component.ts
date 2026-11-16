import { Component, OnInit, computed, inject, signal } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { ActivatedRoute, Router } from '@angular/router';
import { OqIconComponent } from '../../shared/icons/oq-icon.component';
import { GalpaoService } from '../galpao.service';
import { Conciliacao, ConciliacaoLinha, Contagem, MovimentosPaginados, Planta, ROTULO_MOVIMENTO } from '../galpao.model';
import { mensagemErro } from '../plantas-lista/plantas-lista.component';

type Aba = 'movimentos' | 'contagens' | 'conciliacao';
type FiltroSituacao = 'TODAS' | 'DIVERGENTES' | ConciliacaoLinha['situacao'];

const ROTULO_SITUACAO: Record<ConciliacaoLinha['situacao'], string> = {
  OK: 'Conferido',
  PARCIAL: 'Parcialmente endereçado',
  SEM_ENDERECO: 'Sem endereço',
  EXCEDENTE: 'Endereçado acima do Sankhya',
  NEGATIVO: 'Saldo negativo no Sankhya',
};

/**
 * Controle da movimentação da planta (ADMINISTRADOR): histórico de movimentos, contagens para aprovar e
 * conciliação físico endereçado × saldo do Sankhya. A conciliação só mostra — não corrige nada.
 */
@Component({
  selector: 'app-planta-controle',
  standalone: true,
  imports: [CommonModule, FormsModule, OqIconComponent],
  templateUrl: './planta-controle.component.html',
  styleUrl: './planta-controle.component.scss',
})
export class PlantaControleComponent implements OnInit {
  private readonly service = inject(GalpaoService);
  private readonly route = inject(ActivatedRoute);
  private readonly router = inject(Router);

  readonly plantaId = this.route.snapshot.paramMap.get('id') ?? '';
  readonly rotuloMov = ROTULO_MOVIMENTO;
  readonly rotuloSituacao = ROTULO_SITUACAO;
  readonly tiposMov = Object.keys(ROTULO_MOVIMENTO).filter((t) => t !== 'CONTAR');

  readonly planta = signal<Planta | null>(null);
  readonly aba = signal<Aba>((this.route.snapshot.queryParamMap.get('aba') as Aba) || 'movimentos');
  readonly erro = signal<string | null>(null);
  readonly aviso = signal<string | null>(null);

  // Movimentações
  readonly movimentos = signal<MovimentosPaginados | null>(null);
  readonly carregandoMov = signal(false);
  filtroTipo = '';
  filtroProduto: number | null = null;
  filtroEndereco = '';

  // Contagens
  readonly contagens = signal<Contagem[]>([]);
  readonly carregandoCont = signal(false);
  readonly somentePendentes = signal(true);
  readonly decidindo = signal<string | null>(null);
  observacoes: Record<string, string> = {};

  // Conciliação
  readonly conciliacao = signal<Conciliacao | null>(null);
  readonly carregandoConc = signal(false);
  readonly erroConc = signal<string | null>(null);
  readonly filtroSituacao = signal<FiltroSituacao>('DIVERGENTES');
  readonly buscaConc = signal('');
  readonly resumoConc = computed(() => {
    const l = this.conciliacao()?.linhas ?? [];
    const contar = (s: ConciliacaoLinha['situacao']) => l.filter((x) => x.situacao === s).length;
    return { total: l.length, ok: contar('OK'), parcial: contar('PARCIAL'), sem: contar('SEM_ENDERECO'), excedente: contar('EXCEDENTE'), negativo: contar('NEGATIVO') };
  });
  readonly linhasConc = computed(() => {
    const f = this.filtroSituacao();
    const t = this.buscaConc().trim().toLowerCase();
    return (this.conciliacao()?.linhas ?? []).filter((l) => {
      const situacaoOk = f === 'TODAS' || (f === 'DIVERGENTES' ? l.situacao !== 'OK' : l.situacao === f);
      const textoOk = !t || String(l.codprod) === t || l.produto.toLowerCase().includes(t);
      return situacaoOk && textoOk;
    });
  });

  ngOnInit(): void {
    this.service.plantas().subscribe({
      next: (p) => this.planta.set(p.find((x) => x.id === this.plantaId) ?? null),
      error: (e) => this.erro.set(mensagemErro(e, 'Não foi possível carregar a planta.')),
    });
    this.trocarAba(this.aba());
  }

  voltar(): void {
    this.router.navigate(['/galpao', this.plantaId]);
  }

  trocarAba(a: Aba): void {
    this.aba.set(a);
    this.router.navigate([], { queryParams: { aba: a }, replaceUrl: true });
    if (a === 'movimentos' && !this.movimentos()) this.carregarMovimentos(1);
    if (a === 'contagens') this.carregarContagens();
    if (a === 'conciliacao' && !this.conciliacao()) this.carregarConciliacao();
  }

  // ─── movimentações ───────────────────────────────────────────────────────

  carregarMovimentos(pagina: number): void {
    this.carregandoMov.set(true);
    this.service
      .movimentos(this.plantaId, { tipo: this.filtroTipo, codprod: this.filtroProduto, endereco: this.filtroEndereco.trim(), pagina })
      .subscribe({
        next: (m) => {
          this.movimentos.set(m);
          this.carregandoMov.set(false);
        },
        error: (e) => {
          this.carregandoMov.set(false);
          this.erro.set(mensagemErro(e, 'Não foi possível carregar as movimentações.'));
        },
      });
  }

  limparFiltros(): void {
    this.filtroTipo = '';
    this.filtroProduto = null;
    this.filtroEndereco = '';
    this.carregarMovimentos(1);
  }

  // ─── contagens ───────────────────────────────────────────────────────────

  carregarContagens(): void {
    this.carregandoCont.set(true);
    this.service.contagens(this.plantaId, this.somentePendentes() ? 'PENDENTE' : undefined).subscribe({
      next: (c) => {
        this.contagens.set(c);
        this.carregandoCont.set(false);
      },
      error: (e) => {
        this.carregandoCont.set(false);
        this.erro.set(mensagemErro(e, 'Não foi possível carregar as contagens.'));
      },
    });
  }

  alternarPendentes(): void {
    this.somentePendentes.set(!this.somentePendentes());
    this.carregarContagens();
  }

  decidir(c: Contagem, aprovar: boolean): void {
    this.decidindo.set(c.id);
    this.aviso.set(null);
    const obs = this.observacoes[c.id]?.trim() || null;
    const chamada = aprovar ? this.service.aprovarContagem(this.plantaId, c.id, obs) : this.service.descartarContagem(this.plantaId, c.id, obs);
    chamada.subscribe({
      next: (r) => {
        this.decidindo.set(null);
        this.aviso.set(`${c.endereco}: ${r.mensagem}`);
        this.movimentos.set(null);
        this.carregarContagens();
      },
      error: (e) => {
        this.decidindo.set(null);
        this.aviso.set(`${c.endereco}: ${mensagemErro(e, 'Não foi possível registrar a decisão.')}`);
        this.carregarContagens();
      },
    });
  }

  // ─── conciliação ─────────────────────────────────────────────────────────

  carregarConciliacao(): void {
    this.carregandoConc.set(true);
    this.erroConc.set(null);
    this.service.conciliacao(this.plantaId).subscribe({
      next: (c) => {
        this.conciliacao.set(c);
        this.carregandoConc.set(false);
      },
      error: (e) => {
        this.carregandoConc.set(false);
        this.erroConc.set(mensagemErro(e, 'Não foi possível montar a conciliação.'));
      },
    });
  }

  num(v: number | null | undefined, casas = 3): string {
    return v == null ? '—' : v.toLocaleString('pt-BR', { maximumFractionDigits: casas });
  }

  sinal(v: number): string {
    return (v > 0 ? '+' : '') + this.num(v);
  }
}
