import { AfterViewInit, Component, ElementRef, ViewChild, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { Router } from '@angular/router';
import { AuthService } from '../auth.service';

const CHAVE_TENANT = 'wms_tenant_slug';
/** Este deploy só serve o tenant negri — sem seletor de tenant nem dependência do cache do navegador pro login por crachá. */
const TENANT_PADRAO = 'negri';

type AbaLogin = 'senha' | 'cracha';

@Component({
  selector: 'app-login',
  standalone: true,
  imports: [FormsModule],
  templateUrl: './login.component.html',
  styleUrl: './login.component.scss',
})
export class LoginComponent implements AfterViewInit {
  private readonly auth = inject(AuthService);
  private readonly router = inject(Router);

  @ViewChild('inputCracha') private inputCracha?: ElementRef<HTMLInputElement>;

  readonly aba = signal<AbaLogin>('senha');

  email = '';
  senha = '';
  carregando = signal(false);
  erro = signal<string | null>(null);

  readonly tenantSlug = localStorage.getItem(CHAVE_TENANT) ?? TENANT_PADRAO;
  crachaoCodigo = '';

  ngAfterViewInit(): void {
    if (this.aba() === 'cracha') this.focarCracha();
  }

  trocarAba(aba: AbaLogin): void {
    this.aba.set(aba);
    this.erro.set(null);
    if (aba === 'cracha') setTimeout(() => this.focarCracha());
  }

  private focarCracha(): void {
    this.inputCracha?.nativeElement.focus();
  }

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

  entrarComCracha(): void {
    const codigo = this.crachaoCodigo.trim();
    if (!codigo || this.carregando() || !this.tenantSlug) return;
    this.carregando.set(true);
    this.erro.set(null);

    this.auth.loginCracha(this.tenantSlug, codigo).subscribe({
      next: () => {
        this.crachaoCodigo = '';
        this.carregando.set(false);
        this.router.navigate(['/fila-tarefas']);
      },
      error: (err) => {
        this.crachaoCodigo = '';
        this.carregando.set(false);
        this.erro.set(err?.error?.erro ?? 'Crachá não reconhecido.');
        this.focarCracha();
      },
    });
  }
}
