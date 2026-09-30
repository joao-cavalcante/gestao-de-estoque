import { Component, OnInit, inject, signal } from '@angular/core';
import { OqIconComponent } from '../shared/icons/oq-icon.component';
import { OqPanelSectionComponent } from '../conferencia/oq-panel-section/oq-panel-section.component';
import { OqInlineAlertComponent } from '../shared/oq-inline-alert/oq-inline-alert.component';
import { OqSkeletonComponent } from '../shared/oq-skeleton/oq-skeleton.component';
import { TiposOperacaoService } from './tipos-operacao.service';
import { TipoOperacao, rotuloTipmov } from './tipos-operacao.model';
import { OqUsuariosAutorizadosComponent } from '../shared/oq-usuarios-autorizados/oq-usuarios-autorizados.component';
import { UsuarioService } from '../usuarios/usuario.service';
import { Usuario } from '../usuarios/usuario.model';

/**
 * Espelho local da TGFTOP (V16), filtrado no backend pra só trazer os TOP com
 * Configuração de Conferência vinculada. Tela própria, não mais uma aba dentro de
 * Config Conferência — decisão revertida a pedido do usuário.
 *
 * V49: por TOP, "Usar conferência por etapa" — conferência de ENTRADA (compra) não
 * usa etapas Secos/Refrigerado/Congelado. Desligado = a nota desse TOP abre em
 * conferência única. O tipo de movimento aparece em cada TOP só como informação.
 */
@Component({
  selector: 'app-tipos-operacao',
  standalone: true,
  imports: [OqIconComponent, OqPanelSectionComponent, OqInlineAlertComponent, OqSkeletonComponent, OqUsuariosAutorizadosComponent],
  templateUrl: './tipos-operacao.component.html',
  styles: [
    `
      .top-tipmov {
        display: inline-block;
        margin-left: 6px;
        padding: 0 6px;
        border: 1px solid var(--oq-border);
        border-radius: 999px;
        font-family: var(--oq-font-display);
        font-size: 10px;
        font-weight: 700;
        text-transform: uppercase;
        letter-spacing: 0.04em;
        color: var(--oq-text-secondary);
        background: var(--oq-surface-2);
      }
      .top-etapa {
        flex: none;
        display: inline-flex;
        align-items: center;
        gap: 8px;
        font-family: var(--oq-font-display);
        font-size: 11px;
        font-weight: 600;
        color: var(--oq-text-primary);
        cursor: pointer;
        user-select: none;
      }
      .top-etapa input {
        width: 16px;
        height: 16px;
        margin: 0;
        accent-color: var(--oq-brand);
        cursor: pointer;
      }
      .top-etapa--off {
        color: var(--oq-text-secondary);
      }
      .top-usuarios {
        flex: none;
        display: inline-flex;
        align-items: center;
        gap: 6px;
      }
    `,
  ],
})
export class TiposOperacaoComponent implements OnInit {
  private readonly service = inject(TiposOperacaoService);

  tops = signal<TipoOperacao[]>([]);
  carregando = signal(true);
  sincronizando = signal(false);
  erro = signal<string | null>(null);
  /** codtop em gravação — evita duplo clique na mesma linha. */
  salvando = signal<number | null>(null);

  readonly rotuloTipmov = rotuloTipmov;

  // ─── Usuários autorizados (V51) — TOP sem usuário = todos conferem ───────
  private readonly usuarioService = inject(UsuarioService);
  usuarios = signal<Usuario[]>([]);
  usuariosTop = signal<{ top: TipoOperacao; selecionados: string[] } | null>(null);
  salvandoUsuarios = signal(false);
  erroUsuarios = signal<string | null>(null);

  abrirUsuarios(top: TipoOperacao): void {
    this.erro.set(null);
    this.erroUsuarios.set(null);
    this.service.listarUsuarios(top.codtop).subscribe({
      next: ({ usuarioIds }) => {
        const abrir = () => this.usuariosTop.set({ top, selecionados: usuarioIds });
        if (this.usuarios().length) return abrir();
        this.usuarioService.listar().subscribe({
          next: (lista) => {
            this.usuarios.set(lista);
            abrir();
          },
          error: (err) => this.erro.set(err.error?.erro ?? 'Não foi possível carregar os usuários'),
        });
      },
      error: (err) => this.erro.set(err.error?.erro ?? `Não foi possível carregar os usuários do TOP ${top.codtop}`),
    });
  }

  salvarUsuarios(ids: string[]): void {
    const atual = this.usuariosTop();
    if (!atual || this.salvandoUsuarios()) return;
    this.salvandoUsuarios.set(true);
    this.erroUsuarios.set(null);
    this.service.definirUsuarios(atual.top.codtop, ids).subscribe({
      next: ({ usuarioIds }) => {
        this.tops.update((l) => l.map((t) => (t.codtop === atual.top.codtop ? { ...t, usuariosAutorizados: usuarioIds.length } : t)));
        this.salvandoUsuarios.set(false);
        this.usuariosTop.set(null);
      },
      error: (err) => {
        this.salvandoUsuarios.set(false);
        this.erroUsuarios.set(err.error?.erro ?? 'Não foi possível salvar os usuários autorizados');
      },
    });
  }

  ngOnInit(): void {
    this.carregar();
  }

  private carregar(): void {
    this.carregando.set(true);
    this.service.listar().subscribe({
      next: (itens) => {
        this.tops.set(itens);
        this.carregando.set(false);
      },
      error: () => this.carregando.set(false),
    });
  }

  sincronizarAgora(): void {
    if (this.sincronizando()) return;
    this.sincronizando.set(true);
    this.erro.set(null);
    this.service.sincronizar().subscribe({
      next: () => {
        this.sincronizando.set(false);
        this.carregar();
      },
      error: (err) => {
        this.sincronizando.set(false);
        this.erro.set(err.error?.erro ?? 'Não foi possível sincronizar com o Sankhya');
      },
    });
  }

  /** Otimista: marca na hora, volta se a gravação falhar. */
  alternarConferenciaPorEtapa(top: TipoOperacao, valor: boolean): void {
    if (this.salvando() === top.codtop) return;
    this.salvando.set(top.codtop);
    this.erro.set(null);
    this.atualizarLocal(top.codtop, valor);
    this.service.definirConferenciaPorEtapa(top.codtop, valor).subscribe({
      next: () => this.salvando.set(null),
      error: (err) => {
        this.atualizarLocal(top.codtop, !valor);
        this.salvando.set(null);
        this.erro.set(err.error?.erro ?? `Não foi possível salvar o TOP ${top.codtop}`);
      },
    });
  }

  private atualizarLocal(codtop: number, valor: boolean): void {
    this.tops.update((lista) => lista.map((t) => (t.codtop === codtop ? { ...t, conferenciaPorEtapa: valor } : t)));
  }
}
