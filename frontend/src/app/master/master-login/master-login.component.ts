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
  readonly carregando = signal(false);
  readonly erro = signal<string | null>(null);

  entrar(): void {
    if (!this.email.trim() || !this.senha) {
      this.erro.set('Informe e-mail e senha.');
      return;
    }
    this.carregando.set(true);
    this.erro.set(null);
    this.auth.login(this.email.trim(), this.senha).subscribe({
      next: () => {
        this.carregando.set(false);
        this.router.navigate(['/master']);
      },
      error: (e: HttpErrorResponse) => {
        this.carregando.set(false);
        this.senha = '';
        this.erro.set(e.status === 401 ? 'E-mail ou senha inválidos.' : 'Não foi possível entrar. Tente novamente.');
      },
    });
  }
}
