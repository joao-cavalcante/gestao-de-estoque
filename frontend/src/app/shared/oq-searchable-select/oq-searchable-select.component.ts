import { Component, ElementRef, EventEmitter, Input, OnDestroy, Output, computed, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { OqIconComponent } from '../icons/oq-icon.component';

export interface OqSearchableSelectOpcao {
  codigo: string;
  label: string;
}

/**
 * Select padrão do produto — digita pra buscar (não um <select> nativo com
 * lista fixa), sempre exibindo "código - descrição". Genérico: quem usa passa
 * as opções já prontas (código+label), este componente só resolve
 * busca/exibição/seleção.
 *
 * Fecha ao clicar fora via listener manual em FASE DE CAPTURA (não
 * @HostListener('document:click'), que roda na fase de bubble — um ancestral
 * chamando $event.stopPropagation() (ex.: o painel de filtros, pra não fechar
 * a si mesmo ao clicar dentro) bloqueava o clique antes dele chegar ao
 * document, deixando o dropdown "preso" aberto. Captura roda ANTES da fase de
 * bubble, então nenhum stopPropagation() de um descendente consegue impedir.
 */
@Component({
  selector: 'oq-searchable-select',
  standalone: true,
  imports: [FormsModule, OqIconComponent],
  templateUrl: './oq-searchable-select.component.html',
  styleUrl: './oq-searchable-select.component.scss',
})
export class OqSearchableSelectComponent implements OnDestroy {
  private readonly elementRef = inject(ElementRef<HTMLElement>);
  private readonly aoClicarForaBind = (evento: MouseEvent) => this.aoClicarFora(evento);

  @Input() placeholder = 'Todos';
  @Input() opcoes: OqSearchableSelectOpcao[] = [];

  private readonly valorSignal = signal<string | null>(null);
  @Input() set valor(v: string | null) {
    this.valorSignal.set(v);
  }

  @Output() valorChange = new EventEmitter<string | null>();

  readonly aberto = signal(false);
  readonly termo = signal('');

  readonly opcaoSelecionada = computed(() => this.opcoes.find((o) => o.codigo === this.valorSignal()) ?? null);

  readonly opcoesFiltradas = computed(() => {
    const termo = this.termo().trim().toLowerCase();
    if (!termo) return this.opcoes;
    return this.opcoes.filter(
      (o) => o.codigo.toLowerCase().includes(termo) || o.label.toLowerCase().includes(termo),
    );
  });

  get textoExibido(): string {
    if (this.aberto()) return this.termo();
    const sel = this.opcaoSelecionada();
    return sel ? `${sel.codigo} - ${sel.label}` : '';
  }

  ngOnDestroy(): void {
    document.removeEventListener('click', this.aoClicarForaBind, true);
  }

  /**
   * Só `focus` abre — não tem mais um (click) no wrapper alternando aberto/fechado.
   * Um clique num input fechado dispara focus ANTES do click chegar ao wrapper
   * (ordem de eventos do DOM); ter os dois abrindo/alternando fazia o primeiro
   * clique abrir e fechar de novo no mesmo gesto (mesmo tick).
   */
  onFoco(): void {
    if (this.aberto()) return;
    this.aberto.set(true);
    this.termo.set('');
    document.addEventListener('click', this.aoClicarForaBind, true);
  }

  onDigitar(valor: string): void {
    this.termo.set(valor);
  }

  onEscape(): void {
    this.fecharDropdown();
  }

  selecionar(opcao: OqSearchableSelectOpcao): void {
    this.valorSignal.set(opcao.codigo);
    this.valorChange.emit(opcao.codigo);
    this.fecharDropdown();
  }

  limpar(): void {
    this.valorSignal.set(null);
    this.valorChange.emit(null);
    this.fecharDropdown();
  }

  private fecharDropdown(): void {
    this.aberto.set(false);
    this.termo.set('');
    document.removeEventListener('click', this.aoClicarForaBind, true);
  }

  private aoClicarFora(evento: MouseEvent): void {
    if (!this.elementRef.nativeElement.contains(evento.target as Node)) {
      this.fecharDropdown();
    }
  }
}
