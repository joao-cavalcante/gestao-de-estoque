import { Component, OnInit, inject, signal } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { OqIconComponent } from '../shared/icons/oq-icon.component';
import { OqPanelSectionComponent } from '../conferencia/oq-panel-section/oq-panel-section.component';
import { OqAbaGeralComponent } from './oq-aba-geral/oq-aba-geral.component';
import { OqAbaCorteDivergenciaComponent } from './oq-aba-corte-divergencia/oq-aba-corte-divergencia.component';
import { OqAbaFormacaoVolumesComponent } from './oq-aba-formacao-volumes/oq-aba-formacao-volumes.component';
import { ConfigConferenciaService } from './config-conferencia.service';
import { Aba, ConfigConferenciaDetalhe, ConfigConferenciaListItem } from './config-conferencia.model';
import { ABAS } from './config-conferencia.catalogo';

@Component({
  selector: 'app-config-conferencia',
  standalone: true,
  imports: [
    CommonModule,
    FormsModule,
    OqIconComponent,
    OqPanelSectionComponent,
    OqAbaGeralComponent,
    OqAbaCorteDivergenciaComponent,
    OqAbaFormacaoVolumesComponent,
  ],
  templateUrl: './config-conferencia.component.html',
  styleUrl: './config-conferencia.component.scss',
})
export class ConfigConferenciaComponent implements OnInit {
  private readonly service = inject(ConfigConferenciaService);

  readonly abas = ABAS;

  lista = signal<ConfigConferenciaListItem[]>([]);
  nuccoSelecionado = signal<number | null>(null);
  detalhe = signal<ConfigConferenciaDetalhe | null>(null);
  carregando = signal(true);
  sincronizando = signal(false);
  salvando = signal(false);
  erro = signal<string | null>(null);

  /** Vive na raiz de propósito — sobrevive à troca de aba, não reseta. */
  mostrarTodos = signal(false);
  abaAtual = signal<Aba>('geral');

  /** Espelho editável do que está na tela — "formulário vivo": muda aqui, só grava quando clicar Salvar. */
  valoresEditados = signal<Record<string, string | null>>({});
  alterado = signal(false);

  ngOnInit(): void {
    this.carregarLista();
  }

  private carregarLista(): void {
    this.carregando.set(true);
    this.service.listar().subscribe({
      next: (itens) => {
        this.lista.set(itens);
        this.carregando.set(false);
        if (itens.length > 0 && this.nuccoSelecionado() == null) {
          this.selecionar(itens[0].nucco);
        }
      },
      error: () => this.carregando.set(false),
    });
  }

  selecionar(nucco: number): void {
    this.nuccoSelecionado.set(nucco);
    this.detalhe.set(null);
    this.alterado.set(false);
    this.service.detalhe(nucco).subscribe((d) => {
      this.detalhe.set(d);
      this.valoresEditados.set({ ...d.campos });
    });
  }

  onCampoAlterado(evento: { nome: string; valor: string }): void {
    this.valoresEditados.update((atual) => ({ ...atual, [evento.nome]: evento.valor }));
    this.alterado.set(true);
  }

  salvar(): void {
    const nucco = this.nuccoSelecionado();
    if (nucco == null || this.salvando()) return;
    this.salvando.set(true);
    this.erro.set(null);
    this.service.atualizar(nucco, this.valoresEditados()).subscribe({
      next: (d) => {
        this.salvando.set(false);
        this.detalhe.set(d);
        this.valoresEditados.set({ ...d.campos });
        this.alterado.set(false);
      },
      error: (err) => {
        this.salvando.set(false);
        this.erro.set(err.error?.erro ?? 'Não foi possível salvar');
      },
    });
  }

  sincronizarAgora(): void {
    if (this.sincronizando()) return;
    this.sincronizando.set(true);
    this.erro.set(null);
    this.service.sincronizar().subscribe({
      next: () => {
        this.sincronizando.set(false);
        this.carregarLista();
        const atual = this.nuccoSelecionado();
        if (atual != null) this.selecionar(atual);
      },
      error: (err) => {
        this.sincronizando.set(false);
        this.erro.set(err.error?.erro ?? 'Não foi possível sincronizar com o Sankhya');
      },
    });
  }
}
