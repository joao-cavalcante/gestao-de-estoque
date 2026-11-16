import { Component, OnInit, inject, signal } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { Router } from '@angular/router';
import { HttpErrorResponse } from '@angular/common/http';
import { OqPanelSectionComponent } from '../../conferencia/oq-panel-section/oq-panel-section.component';
import { OqStatusChipComponent } from '../../conferencia/oq-status-chip/oq-status-chip.component';
import { OqIconComponent } from '../../shared/icons/oq-icon.component';
import { OqSkeletonComponent } from '../../shared/oq-skeleton/oq-skeleton.component';
import { GalpaoService } from '../galpao.service';
import { Planta, SalvarPlantaRequest } from '../galpao.model';

export function mensagemErro(e: unknown, padrao: string): string {
  const err = e as HttpErrorResponse;
  return (err?.error && typeof err.error === 'object' && err.error.erro) || padrao;
}

@Component({
  selector: 'app-plantas-lista',
  standalone: true,
  imports: [CommonModule, FormsModule, OqPanelSectionComponent, OqStatusChipComponent, OqIconComponent, OqSkeletonComponent],
  templateUrl: './plantas-lista.component.html',
})
export class PlantasListaComponent implements OnInit {
  private readonly service = inject(GalpaoService);
  private readonly router = inject(Router);

  readonly carregando = signal(true);
  readonly disponivel = signal(true);
  readonly podeEditar = signal(false);
  readonly plantas = signal<Planta[]>([]);
  readonly erro = signal<string | null>(null);

  readonly modalAberto = signal(false);
  readonly editandoId = signal<string | null>(null);
  readonly salvando = signal(false);
  readonly erroModal = signal<string | null>(null);
  form: SalvarPlantaRequest = this.formVazio();

  ngOnInit(): void {
    this.service.disponivel().subscribe({
      next: (r) => {
        this.disponivel.set(r.disponivel);
        this.podeEditar.set(r.podeEditar);
        if (r.disponivel) this.carregar();
        else this.carregando.set(false);
      },
      error: () => {
        this.erro.set('Não foi possível carregar a Planta do Galpão.');
        this.carregando.set(false);
      },
    });
  }

  carregar(): void {
    this.carregando.set(true);
    this.service.plantas().subscribe({
      next: (p) => {
        this.plantas.set(p);
        this.erro.set(null);
        this.carregando.set(false);
      },
      error: (e) => {
        this.erro.set(mensagemErro(e, 'Não foi possível carregar as plantas.'));
        this.carregando.set(false);
      },
    });
  }

  operar(p: Planta): void {
    this.router.navigate(['/galpao', p.id, 'operacao']);
  }

  visualizar(p: Planta): void {
    this.router.navigate(['/galpao', p.id]);
  }

  desenhar(p: Planta): void {
    this.router.navigate(['/galpao', p.id, 'editor']);
  }

  abrirCriacao(): void {
    this.form = this.formVazio();
    this.editandoId.set(null);
    this.erroModal.set(null);
    this.modalAberto.set(true);
  }

  abrirEdicao(p: Planta): void {
    this.form = { nome: p.nome, codemp: p.codemp, codlocal: p.codlocal, largura: p.largura, profundidade: p.profundidade };
    this.editandoId.set(p.id);
    this.erroModal.set(null);
    this.modalAberto.set(true);
  }

  fecharModal(): void {
    if (!this.salvando()) this.modalAberto.set(false);
  }

  salvar(): void {
    const req: SalvarPlantaRequest = {
      nome: (this.form.nome ?? '').trim(),
      codemp: this.form.codemp || null,
      codlocal: this.form.codlocal || null,
      largura: Number(this.form.largura),
      profundidade: Number(this.form.profundidade),
    };
    if (!req.nome) return this.erroModal.set('Informe o nome da planta.');
    if (!(req.largura > 0) || !(req.profundidade > 0)) return this.erroModal.set('Largura e profundidade devem ser maiores que zero.');
    this.salvando.set(true);
    const id = this.editandoId();
    const chamada = id ? this.service.atualizarPlanta(id, req) : this.service.criarPlanta(req);
    chamada.subscribe({
      next: (p) => {
        this.salvando.set(false);
        this.modalAberto.set(false);
        if (id) this.carregar();
        else this.desenhar(p);
      },
      error: (e) => {
        this.salvando.set(false);
        this.erroModal.set(mensagemErro(e, 'Não foi possível salvar a planta.'));
      },
    });
  }

  medidas(p: Planta): string {
    const f = (v: number) => v.toLocaleString('pt-BR', { maximumFractionDigits: 2 });
    return `${f(p.largura)} × ${f(p.profundidade)} m`;
  }

  private formVazio(): SalvarPlantaRequest {
    return { nome: '', codemp: null, codlocal: null, largura: 60, profundidade: 40 };
  }
}
