import { Component, HostListener, computed, inject, signal } from '@angular/core';
import { ActivatedRoute, Router } from '@angular/router';
import { OqIconComponent } from '../../shared/icons/oq-icon.component';
import { OqStatusChipComponent } from '../../conferencia/oq-status-chip/oq-status-chip.component';
import { ChipTone } from '../../conferencia/conferencia.model';
import { Validacao } from '../roteiro-logica';
import { RoteirosDemoService } from '../roteiros-demo.service';
import { BLOCOS, CategoriaBloco, DefinicaoBloco, ROTULO_CATEGORIA, ROTULO_STATUS, ROTULO_TIPO, TipoBloco } from '../roteiros.model';
import { MIME_BLOCO, RoteiroCanvasComponent } from './roteiro-canvas.component';
import { RoteiroEditorStore } from './roteiro-editor.store';
import { RoteiroPropriedadesComponent } from './roteiro-propriedades.component';
import { RoteiroSimuladorComponent } from './roteiro-simulador.component';

/**
 * PROTÓTIPO — Tela B: Construtor Visual de Roteiros. Biblioteca de blocos (esquerda), canvas (centro),
 * propriedades ou simulador (direita). `?modo=visualizar` abre só leitura (uso em tablet).
 * Salvar e publicar gravam no localStorage (RoteirosDemoService); nada chega ao backend.
 */
@Component({
  selector: 'app-roteiro-construtor',
  standalone: true,
  imports: [OqIconComponent, OqStatusChipComponent, RoteiroCanvasComponent, RoteiroPropriedadesComponent, RoteiroSimuladorComponent],
  providers: [RoteiroEditorStore],
  templateUrl: './roteiro-construtor.component.html',
  styleUrl: './roteiro-construtor.component.scss',
})
export class RoteiroConstrutorComponent {
  readonly store = inject(RoteiroEditorStore);
  private readonly servico = inject(RoteirosDemoService);
  private readonly router = inject(Router);
  private readonly rota = inject(ActivatedRoute);

  readonly rotuloTipo = ROTULO_TIPO;
  readonly rotuloStatus = ROTULO_STATUS;
  readonly categorias: { categoria: CategoriaBloco; rotulo: string; blocos: DefinicaoBloco[] }[] = (
    ['OPERACAO', 'CONTROLE', 'SISTEMA'] as CategoriaBloco[]
  ).map((c) => ({ categoria: c, rotulo: ROTULO_CATEGORIA[c], blocos: BLOCOS.filter((b) => b.categoria === c) }));

  readonly naoEncontrado = signal(false);
  readonly painelValidacao = signal(false);
  readonly bibliotecaAberta = signal(false);
  readonly confirmarSaida = signal(false);

  readonly roteiro = this.store.roteiro;
  readonly leitura = this.store.somenteLeitura;
  readonly simulando = this.store.painelSimulacao;
  readonly painelLateral = computed(() => this.simulando() || !!this.store.selecao());

  constructor() {
    this.rota.paramMap.subscribe((p) => {
      const r = this.servico.obter(p.get('id') ?? '');
      this.naoEncontrado.set(!r);
      if (r) this.store.carregar(r, this.rota.snapshot.queryParamMap.get('modo') === 'visualizar');
    });
    this.rota.queryParamMap.subscribe((q) => {
      if (this.roteiro()) this.store.somenteLeitura.set(q.get('modo') === 'visualizar');
    });
  }

  @HostListener('window:beforeunload', ['$event'])
  aoSairDaPagina(ev: BeforeUnloadEvent): void {
    if (this.store.alterado()) ev.preventDefault();
  }

  tomStatus(): ChipTone {
    const s = this.roteiro()?.status;
    return s === 'PUBLICADO' ? 'success' : s === 'RASCUNHO' ? 'warning' : 'neutral';
  }

  // ─── Biblioteca ───────────────────────────────────────────

  arrastarBloco(ev: DragEvent, tipo: TipoBloco): void {
    ev.dataTransfer?.setData(MIME_BLOCO, tipo);
    if (ev.dataTransfer) ev.dataTransfer.effectAllowed = 'copy';
  }

  inserirBloco(tipo: TipoBloco): void {
    this.store.adicionarEtapa(tipo);
    this.bibliotecaAberta.set(false);
  }

  // ─── Validação ────────────────────────────────────────────

  irPara(v: Validacao): void {
    if (v.etapaId) this.store.selecao.set({ tipo: 'etapa', id: v.etapaId });
    else if (v.conexaoId) this.store.selecao.set({ tipo: 'conexao', id: v.conexaoId });
  }

  // ─── Ações ────────────────────────────────────────────────

  salvar(): void {
    const r = this.roteiro();
    if (!r) return;
    const eraPublicado = r.status === 'PUBLICADO';
    const salvo = this.servico.salvar({ ...r, status: r.status === 'INATIVO' ? 'INATIVO' : 'RASCUNHO' });
    this.store.marcarSalvo(salvo);
    this.store.avisar(
      eraPublicado
        ? `Rascunho salvo neste navegador. Na versão definitiva, a v${r.versao} publicada continuaria valendo até a próxima publicação.`
        : 'Rascunho salvo neste navegador (demonstração).',
    );
  }

  publicar(): void {
    const r = this.roteiro();
    if (!r) return;
    if (this.store.erros() > 0) {
      this.painelValidacao.set(true);
      this.store.avisar('Corrija os erros antes de publicar.', 'erro');
      return;
    }
    const salvo = this.servico.publicar(r);
    this.store.marcarSalvo(salvo);
    this.store.avisar(`Publicado como v${salvo.versao} (demonstração; nenhum processo real foi alterado).`);
  }

  editar(): void {
    this.router.navigate([], { relativeTo: this.rota, queryParams: { modo: null } });
  }

  simular(): void {
    if (this.simulando()) this.store.encerrarSimulacao();
    else this.store.abrirSimulacao();
  }

  voltar(): void {
    if (this.store.alterado() && !this.leitura()) {
      this.confirmarSaida.set(true);
      return;
    }
    this.router.navigate(['/roteiros']);
  }

  sairSemSalvar(): void {
    this.confirmarSaida.set(false);
    this.store.carregar(this.servico.obter(this.roteiro()!.id)!, true);
    this.router.navigate(['/roteiros']);
  }

  salvarESair(): void {
    this.salvar();
    this.confirmarSaida.set(false);
    this.router.navigate(['/roteiros']);
  }
}
