import { CommonModule } from '@angular/common';
import { Component, inject, signal } from '@angular/core';
import { FormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { ActivatedRoute, Router, RouterLink } from '@angular/router';
import { TenantService } from '../tenant.service';

@Component({
  selector: 'app-tenant-form',
  standalone: true,
  imports: [CommonModule, ReactiveFormsModule, RouterLink],
  templateUrl: './tenant-form.component.html',
  styleUrl: './tenant-form.component.scss',
})
export class TenantFormComponent {
  private readonly fb = inject(FormBuilder);
  private readonly tenantService = inject(TenantService);
  private readonly route = inject(ActivatedRoute);
  private readonly router = inject(Router);

  /** Valor exibido nos campos de credencial quando já há algo salvo — nunca é o segredo real. */
  readonly MASCARA_CREDENCIAL = '*****';

  /** null = tela de cadastro (novo tenant); slug = edição de tenant existente. */
  slugOriginal = signal<string | null>(null);
  salvando = signal(false);
  erro = signal<string | null>(null);
  /** Só informativo — a API nunca devolve o segredo em si, só se há algo salvo. */
  credenciaisJaConfiguradas = signal(false);

  readonly form = this.fb.nonNullable.group({
    slug: ['', [Validators.required, Validators.pattern(/^[a-z0-9-]+$/)]],
    nome: ['', Validators.required],
    tier: ['shared' as 'shared' | 'dedicated', Validators.required],
    status: ['trial'],
    dedicatedDbUrl: [''],
    erpType: ['sankhya'],
    baseUrl: [''],
    gatewayPath: [''],
    dialect: ['SQLSERVER'],
    clientId: [''],
    clientSecret: [''],
    xToken: [''],
  });

  constructor() {
    const slugParam = this.route.snapshot.paramMap.get('slug');
    if (slugParam && slugParam !== 'novo') {
      this.slugOriginal.set(slugParam);
      this.carregarParaEdicao(slugParam);
      // Em edição, slug não muda mais (é a chave usada nas rotas da API).
      this.form.controls.slug.disable();
    }
  }

  get emEdicao(): boolean {
    return this.slugOriginal() !== null;
  }

  get exigeDbDedicado(): boolean {
    return this.form.controls.tier.value === 'dedicated';
  }

  /** Chamado no (focus) dos campos de credencial: some com a máscara pra digitar por cima. */
  limparMascara(campo: 'clientId' | 'clientSecret' | 'xToken'): void {
    if (this.form.controls[campo].value === this.MASCARA_CREDENCIAL) {
      this.form.controls[campo].setValue('');
    }
  }

  private valorFoiAlterado(valor: string): boolean {
    return !!valor && valor !== this.MASCARA_CREDENCIAL;
  }

  private carregarParaEdicao(slug: string): void {
    this.tenantService.buscarPorSlug(slug).subscribe({
      next: (tenant) => {
        const conn = tenant.erpConnections[0];
        const configurada = conn?.credenciaisConfiguradas ?? false;
        this.credenciaisJaConfiguradas.set(configurada);
        // Mostra a máscara clássica (nunca o segredo real — a API não o
        // devolve) quando já existe algo salvo, em vez de deixar em branco.
        const mascara = configurada ? this.MASCARA_CREDENCIAL : '';
        this.form.patchValue({
          slug: tenant.slug,
          nome: tenant.nome,
          tier: tenant.tier,
          status: tenant.status,
          dedicatedDbUrl: tenant.dedicatedDbUrl ?? '',
          erpType: conn?.erpType ?? 'sankhya',
          baseUrl: conn?.baseUrl ?? '',
          gatewayPath: conn?.gatewayPath ?? '',
          dialect: conn?.dialect ?? 'SQLSERVER',
          clientId: mascara,
          clientSecret: mascara,
          xToken: mascara,
        });
      },
      error: (err) => this.erro.set('Falha ao carregar tenant: ' + (err?.message ?? 'erro desconhecido')),
    });
  }

  salvar(): void {
    if (this.form.invalid) {
      this.form.markAllAsTouched();
      return;
    }
    this.erro.set(null);

    const v = this.form.getRawValue();
    const alterados = [v.clientId, v.clientSecret, v.xToken].map((val) => this.valorFoiAlterado(val));
    const algumAlterado = alterados.some(Boolean);
    const todosAlterados = alterados.every(Boolean);

    // Os três campos formam um blob cifrado só (ver backend) — não dá pra
    // trocar um sem os outros dois, porque a API nunca devolve os valores
    // reais pra recompor o blob parcialmente.
    if (algumAlterado && !todosAlterados) {
      this.erro.set('Pra trocar a credencial, preencha Client ID, Client Secret e X-Token juntos (os três, não só um).');
      return;
    }

    this.salvando.set(true);

    // null = "não mexe no segredo já salvo" — só manda credenciais quando
    // o admin de fato preencheu (ou trocou) os três campos.
    const credenciais = algumAlterado
      ? JSON.stringify({ clientId: v.clientId, clientSecret: v.clientSecret, xToken: v.xToken })
      : null;

    if (this.emEdicao) {
      this.tenantService
        .atualizar(this.slugOriginal()!, {
          nome: v.nome,
          status: v.status,
          tier: v.tier,
          dedicatedDbUrl: v.dedicatedDbUrl || null,
        })
        .subscribe({
          next: () => {
            if (!v.baseUrl) {
              this.router.navigateByUrl('/tenants');
              return;
            }
            this.tenantService
              .adicionarErpConnection(this.slugOriginal()!, {
                erpType: v.erpType,
                baseUrl: v.baseUrl,
                gatewayPath: v.gatewayPath || null,
                dialect: v.dialect || null,
                ativo: true,
                credenciais,
              })
              .subscribe({
                next: () => this.router.navigateByUrl('/tenants'),
                error: (err) => {
                  this.salvando.set(false);
                  this.erro.set('Tenant salvo, mas falha ao salvar conexão de ERP: ' + (err?.error?.erro ?? err?.message ?? 'erro desconhecido'));
                },
              });
          },
          error: (err) => {
            this.salvando.set(false);
            this.erro.set('Falha ao salvar: ' + (err?.error?.erro ?? err?.message ?? 'erro desconhecido'));
          },
        });
      return;
    }

    this.tenantService
      .criar({
        slug: v.slug,
        nome: v.nome,
        tier: v.tier,
        dedicatedDbUrl: v.dedicatedDbUrl || null,
        erpConnections: v.baseUrl
          ? [
              {
                erpType: v.erpType,
                baseUrl: v.baseUrl,
                gatewayPath: v.gatewayPath || null,
                dialect: v.dialect || null,
                ativo: true,
                credenciais: credenciais ?? '{}',
              },
            ]
          : [],
      })
      .subscribe({
        next: () => this.router.navigateByUrl('/tenants'),
        error: (err) => {
          this.salvando.set(false);
          this.erro.set('Falha ao criar: ' + (err?.error?.erro ?? err?.message ?? 'erro desconhecido'));
        },
      });
  }
}
