import { Component, ElementRef, OnInit, ViewChild, computed, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { UsuarioService } from '../usuario.service';
import { Usuario } from '../usuario.model';
import { OqPanelSectionComponent } from '../../conferencia/oq-panel-section/oq-panel-section.component';
import { OqStatusChipComponent } from '../../conferencia/oq-status-chip/oq-status-chip.component';
import { OqIconComponent } from '../../shared/icons/oq-icon.component';
import { OqSkeletonComponent } from '../../shared/oq-skeleton/oq-skeleton.component';
import { OqSpinnerComponent } from '../../shared/icons/oq-spinner.component';
import { CrachaComponent } from '../../shared/cracha/cracha.component';
import { CrachaLogoService } from '../../shared/cracha/cracha-logo.service';
import { FuroCracha, OrientacaoCracha, dimensoes, nomeArquivoCracha } from '../../shared/cracha/cracha-layout';
import { baixarPdfDeSvgs } from '../../shared/cracha/cracha-pdf';

interface FormUsuario {
  nome: string;
  email: string;
  senha: string;
  perfil: string;
  crachaoCodigo: string;
  /** '' = sem turno fixo | 'MANHA' | 'NOITE'. */
  turno: string;
}

function formVazio(): FormUsuario {
  return { nome: '', email: '', senha: '', perfil: 'OPERADOR', crachaoCodigo: '', turno: '' };
}

@Component({
  selector: 'app-usuario-list',
  standalone: true,
  imports: [FormsModule, OqPanelSectionComponent, OqStatusChipComponent, OqIconComponent, OqSkeletonComponent, OqSpinnerComponent, CrachaComponent],
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

  // ─── Crachá ────────────────────────────────────────────────────────────
  private readonly logoService = inject(CrachaLogoService);
  /** Usuário (versão SALVA) cujo crachá está na prévia. */
  readonly crachaPrevia = signal<Usuario | null>(null);
  readonly crachaOrientacao = signal<OrientacaoCracha>('horizontal');
  readonly crachaFuro = signal<FuroCracha>('retangular');
  readonly crachaDim = computed(() => dimensoes(this.crachaOrientacao()));
  readonly crachaLogo = signal<string | null>(null);
  readonly gerandoPdf = signal(false);
  @ViewChild('svgCracha') svgCracha?: ElementRef<SVGSVGElement>;

  /** Seleção pra impressão em lote (só quem tem crachá). */
  readonly selecionados = signal<ReadonlySet<string>>(new Set());

  ngOnInit(): void {
    this.carregar();
    this.logoService.obter().then((l) => this.crachaLogo.set(l));
  }

  /** Código do crachá GRAVADO no banco pro usuário em edição (não o que está digitado). */
  private get crachaSalvo(): string {
    const id = this.editandoId();
    return (id ? this.usuarios().find((u) => u.id === id)?.crachaoCodigo : null)?.trim() ?? '';
  }

  /**
   * Motivo pra NÃO imprimir agora (tooltip do botão) — null = pode. Nunca imprime
   * código que não está no banco: o crachá tem que ser aceito pela conferência.
   */
  get bloqueioCracha(): string | null {
    if (!this.crachaSalvo) {
      return this.form.crachaoCodigo.trim()
        ? 'Salve o usuário antes de imprimir — o código do crachá ainda não foi gravado'
        : 'Cadastre um código de crachá primeiro';
    }
    if (this.form.crachaoCodigo.trim() !== this.crachaSalvo) {
      return 'O código do crachá foi alterado — salve o usuário antes de imprimir';
    }
    return null;
  }

  abrirPreviaCracha(): void {
    const id = this.editandoId();
    const u = id ? this.usuarios().find((x) => x.id === id) : null;
    if (!u || this.bloqueioCracha) return;
    this.crachaPrevia.set(u);
  }

  fecharPreviaCracha(): void {
    this.crachaPrevia.set(null);
  }

  /** Impressão em aba própria (@page no tamanho exato do CR80, sem o resto do app). */
  imprimirCracha(): void {
    const u = this.crachaPrevia();
    if (!u) return;
    this.abrirImpressao([u.id], 'unico', true);
  }

  async baixarPdfCracha(): Promise<void> {
    const u = this.crachaPrevia();
    const svg = this.svgCracha?.nativeElement;
    if (!u || !svg) return;
    this.gerandoPdf.set(true);
    try {
      await baixarPdfDeSvgs([svg], nomeArquivoCracha(u.crachaoCodigo ?? '', u.nome));
    } finally {
      this.gerandoPdf.set(false);
    }
  }

  alternarSelecao(u: Usuario): void {
    const s = new Set(this.selecionados());
    if (s.has(u.id)) s.delete(u.id);
    else s.add(u.id);
    this.selecionados.set(s);
  }

  imprimirLote(): void {
    const ids = [...this.selecionados()];
    if (ids.length) this.abrirImpressao(ids, 'a4', false);
  }

  private abrirImpressao(ids: string[], modo: 'unico' | 'a4', imprimir: boolean): void {
    const q = new URLSearchParams({ ids: ids.join(','), modo, orientacao: this.crachaOrientacao(), furo: this.crachaFuro() });
    if (imprimir) q.set('imprimir', '1');
    window.open('/crachas?' + q.toString(), '_blank');
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
    this.form = {
      nome: usuario.nome,
      email: usuario.email,
      senha: '',
      perfil: usuario.perfil,
      crachaoCodigo: usuario.crachaoCodigo ?? '',
      turno: usuario.turno ?? '',
    };
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
      this.service
        .criar({
          nome: this.form.nome,
          email: this.form.email,
          senha: this.form.senha,
          perfil: this.form.perfil,
          turno: this.form.turno || null,
        })
        .subscribe({
          next: (criado) => this.salvarCracha(criado.id, 'Usuário criado, mas falha ao atribuir o crachá.'),
          error: (err) => {
            this.modalCarregando.set(false);
            this.modalErro.set(err?.error?.erro ?? 'Falha ao criar usuário.');
          },
        });
      return;
    }

    this.modalCarregando.set(true);
    this.service.atualizar(id, { nome: this.form.nome, perfil: this.form.perfil, turno: this.form.turno || null }).subscribe({
      next: () => {
        if (!this.form.senha) {
          this.salvarCracha(id, 'Dados salvos, mas falha ao atribuir o crachá.');
          return;
        }
        this.service.alterarSenha(id, this.form.senha).subscribe({
          next: () => this.salvarCracha(id, 'Senha trocada, mas falha ao atribuir o crachá.'),
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

  /** Último passo do salvar — sempre roda, mesmo com o campo vazio (null remove o crachá). */
  private salvarCracha(id: string, mensagemErro: string): void {
    const codigo = this.form.crachaoCodigo.trim() || null;
    this.service.definirCracha(id, codigo).subscribe({
      next: () => {
        this.modalCarregando.set(false);
        this.modalAberto.set(false);
        this.carregar();
      },
      error: (err) => {
        this.modalCarregando.set(false);
        this.modalErro.set(err?.error?.erro ?? mensagemErro);
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
