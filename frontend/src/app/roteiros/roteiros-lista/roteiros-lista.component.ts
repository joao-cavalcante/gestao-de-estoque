import { Component, computed, inject, signal } from '@angular/core';
import { DatePipe } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { Router } from '@angular/router';
import { OqIconComponent } from '../../shared/icons/oq-icon.component';
import { OqStatusChipComponent } from '../../conferencia/oq-status-chip/oq-status-chip.component';
import { ChipTone } from '../../conferencia/conferencia.model';
import { MODELOS, ModeloRoteiro } from '../roteiros-demo.data';
import { RoteirosDemoService } from '../roteiros-demo.service';
import { ROTULO_STATUS, ROTULO_TIPO, Roteiro, StatusRoteiro, TipoRoteiro, contarEtapas } from '../roteiros.model';

type Filtro = 'TODOS' | TipoRoteiro;

/**
 * PROTÓTIPO — Tela A: gestão de Roteiros Operacionais (lista, filtros por categoria, novo, duplicar,
 * modelos prontos). Dados demonstrativos do RoteirosDemoService (localStorage).
 */
@Component({
  selector: 'app-roteiros-lista',
  standalone: true,
  imports: [DatePipe, FormsModule, OqIconComponent, OqStatusChipComponent],
  templateUrl: './roteiros-lista.component.html',
  styleUrl: './roteiros-lista.component.scss',
})
export class RoteirosListaComponent {
  private readonly servico = inject(RoteirosDemoService);
  private readonly router = inject(Router);

  readonly rotuloTipo = ROTULO_TIPO;
  readonly rotuloStatus = ROTULO_STATUS;
  readonly modelos = MODELOS;
  readonly contarEtapas = contarEtapas;

  readonly filtros: { valor: Filtro; rotulo: string }[] = [
    { valor: 'TODOS', rotulo: 'Todos' },
    { valor: 'SAIDA', rotulo: 'Saída' },
    { valor: 'ENTRADA', rotulo: 'Entrada' },
    { valor: 'DEVOLUCAO', rotulo: 'Devolução' },
  ];
  readonly filtro = signal<Filtro>('TODOS');

  readonly lista = computed(() => {
    const f = this.filtro();
    return this.servico
      .roteiros()
      .filter((r) => f === 'TODOS' || r.tipo === f)
      .sort((a, b) => a.nome.localeCompare(b.nome, 'pt-BR'));
  });

  contagem(f: Filtro): number {
    return this.servico.roteiros().filter((r) => f === 'TODOS' || r.tipo === f).length;
  }

  // ─── Novo roteiro ─────────────────────────────────────────
  readonly modalNovo = signal(false);
  form = { nome: '', tipo: 'SAIDA' as TipoRoteiro, descricao: '', modelo: '' };

  abrirNovo(modelo?: ModeloRoteiro): void {
    this.form = modelo
      ? { nome: modelo.nome, tipo: modelo.tipo, descricao: modelo.descricao, modelo: modelo.id }
      : { nome: '', tipo: 'SAIDA', descricao: '', modelo: '' };
    this.modalNovo.set(true);
  }

  modelosDoTipo(): ModeloRoteiro[] {
    return this.modelos.filter((m) => m.tipo === this.form.tipo);
  }

  aoMudarTipo(): void {
    if (this.form.modelo && !this.modelosDoTipo().some((m) => m.id === this.form.modelo)) this.form.modelo = '';
  }

  criar(): void {
    const nome = this.form.nome.trim();
    if (!nome) return;
    const r = this.servico.criar(nome, this.form.tipo, this.form.descricao.trim(), this.form.modelo || null);
    this.modalNovo.set(false);
    this.router.navigate(['/roteiros', r.id]);
  }

  // ─── Ações da linha ───────────────────────────────────────
  visualizar(r: Roteiro): void {
    this.router.navigate(['/roteiros', r.id], { queryParams: { modo: 'visualizar' } });
  }

  editar(r: Roteiro): void {
    this.router.navigate(['/roteiros', r.id]);
  }

  duplicar(r: Roteiro): void {
    this.servico.duplicar(r.id);
  }

  restaurar(): void {
    this.servico.restaurarDemonstracao();
    this.filtro.set('TODOS');
  }

  tomStatus(s: StatusRoteiro): ChipTone {
    return s === 'PUBLICADO' ? 'success' : s === 'RASCUNHO' ? 'warning' : 'neutral';
  }

  conceitual(r: Roteiro): boolean {
    return r.tipo !== 'SAIDA';
  }
}
