import { Component, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { Router } from '@angular/router';
import { AuthService } from '../auth.service';

@Component({
  selector: 'app-login',
  standalone: true,
  imports: [FormsModule],
  templateUrl: './login.component.html',
  styleUrl: './login.component.scss',
})
export class LoginComponent {
  private readonly auth = inject(AuthService);
  private readonly router = inject(Router);

  email = '';
  senha = '';
  carregando = signal(false);
  erro = signal<string | null>(null);

  entrar(): void {
    if (!this.email || !this.senha) return;
    this.carregando.set(true);
    this.erro.set(null);

    this.auth.login(this.email, this.senha).subscribe({
      next: () => {
        this.carregando.set(false);
        this.router.navigate(['/fila-tarefas']);
      },
      error: (err) => {
        this.carregando.set(false);
        this.erro.set(err?.error?.erro ?? 'Falha ao entrar.');
      },
    });
  }
}
