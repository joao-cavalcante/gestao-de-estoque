import { AfterViewInit, Component, ElementRef, ViewChild, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { Router, RouterLink } from '@angular/router';
import { AuthService } from '../auth.service';
import { EstacaoService } from '../../balancas/estacao.service';

const CHAVE_TENANT = 'wms_tenant_slug';
/** Quanto tempo mostra "Bem-vindo, {nome}" antes de ir pra fila. */
const TEMPO_BOAS_VINDAS_MS = 1500;

/**
 * Login por crachá (código de barras) — serve pra QUALQUER estação
 * (conferência normal ou pesagem), mesmo padrão de captura do
 * oq-scan-bar.component.ts: um <input> sempre focado, sem HostListener de
 * teclado; o leitor emula digitação + Enter.
 *
 * Depende de 1 config local já salva neste navegador por um login normal
 * anterior (feito uma vez, na configuração da estação):
 * - `wms_tenant_slug` (AuthService, salvo em todo login normal) — sem ela
 *   a estação ainda não está pronta pra crachá.
 *
 * A balança fixada aqui (EstacaoService, tela Balanças → "Fixar nesta
 * estação") é OPCIONAL — só existe em estação de pesagem (Stage 01/02
 * etc.); conferência normal loga igual, sem balança nenhuma.
 */
@Component({
  selector: 'app-cracha-login',
  standalone: true,
  imports: [FormsModule, RouterLink],
  templateUrl: './cracha-login.component.html',
  styleUrl: './cracha-login.component.scss',
})
export class CrachaLoginComponent implements AfterViewInit {
  private readonly auth = inject(AuthService);
  private readonly estacao = inject(EstacaoService);
  private readonly router = inject(Router);

  @ViewChild('inputCracha') inputCracha?: ElementRef<HTMLInputElement>;

  codigo = '';
  carregando = signal(false);
  erro = signal<string | null>(null);
  nomeBemVindo = signal<string | null>(null);

  readonly tenantSlug = localStorage.getItem(CHAVE_TENANT);
  readonly balancaId = this.estacao.obterBalancaId();

  get estacaoConfigurada(): boolean {
    return !!this.tenantSlug;
  }

  ngAfterViewInit(): void {
    this.focar();
  }

  private focar(): void {
    setTimeout(() => this.inputCracha?.nativeElement.focus());
  }

  ler(): void {
    const codigo = this.codigo.trim();
    if (!codigo || this.carregando() || !this.tenantSlug) return;

    this.carregando.set(true);
    this.erro.set(null);

    this.auth.loginCracha(this.tenantSlug, codigo, this.balancaId).subscribe({
      next: (res) => {
        this.codigo = '';
        this.carregando.set(false);
        this.nomeBemVindo.set(res.usuario.nome);
        setTimeout(() => this.router.navigate(['/fila-tarefas']), TEMPO_BOAS_VINDAS_MS);
      },
      error: (err) => {
        this.codigo = '';
        this.carregando.set(false);
        this.erro.set(err?.error?.erro ?? 'Crachá não reconhecido.');
        this.focar();
      },
    });
  }
}
