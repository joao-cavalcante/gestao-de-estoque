import { Component, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { Router } from '@angular/router';
import { HttpErrorResponse } from '@angular/common/http';
import { MasterAuthService } from '../master-auth.service';
import { OqSpinnerComponent } from '../../shared/icons/oq-spinner.component';

/** Entrada do Painel Master — mesmo visual do login do WMS; conta da plataforma, não de cliente. */
@Component({
  selector: 'app-master-login',
  standalone: true,
  imports: [FormsModule, OqSpinnerComponent],
  templateUrl: './master-login.component.html',
  styleUrl: '../../auth/login/login.component.scss',
})
export class MasterLoginComponent {
  private readonly auth = inject(MasterAuthService);
  private readonly router = inject(Router);

  email = '';
  senha = '';
  codigo = '';
  readonly carregando = signal(false);
  readonly erro = signal<string | null>(null);

  entrar(): void {
    const codigo = this.codigo.replace(/\s/g, '');
    if (!this.email.trim() || !this.senha) {
      this.erro.set('Informe e-mail e senha.');
      return;
    }
    if (!/^\d{6}$/.test(codigo)) {
      this.erro.set('Informe o código de 6 dígitos do aplicativo autenticador.');
      return;
    }
    this.carregando.set(true);
    this.erro.set(null);
    this.auth.login(this.email.trim(), this.senha, codigo).subscribe({
      next: () => {
        this.carregando.set(false);
        this.router.navigate(['/master']);
      },
      error: (e: HttpErrorResponse) => {
        this.carregando.set(false);
        this.senha = '';
        this.codigo = '';
        this.erro.set(mensagemErroLogin(e.status));
      },
    });
  }
}

/** Recusa de credencial é sempre a mesma mensagem (o servidor não diz se a conta existe nem qual dado errou). */
export function mensagemErroLogin(status: number): string {
  switch (status) {
    case 401:
      return 'Credenciais inválidas. Confira e-mail, senha e o código atual do aplicativo.';
    case 429:
      return 'Muitas tentativas de acesso. Aguarde 15 minutos e tente novamente.';
    case 503:
      return 'Administração da plataforma não configurada nesta instalação.';
    case 404:
      return 'Administração da plataforma indisponível a partir desta rede.';
    default:
      return 'Não foi possível entrar. Tente novamente.';
  }
}
