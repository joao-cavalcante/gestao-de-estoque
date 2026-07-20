import { Component, OnInit, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { UsuarioService } from '../usuario.service';
import { Usuario } from '../usuario.model';
import { OqPanelSectionComponent } from '../../conferencia/oq-panel-section/oq-panel-section.component';
import { OqStatusChipComponent } from '../../conferencia/oq-status-chip/oq-status-chip.component';
import { OqIconComponent } from '../../shared/icons/oq-icon.component';

interface FormUsuario {
  nome: string;
  email: string;
  senha: string;
  perfil: string;
}

function formVazio(): FormUsuario {
  return { nome: '', email: '', senha: '', perfil: 'OPERADOR' };
}

@Component({
  selector: 'app-usuario-list',
  standalone: true,
  imports: [FormsModule, OqPanelSectionComponent, OqStatusChipComponent, OqIconComponent],
  templateUrl: './usuario-list.component.html',
})
export class UsuarioListComponent implements OnInit {
  private readonly service = inject(UsuarioService);

  usuarios = signal<Usuario[]>([]);
  carregando = signal(true);

  // Modal criar/editar
  modalAberto = signal(false);
  editandoId = signal<string | null>(null);
  form: FormUsuario = formVazio();
  modalErro = signal<string | null>(null);
  modalCarregando = signal(false);

  // Modal confirmação de exclusão
  removendoUsuario = signal<Usuario | null>(null);
  removendoCarregando = signal(false);

  ngOnInit(): void {
    this.carregar();
  }

  carregar(): void {
    this.carregando.set(true);
    this.service.listar().subscribe({
      next: (usuarios) => {
        this.usuarios.set(usuarios);
        this.carregando.set(false);
      },
      error: () => this.carregando.set(false),
    });
  }

  abrirCriacao(): void {
    this.editandoId.set(null);
    this.form = formVazio();
    this.modalErro.set(null);
    this.modalAberto.set(true);
  }

  abrirEdicao(usuario: Usuario): void {
    this.editandoId.set(usuario.id);
    this.form = { nome: usuario.nome, email: usuario.email, senha: '', perfil: usuario.perfil };
    this.modalErro.set(null);
    this.modalAberto.set(true);
  }

  fecharModal(): void {
    this.modalAberto.set(false);
  }

  salvar(): void {
    this.modalErro.set(null);
    const id = this.editandoId();

    if (!id) {
      this.modalCarregando.set(true);
      this.service.criar({ nome: this.form.nome, email: this.form.email, senha: this.form.senha, perfil: this.form.perfil }).subscribe({
        next: () => {
          this.modalCarregando.set(false);
          this.modalAberto.set(false);
          this.carregar();
        },
        error: (err) => {
          this.modalCarregando.set(false);
          this.modalErro.set(err?.error?.erro ?? 'Falha ao criar usuário.');
        },
      });
      return;
    }

    this.modalCarregando.set(true);
    this.service.atualizar(id, { nome: this.form.nome, perfil: this.form.perfil }).subscribe({
      next: () => {
        if (!this.form.senha) {
          this.modalCarregando.set(false);
          this.modalAberto.set(false);
          this.carregar();
          return;
        }
        this.service.alterarSenha(id, this.form.senha).subscribe({
          next: () => {
            this.modalCarregando.set(false);
            this.modalAberto.set(false);
            this.carregar();
          },
          error: (err) => {
            this.modalCarregando.set(false);
            this.modalErro.set(err?.error?.erro ?? 'Dados salvos, mas falha ao trocar a senha.');
          },
        });
      },
      error: (err) => {
        this.modalCarregando.set(false);
        this.modalErro.set(err?.error?.erro ?? 'Falha ao salvar usuário.');
      },
    });
  }

  alternarAtivo(usuario: Usuario): void {
    this.service.atualizar(usuario.id, { ativo: !usuario.ativo }).subscribe(() => this.carregar());
  }

  pedirExclusao(usuario: Usuario): void {
    this.removendoUsuario.set(usuario);
  }

  cancelarExclusao(): void {
    this.removendoUsuario.set(null);
  }

  confirmarExclusao(): void {
    const usuario = this.removendoUsuario();
    if (!usuario) return;
    this.removendoCarregando.set(true);
    this.service.remover(usuario.id).subscribe({
      next: () => {
        this.removendoCarregando.set(false);
        this.removendoUsuario.set(null);
        this.carregar();
      },
      error: () => {
        this.removendoCarregando.set(false);
        this.removendoUsuario.set(null);
      },
    });
  }

  iniciais(nome: string): string {
    return nome.split(' ').filter(Boolean).slice(0, 2).map((p) => p[0]).join('').toUpperCase();
  }
}
