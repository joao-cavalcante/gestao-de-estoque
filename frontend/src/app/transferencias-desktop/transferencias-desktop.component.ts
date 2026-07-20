import { Component, OnInit, computed, inject, signal } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { HttpErrorResponse } from '@angular/common/http';
import { OqIconComponent } from '../shared/icons/oq-icon.component';
import { OqInlineAlertComponent } from '../shared/oq-inline-alert/oq-inline-alert.component';
import { OqPanelSectionComponent } from '../conferencia/oq-panel-section/oq-panel-section.component';
import { OqStatusChipComponent } from '../conferencia/oq-status-chip/oq-status-chip.component';
import { ChipTone } from '../conferencia/conferencia.model';
import { OqItemScanBarComponent } from '../transferencia/oq-item-scan-bar/oq-item-scan-bar.component';
import { OqItemListComponent } from '../transferencia/oq-item-list/oq-item-list.component';
import { TransferenciaService } from '../transferencia/transferencia.service';
import { CanalOrigem, ItemTransferencia, StatusTransferencia, TransferenciaListItem } from '../transferencia/transferencia.model';

type FiltroCanal = 'todos' | CanalOrigem;
type FiltroStatus = 'todos' | StatusTransferencia;

interface AvisoAlterarLocal {
  campo: 'origem' | 'destino';
  valorNovo: string;
}

@Component({
  selector: 'app-transferencias-desktop',
  standalone: true,
  imports: [
    CommonModule,
    FormsModule,
    OqIconComponent,
    OqInlineAlertComponent,
    OqPanelSectionComponent,
    OqStatusChipComponent,
    OqItemScanBarComponent,
    OqItemListComponent,
  ],
  templateUrl: './transferencias-desktop.component.html',
  styleUrl: './transferencias-desktop.component.scss',
})
export class TransferenciasDesktopComponent implements OnInit {
  private readonly service = inject(TransferenciaService);

  // Lista/histórico — filtros e paginação resolvidos no servidor.
  lista = signal<TransferenciaListItem[]>([]);
  totalRegistros = signal(0);
  totalPaginas = signal(1);
  paginaAtual = signal(1);
  carregando = signal(true);
  filtroCanal = signal<FiltroCanal>('todos');
  filtroStatus = signal<FiltroStatus>('todos');
  termoBusca = signal('');

  // Modelo de nota — bloqueia criação se não configurado
  modeloNotaDisponivel = signal<boolean | null>(null);

  // Modal único — origem/destino sempre editáveis, bipe some junto na mesma tela
  modalAberto = signal(false);
  formOrigem = '';
  formDestino = '';
  origemValidada: string | null = null;
  destinoValidada: string | null = null;
  erroLocais = signal<string | null>(null);
  validandoLocais = signal(false);
  transferenciaAtual = signal<{ id: string; origem: string; destino: string } | null>(null);
  itensModal = signal<ItemTransferencia[]>([]);
  confirmando = signal(false);
  erroConfirmar = signal<string | null>(null);

  /** Setado quando o operador edita origem/destino com itens já bipados — precisa confirmar antes de valer. */
  avisoAlterarLocal = signal<AvisoAlterarLocal | null>(null);

  readonly totalUnidadesModal = computed(() => this.itensModal().reduce((soma, i) => soma + Number(i.quantidade), 0));

  ngOnInit(): void {
    this.carregarLista();
    this.service.buscarModeloNota().subscribe((modelo) => this.modeloNotaDisponivel.set(!!modelo));
  }

  carregarLista(): void {
    this.carregando.set(true);
    const filtroCanal = this.filtroCanal();
    const filtroStatus = this.filtroStatus();
    const canal: CanalOrigem | undefined = filtroCanal === 'todos' ? undefined : filtroCanal;
    const status: StatusTransferencia | undefined = filtroStatus === 'todos' ? undefined : filtroStatus;
    const busca = this.termoBusca().trim() || undefined;

    this.service.listar({ canal, status, busca, pagina: this.paginaAtual() }).subscribe({
      next: (res) => {
        this.lista.set(res.itens);
        this.totalRegistros.set(res.totalRegistros);
        this.totalPaginas.set(res.totalPaginas);
        this.paginaAtual.set(res.paginaAtual);
        this.carregando.set(false);
      },
      error: () => this.carregando.set(false),
    });
  }

  aoMudarFiltro(): void {
    this.paginaAtual.set(1);
    this.carregarLista();
  }

  irParaPagina(pagina: number): void {
    if (pagina < 1 || pagina > this.totalPaginas()) return;
    this.paginaAtual.set(pagina);
    this.carregarLista();
  }

  abrirNovaTransferencia(): void {
    this.formOrigem = '';
    this.formDestino = '';
    this.origemValidada = null;
    this.destinoValidada = null;
    this.erroLocais.set(null);
    this.erroConfirmar.set(null);
    this.avisoAlterarLocal.set(null);
    this.transferenciaAtual.set(null);
    this.itensModal.set([]);
    this.modalAberto.set(true);
  }

  fecharModal(): void {
    this.modalAberto.set(false);
  }

  /** Chamado ao sair do campo (blur) ou Enter — valida e, se o par ficou completo/diferente do atual, cria (ou pede confirmação). */
  validarOrigem(): void {
    const bruto = this.formOrigem.trim();
    if (!bruto) return;
    this.validandoLocais.set(true);
    this.service.validarLocal(bruto, null).subscribe((res) => {
      this.validandoLocais.set(false);
      if (!res.ok) {
        this.origemValidada = null;
        this.erroLocais.set(res.erro ?? 'Local de origem inválido');
        return;
      }
      this.erroLocais.set(null);
      this.formOrigem = res.codigo;
      this.aplicarMudancaLocal('origem', res.codigo);
    });
  }

  /**
   * Sem comparador na chamada HTTP de propósito — validar apenas existência/status aqui.
   * A igualdade com a origem é checada só localmente em `aplicarMudancaLocal`, usando os dois
   * valores já resolvidos: fazer essa checagem dentro da própria chamada de destino zerava
   * `destinoValidada` sempre que ele colidisse TEMPORARIAMENTE com a origem antiga durante uma
   * edição em duas etapas (trocar destino, depois trocar origem) — e não se recuperava sozinho
   * mesmo depois da origem mudar pra um valor diferente.
   */
  validarDestino(): void {
    const bruto = this.formDestino.trim();
    if (!bruto) return;
    this.validandoLocais.set(true);
    this.service.validarLocal(bruto, null).subscribe((res) => {
      this.validandoLocais.set(false);
      if (!res.ok) {
        this.destinoValidada = null;
        this.erroLocais.set(res.erro ?? 'Local de destino inválido');
        return;
      }
      this.erroLocais.set(null);
      this.formDestino = res.codigo;
      this.aplicarMudancaLocal('destino', res.codigo);
    });
  }

  /** Origem/destino validados individualmente — só cria/recria a transferência quando o PAR muda de fato. */
  private aplicarMudancaLocal(campo: 'origem' | 'destino', valor: string): void {
    if (campo === 'origem') this.origemValidada = valor;
    else this.destinoValidada = valor;

    if (!this.origemValidada || !this.destinoValidada) return;
    if (this.origemValidada === this.destinoValidada) {
      this.erroLocais.set('Destino não pode ser igual à origem');
      return;
    }

    const atual = this.transferenciaAtual();
    const parMudou = !atual || atual.origem !== this.origemValidada || atual.destino !== this.destinoValidada;
    if (!parMudou) return;

    if (atual && this.itensModal().length > 0) {
      this.avisoAlterarLocal.set({ campo, valorNovo: valor });
      return;
    }
    this.criarTransferencia();
  }

  confirmarAvisoAlterarLocal(): void {
    this.avisoAlterarLocal.set(null);
    this.criarTransferencia();
  }

  cancelarAvisoAlterarLocal(): void {
    const atual = this.transferenciaAtual();
    if (atual) {
      // desfaz a edição — volta pro trajeto da transferência já criada
      this.formOrigem = atual.origem;
      this.formDestino = atual.destino;
      this.origemValidada = atual.origem;
      this.destinoValidada = atual.destino;
    }
    this.avisoAlterarLocal.set(null);
  }

  private criarTransferencia(): void {
    if (!this.origemValidada || !this.destinoValidada) return;
    this.service.criarTransferencia(this.origemValidada, this.destinoValidada, 'desktop').subscribe({
      next: (criada) => {
        this.transferenciaAtual.set({ id: criada.id, origem: criada.origem, destino: criada.destino });
        this.itensModal.set([]);
      },
      error: (err: HttpErrorResponse) => {
        this.erroLocais.set(err.error?.erro ?? 'Não foi possível criar a transferência');
      },
    });
  }

  onItemAdicionado(item: ItemTransferencia): void {
    this.itensModal.update((arr) => {
      const idx = arr.findIndex((i) => i.id === item.id);
      if (idx === -1) return [item, ...arr];
      return arr.map((i, i2) => (i2 === idx ? item : i));
    });
  }

  onAlterarQtd(evento: { id: string; qtd: number }): void {
    const atual = this.transferenciaAtual();
    const item = this.itensModal().find((i) => i.id === evento.id);
    if (!atual || !item) return;

    this.service.removerItem(atual.id, item.id).subscribe(() => {
      this.itensModal.update((arr) => arr.filter((i) => i.id !== item.id));
      this.service.adicionarItem(atual.id, item.codigoProduto, evento.qtd).subscribe((novo) => {
        this.itensModal.update((arr) => [novo, ...arr]);
      });
    });
  }

  onRemover(itemId: string): void {
    const atual = this.transferenciaAtual();
    if (!atual) return;
    this.service.removerItem(atual.id, itemId).subscribe(() => {
      this.itensModal.update((arr) => arr.filter((i) => i.id !== itemId));
    });
  }

  confirmarTransferencia(): void {
    const atual = this.transferenciaAtual();
    if (!atual || this.confirmando()) return;
    this.confirmando.set(true);
    this.erroConfirmar.set(null);
    this.service.confirmarTransferencia(atual.id).subscribe({
      next: () => {
        this.confirmando.set(false);
        this.fecharModal();
        this.carregarLista();
      },
      error: (err: HttpErrorResponse) => {
        this.confirmando.set(false);
        // 422 (modelo de nota ausente) NÃO fecha o modal — operador pode aguardar e tentar de novo.
        this.erroConfirmar.set(err.error?.erro ?? 'Não foi possível confirmar a transferência');
      },
    });
  }

  toneStatus(status: string): ChipTone {
    switch (status) {
      case 'confirmada':
        return 'success';
      case 'cancelada':
        return 'critical';
      default:
        return 'neutral';
    }
  }

  formatarData(iso: string): string {
    return new Date(iso).toLocaleString('pt-BR');
  }
}
