import { Component, EventEmitter, Input, OnChanges, Output, computed, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { OqIconComponent } from '../icons/oq-icon.component';
import { Usuario } from '../../usuarios/usuario.model';

/**
 * Pop-up "Usuários autorizados" de um recurso da conferência (TOP ou Balança) — mesma estrutura nas
 * duas telas. Regra do backend (PermissoesRecurso): lista vazia = sem restrição (todos usam).
 * Conta de estação não entra na lista: quem confere na estação é o operador do crachá.
 */
@Component({
  selector: 'oq-usuarios-autorizados',
  standalone: true,
  imports: [FormsModule, OqIconComponent],
  templateUrl: './oq-usuarios-autorizados.component.html',
  styleUrl: './oq-usuarios-autorizados.component.scss',
})
export class OqUsuariosAutorizadosComponent implements OnChanges {
  @Input({ required: true }) titulo = '';
  @Input() subtitulo = '';
  @Input({ required: true }) usuarios: Usuario[] = [];
  @Input() selecionadosIniciais: string[] = [];
  @Input() salvando = false;
  @Input() erro: string | null = null;
  @Output() salvar = new EventEmitter<string[]>();
  @Output() fechar = new EventEmitter<void>();

  readonly busca = signal('');
  readonly selecionados = signal<Set<string>>(new Set());
  private readonly todos = signal<Usuario[]>([]);

  ngOnChanges(): void {
    const iniciais = new Set(this.selecionadosIniciais);
    this.selecionados.set(iniciais);
    // Pessoas ativas + quem já estava vinculado (mesmo inativo, pra poder remover).
    this.todos.set(
      this.usuarios
        .filter((u) => u.perfil !== 'ESTACAO' && (u.ativo || iniciais.has(u.id)))
        .sort((a, b) => a.nome.localeCompare(b.nome)),
    );
  }

  readonly filtrados = computed(() => {
    const termo = this.normalizar(this.busca());
    if (!termo) return this.todos();
    return this.todos().filter(
      (u) => this.normalizar(u.nome).includes(termo) || this.normalizar(u.email).includes(termo) || (u.crachaoCodigo ?? '').includes(termo),
    );
  });

  readonly listaSelecionados = computed(() => this.todos().filter((u) => this.selecionados().has(u.id)));

  alternar(id: string): void {
    this.selecionados.update((s) => {
      const n = new Set(s);
      n.has(id) ? n.delete(id) : n.add(id);
      return n;
    });
  }

  remover(id: string): void {
    this.selecionados.update((s) => {
      const n = new Set(s);
      n.delete(id);
      return n;
    });
  }

  /** Marca todos os do filtro atual (seleção múltipla rápida). */
  selecionarFiltrados(): void {
    this.selecionados.update((s) => new Set([...s, ...this.filtrados().map((u) => u.id)]));
  }

  limpar(): void {
    this.selecionados.set(new Set());
  }

  confirmar(): void {
    if (this.salvando) return;
    this.salvar.emit([...this.selecionados()]);
  }

  private normalizar(t: string | null | undefined): string {
    return (t ?? '').normalize('NFD').replace(/[̀-ͯ]/g, '').toLowerCase().trim();
  }
}
