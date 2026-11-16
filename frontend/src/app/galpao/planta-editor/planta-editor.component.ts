import { Component, ElementRef, HostListener, OnDestroy, OnInit, ViewChild, computed, inject, signal } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { ActivatedRoute, Router } from '@angular/router';
import { forkJoin } from 'rxjs';
import { OqIconComponent } from '../../shared/icons/oq-icon.component';
import { GalpaoService } from '../galpao.service';
import { GeometriaPlanta, ObjetoPlanta, Planta, ROTULO_TIPO, SetorPlanta, TipoObjeto, VersaoResumo } from '../galpao.model';
import { arredondar, enderecosDaEstrutura, extensao, gerarEnderecos, geometriaVazia, novoObjeto, proximoCodigo, totalEnderecos } from '../geometria';
import { Cena3d } from '../cena-3d';
import { mensagemErro } from '../plantas-lista/plantas-lista.component';

type Aba = 'objeto' | 'setores' | 'planta' | 'versoes';

interface Comparacao {
  titulo: string;
  geral: string[];
  adicionados: string[];
  removidos: string[];
  alterados: { codigo: string; campos: string[] }[];
  enderecosNovos: string[];
  enderecosRemovidos: string[];
  totalAntes: number;
  totalDepois: number;
}
interface Caixa { x: number; y: number; w: number; h: number }
interface Arraste {
  tipo: 'mover' | 'pan';
  inicio: { x: number; y: number };
  /** Posição original dos objetos movidos (mover) ou do viewBox (pan). */
  origem: Map<string, { x: number; z: number }> | Caixa;
  moveu: boolean;
}

const MAX_HISTORICO = 100;

/**
 * Editor da planta (ADMINISTRADOR): desenho 2D em metros com prévia 3D. Endereços não são desenhados um a
 * um — saem das estruturas (módulos × níveis). Salvar = rascunho; Publicar = valida no servidor e projeta
 * os endereços (confirmação digitando o nome da planta).
 */
@Component({
  selector: 'app-planta-editor',
  standalone: true,
  imports: [CommonModule, FormsModule, OqIconComponent],
  templateUrl: './planta-editor.component.html',
  styleUrl: './planta-editor.component.scss',
})
export class PlantaEditorComponent implements OnInit, OnDestroy {
  private readonly service = inject(GalpaoService);
  private readonly route = inject(ActivatedRoute);
  private readonly router = inject(Router);

  @ViewChild('svg') svg?: ElementRef<SVGSVGElement>;
  @ViewChild('palco3d') palco3d?: ElementRef<HTMLDivElement>;
  private cena: Cena3d | null = null;

  readonly tipos: TipoObjeto[] = ['ESTRUTURA', 'CORREDOR', 'DOCA', 'AREA', 'PAREDE'];
  readonly rotulo = ROTULO_TIPO;
  readonly plantaId = this.route.snapshot.paramMap.get('id') ?? '';

  readonly carregando = signal(true);
  readonly erro = signal<string | null>(null);
  readonly aviso = signal<string | null>(null);
  readonly planta = signal<Planta | null>(null);
  readonly g = signal<GeometriaPlanta>(geometriaVazia(60, 40));
  readonly selecao = signal<Set<string>>(new Set());
  readonly ferramenta = signal<TipoObjeto | null>(null);
  readonly aba = signal<Aba>('objeto');
  readonly passo = signal(0.5);
  readonly cursor = signal<{ x: number; z: number } | null>(null);
  readonly caixa = signal<Caixa>({ x: -2, y: -2, w: 64, h: 44 });
  readonly modo3d = signal(false);
  readonly alterado = signal(false);
  readonly salvando = signal(false);
  readonly validando = signal(false);
  readonly errosValidacao = signal<string[] | null>(null);
  readonly versoes = signal<VersaoResumo[]>([]);
  readonly rascunhoId = signal<string | null>(null);
  readonly observacao = signal('');

  readonly publicarAberto = signal(false);
  readonly confirmacao = signal('');
  readonly publicando = signal(false);
  readonly erroPublicar = signal<string[] | null>(null);

  readonly fileira = { quantidade: 4, distancia: 4.2 };

  /** Resultado de "Comparar" (versão × desenho aberto). */
  readonly comparacao = signal<Comparacao | null>(null);

  private desfazer: string[] = [];
  private refazer: string[] = [];
  readonly podeDesfazer = signal(false);
  readonly podeRefazer = signal(false);
  private arraste: Arraste | null = null;

  readonly selecionados = computed(() => this.g().objetos.filter((o) => this.selecao().has(o.codigo)));
  readonly unico = computed(() => (this.selecionados().length === 1 ? this.selecionados()[0] : null));
  readonly totalEnd = computed(() => totalEnderecos(this.g()));
  readonly corSetor = computed(() => new Map(this.g().setores.map((s) => [s.codigo, s.cor])));
  readonly viewBox = computed(() => {
    const c = this.caixa();
    return `${c.x} ${c.y} ${c.w} ${c.h}`;
  });
  /** Linhas da grade a cada 5 m (1 m quando aproximado). */
  readonly grade = computed(() => {
    const g = this.g();
    const p = this.caixa().w < 40 ? 1 : 5;
    const xs: number[] = [];
    const zs: number[] = [];
    for (let x = 0; x <= g.largura + 1e-6; x += p) xs.push(x);
    for (let z = 0; z <= g.profundidade + 1e-6; z += p) zs.push(z);
    return { xs, zs };
  });
  readonly espessura = computed(() => this.caixa().w / 900);

  ngOnInit(): void {
    forkJoin({ plantas: this.service.plantas(), desenho: this.service.desenho(this.plantaId) }).subscribe({
      next: ({ plantas, desenho }) => {
        const p = plantas.find((x) => x.id === this.plantaId) ?? null;
        this.planta.set(p);
        if (!p) {
          this.erro.set('Planta não encontrada.');
        } else if (desenho) {
          this.g.set(this.normalizar(desenho.geometria));
          this.rascunhoId.set(desenho.versao.status === 'RASCUNHO' ? desenho.versao.id : null);
          this.observacao.set(desenho.versao.status === 'RASCUNHO' ? (desenho.versao.observacao ?? '') : '');
        } else {
          this.g.set(geometriaVazia(p.largura, p.profundidade));
        }
        this.enquadrar();
        this.carregarVersoes();
        this.carregando.set(false);
      },
      error: (e) => {
        this.erro.set(mensagemErro(e, 'Não foi possível abrir o desenho.'));
        this.carregando.set(false);
      },
    });
  }

  ngOnDestroy(): void {
    this.cena?.destruir();
  }

  @HostListener('window:beforeunload', ['$event'])
  aoSair(e: BeforeUnloadEvent): void {
    if (this.alterado()) e.preventDefault();
  }

  voltar(): void {
    if (this.alterado() && !window.confirm('Há alterações não salvas. Sair mesmo assim?')) return;
    this.router.navigate(['/galpao']);
  }

  // ─── edição ──────────────────────────────────────────────────────────────

  /** Toda alteração passa aqui: guarda o estado anterior pro Desfazer. */
  private alterar(fn: (g: GeometriaPlanta) => GeometriaPlanta, registrar = true): void {
    if (registrar) {
      this.desfazer.push(JSON.stringify(this.g()));
      if (this.desfazer.length > MAX_HISTORICO) this.desfazer.shift();
      this.refazer = [];
    }
    this.g.set(fn(structuredClone(this.g())));
    this.alterado.set(true);
    this.errosValidacao.set(null);
    this.atualizarHistorico();
    this.atualizarPrevia();
  }

  desfazerAcao(): void {
    const anterior = this.desfazer.pop();
    if (!anterior) return;
    this.refazer.push(JSON.stringify(this.g()));
    this.g.set(JSON.parse(anterior));
    this.limparSelecaoInexistente();
    this.alterado.set(true);
    this.atualizarHistorico();
    this.atualizarPrevia();
  }

  refazerAcao(): void {
    const proximo = this.refazer.pop();
    if (!proximo) return;
    this.desfazer.push(JSON.stringify(this.g()));
    this.g.set(JSON.parse(proximo));
    this.limparSelecaoInexistente();
    this.alterado.set(true);
    this.atualizarHistorico();
    this.atualizarPrevia();
  }

  escolherFerramenta(t: TipoObjeto): void {
    this.ferramenta.set(this.ferramenta() === t ? null : t);
  }

  adicionar(tipo: TipoObjeto, x: number, z: number): void {
    const novo = novoObjeto(this.g(), tipo, arredondar(x, this.passo()), arredondar(z, this.passo()));
    this.alterar((g) => ({ ...g, objetos: [...g.objetos, novo] }));
    this.selecao.set(new Set([novo.codigo]));
    this.aba.set('objeto');
    this.ferramenta.set(null);
  }

  duplicar(): void {
    const sel = this.selecionados();
    if (!sel.length) return;
    const novos: string[] = [];
    this.alterar((g) => {
      const objetos = [...g.objetos];
      for (const o of sel) {
        const codigo = proximoCodigo({ ...g, objetos }, o.tipo);
        const { rx } = extensao(o);
        objetos.push({ ...structuredClone(o), codigo, x: o.x + rx + 0.5 });
        novos.push(codigo);
      }
      return { ...g, objetos };
    });
    this.selecao.set(new Set(novos));
  }

  /** Repete a estrutura selecionada em fileira, lado a lado no eixo X (distância entre centros). */
  repetirEmFileira(): void {
    const o = this.unico();
    const qtd = Math.min(200, Math.max(1, Math.round(this.fileira.quantidade)));
    const dist = Number(this.fileira.distancia);
    if (!o || !(dist > 0)) return;
    const novos: string[] = [o.codigo];
    this.alterar((g) => {
      const objetos = [...g.objetos];
      for (let i = 1; i <= qtd; i++) {
        const codigo = proximoCodigo({ ...g, objetos }, o.tipo);
        objetos.push({ ...structuredClone(o), codigo, x: o.x + dist * i });
        novos.push(codigo);
      }
      return { ...g, objetos };
    });
    this.selecao.set(new Set(novos));
  }

  girar(): void {
    const sel = this.selecao();
    if (!sel.size) return;
    this.alterar((g) => ({
      ...g,
      objetos: g.objetos.map((o) => (sel.has(o.codigo) ? { ...o, rotacao: (o.rotacao + 90) % 360 } : o)),
    }));
  }

  excluir(): void {
    const sel = this.selecao();
    if (!sel.size) return;
    this.alterar((g) => ({ ...g, objetos: g.objetos.filter((o) => !sel.has(o.codigo)) }));
    this.selecao.set(new Set());
  }

  /** Campo do painel de propriedades (um objeto). Código trocado precisa continuar único. */
  alterarCampo<K extends keyof ObjetoPlanta>(campo: K, valor: ObjetoPlanta[K]): void {
    const o = this.unico();
    if (!o) return;
    if (campo === 'codigo') {
      const novo = String(valor ?? '').trim().toUpperCase();
      if (!novo || this.g().objetos.some((x) => x.codigo === novo && x !== o)) {
        this.aviso.set(`Código "${novo}" vazio ou já usado.`);
        return;
      }
      valor = novo as ObjetoPlanta[K];
    }
    const numericos: (keyof ObjetoPlanta)[] = ['x', 'z', 'largura', 'profundidade', 'altura', 'rotacao', 'modulos', 'niveis', 'nivelPicking'];
    if (numericos.includes(campo)) valor = Number(valor) as ObjetoPlanta[K];
    if (['capQtd', 'capPeso', 'capVolume'].includes(campo as string)) {
      valor = (valor === null || (valor as unknown) === '' ? null : Number(valor)) as ObjetoPlanta[K];
    }
    this.aviso.set(null);
    this.alterar((g) => ({ ...g, objetos: g.objetos.map((x) => (x.codigo === o.codigo ? { ...x, [campo]: valor } : x)) }));
    if (campo === 'codigo') this.selecao.set(new Set([valor as string]));
  }

  alterarPlanta<K extends 'largura' | 'profundidade' | 'corredorMinimo' | 'padraoEndereco'>(campo: K, valor: GeometriaPlanta[K]): void {
    const v = campo === 'padraoEndereco' ? String(valor) : Number(valor);
    this.alterar((g) => ({ ...g, [campo]: v }));
  }

  adicionarSetor(): void {
    let i = this.g().setores.length + 1;
    while (this.g().setores.some((s) => s.codigo === `SETOR${i}`)) i++;
    this.alterar((g) => ({ ...g, setores: [...g.setores, { codigo: `SETOR${i}`, nome: `Setor ${i}`, cor: '#7a6aa8' }] }));
  }

  alterarSetor(indice: number, campo: keyof SetorPlanta, valor: string): void {
    const antigo = this.g().setores[indice];
    const v = campo === 'codigo' ? valor.trim().toUpperCase() : valor;
    if (campo === 'codigo' && (!v || this.g().setores.some((s, i) => i !== indice && s.codigo === v))) {
      this.aviso.set(`Código de setor "${v}" vazio ou repetido.`);
      return;
    }
    this.aviso.set(null);
    this.alterar((g) => ({
      ...g,
      setores: g.setores.map((s, i) => (i === indice ? { ...s, [campo]: v } : s)),
      // Renomear o código do setor leva as estruturas junto.
      objetos: campo === 'codigo' ? g.objetos.map((o) => (o.setor === antigo.codigo ? { ...o, setor: v } : o)) : g.objetos,
    }));
  }

  removerSetor(indice: number): void {
    const s = this.g().setores[indice];
    const usado = this.g().objetos.filter((o) => o.setor === s.codigo).length;
    if (usado) {
      this.aviso.set(`O setor ${s.nome} está em ${usado} estrutura(s). Troque o setor delas antes de remover.`);
      return;
    }
    this.alterar((g) => ({ ...g, setores: g.setores.filter((_, i) => i !== indice) }));
  }

  // ─── canvas 2D ───────────────────────────────────────────────────────────

  transform(o: ObjetoPlanta): string {
    return `translate(${o.x} ${o.z}) rotate(${o.rotacao})`;
  }

  corObjeto(o: ObjetoPlanta): string {
    switch (o.tipo) {
      case 'ESTRUTURA': return this.corSetor().get(o.setor ?? '') ?? '#7a6aa8';
      case 'CORREDOR': return '#d8d2e6';
      case 'DOCA': return '#8b5e34';
      case 'AREA': return '#e6dcc4';
      default: return '#8b8398';
    }
  }

  /** Divisões de módulo desenhadas dentro da estrutura (coordenadas locais). */
  linhasModulo(o: ObjetoPlanta): number[] {
    if (o.tipo !== 'ESTRUTURA' || o.modulos <= 1 || o.modulos > 200) return [];
    const passo = o.profundidade / o.modulos;
    return Array.from({ length: o.modulos - 1 }, (_, i) => -o.profundidade / 2 + passo * (i + 1));
  }

  rotuloVisivel(o: ObjetoPlanta): boolean {
    return Math.min(o.largura, o.profundidade) / this.caixa().w > 0.012 || Math.max(o.largura, o.profundidade) / this.caixa().w > 0.08;
  }

  tamanhoFonte(): number {
    return Math.max(0.25, Math.min(1.2, this.caixa().w / 70));
  }

  private ponto(e: MouseEvent): { x: number; z: number } {
    const svg = this.svg?.nativeElement;
    if (!svg) return { x: 0, z: 0 };
    const pt = svg.createSVGPoint();
    pt.x = e.clientX;
    pt.y = e.clientY;
    const m = svg.getScreenCTM();
    const p = m ? pt.matrixTransform(m.inverse()) : pt;
    return { x: p.x, z: p.y };
  }

  aoPressionarFundo(e: PointerEvent): void {
    const p = this.ponto(e);
    const t = this.ferramenta();
    if (t && e.button === 0) {
      this.adicionar(t, p.x, p.z);
      return;
    }
    if (!e.shiftKey) this.selecao.set(new Set());
    this.capturar(e);
    this.arraste = { tipo: 'pan', inicio: { x: e.clientX, y: e.clientY }, origem: { ...this.caixa() }, moveu: false };
  }

  aoPressionarObjeto(e: PointerEvent, o: ObjetoPlanta): void {
    if (this.ferramenta()) return; // deixa o clique cair no fundo pra adicionar
    e.stopPropagation();
    if (e.button === 1) return;
    const sel = new Set(this.selecao());
    if (e.shiftKey) {
      if (sel.has(o.codigo)) sel.delete(o.codigo);
      else sel.add(o.codigo);
    } else if (!sel.has(o.codigo)) {
      sel.clear();
      sel.add(o.codigo);
    }
    this.selecao.set(sel);
    this.aba.set('objeto');
    const origem = new Map(this.g().objetos.filter((x) => sel.has(x.codigo)).map((x) => [x.codigo, { x: x.x, z: x.z }]));
    this.capturar(e);
    const p = this.ponto(e);
    this.arraste = { tipo: 'mover', inicio: { x: p.x, y: p.z }, origem, moveu: false };
  }

  /** Mantém o arraste mesmo com o ponteiro saindo do desenho; falha (ponteiro já solto) é inofensiva. */
  private capturar(e: PointerEvent): void {
    try {
      (e.target as Element).setPointerCapture?.(e.pointerId);
    } catch {
      /* sem captura: o arraste só para ao sair do desenho */
    }
  }

  aoMover(e: PointerEvent): void {
    const p = this.ponto(e);
    this.cursor.set({ x: p.x, z: p.z });
    const a = this.arraste;
    if (!a) return;
    if (a.tipo === 'pan') {
      const svg = this.svg?.nativeElement;
      const c = a.origem as Caixa;
      if (!svg) return;
      const escala = c.w / svg.clientWidth;
      this.caixa.set({ ...c, x: c.x - (e.clientX - a.inicio.x) * escala, y: c.y - (e.clientY - a.inicio.y) * escala });
      return;
    }
    const dx = arredondar(p.x - a.inicio.x, this.passo());
    const dz = arredondar(p.z - a.inicio.y, this.passo());
    if (!a.moveu && dx === 0 && dz === 0) return;
    if (!a.moveu) {
      // Um registro de Desfazer por arraste inteiro.
      this.desfazer.push(JSON.stringify(this.g()));
      this.refazer = [];
      a.moveu = true;
    }
    const origem = a.origem as Map<string, { x: number; z: number }>;
    this.alterar(
      (g) => ({
        ...g,
        objetos: g.objetos.map((o) => {
          const base = origem.get(o.codigo);
          return base ? { ...o, x: +(base.x + dx).toFixed(3), z: +(base.z + dz).toFixed(3) } : o;
        }),
      }),
      false,
    );
  }

  aoSoltar(): void {
    this.arraste = null;
  }

  aoRolar(e: WheelEvent): void {
    e.preventDefault();
    const p = this.ponto(e);
    const c = this.caixa();
    const f = e.deltaY > 0 ? 1.15 : 1 / 1.15;
    const w = Math.min(Math.max(c.w * f, 3), 3000);
    const fator = w / c.w;
    this.caixa.set({ x: p.x - (p.x - c.x) * fator, y: p.z - (p.z - c.y) * fator, w, h: c.h * fator });
  }

  enquadrar(): void {
    const g = this.g();
    const svg = this.svg?.nativeElement;
    const proporcao = svg && svg.clientWidth > 0 ? svg.clientHeight / svg.clientWidth : 0.6;
    const margem = Math.max(g.largura, g.profundidade) * 0.06 + 1;
    let w = g.largura + margem * 2;
    let h = g.profundidade + margem * 2;
    if (h / w > proporcao) w = h / proporcao;
    else h = w * proporcao;
    this.caixa.set({ x: g.largura / 2 - w / 2, y: g.profundidade / 2 - h / 2, w, h });
  }

  @HostListener('window:keydown', ['$event'])
  aoTeclar(e: KeyboardEvent): void {
    const alvo = e.target as HTMLElement;
    if (this.publicarAberto() || ['INPUT', 'SELECT', 'TEXTAREA'].includes(alvo?.tagName)) return;
    const ctrl = e.ctrlKey || e.metaKey;
    if (ctrl && e.key.toLowerCase() === 'z') { e.preventDefault(); return e.shiftKey ? this.refazerAcao() : this.desfazerAcao(); }
    if (ctrl && e.key.toLowerCase() === 'y') { e.preventDefault(); return this.refazerAcao(); }
    if (ctrl && e.key.toLowerCase() === 'd') { e.preventDefault(); return this.duplicar(); }
    if (ctrl && e.key.toLowerCase() === 's') { e.preventDefault(); return this.salvar(); }
    if (e.key === 'Delete') return this.excluir();
    if (e.key === 'Escape') { this.ferramenta.set(null); this.selecao.set(new Set()); return; }
    if (e.key.toLowerCase() === 'r' && !ctrl) return this.girar();
    const setas: Record<string, [number, number]> = { ArrowLeft: [-1, 0], ArrowRight: [1, 0], ArrowUp: [0, -1], ArrowDown: [0, 1] };
    const d = setas[e.key];
    if (d && this.selecao().size) {
      e.preventDefault();
      const passo = this.passo() || 0.1;
      const sel = this.selecao();
      this.alterar((g) => ({
        ...g,
        objetos: g.objetos.map((o) => (sel.has(o.codigo) ? { ...o, x: +(o.x + d[0] * passo).toFixed(3), z: +(o.z + d[1] * passo).toFixed(3) } : o)),
      }));
    }
  }

  // ─── prévia 3D ───────────────────────────────────────────────────────────

  alternar3d(): void {
    this.modo3d.set(!this.modo3d());
    if (this.modo3d()) {
      setTimeout(() => {
        const el = this.palco3d?.nativeElement;
        if (!el) return;
        if (!this.cena) {
          try {
            this.cena = new Cena3d(el);
          } catch {
            this.aviso.set('Este computador não suporta visualização 3D (WebGL).');
            this.modo3d.set(false);
            return;
          }
        }
        this.cena.carregar(this.g(), gerarEnderecos(this.g()));
        this.cena.definirModo('setor');
      });
    }
  }

  private atualizarPrevia(): void {
    if (this.modo3d() && this.cena) this.cena.carregar(this.g(), gerarEnderecos(this.g()), true);
  }

  // ─── servidor ────────────────────────────────────────────────────────────

  validar(): void {
    this.validando.set(true);
    this.service.validar(this.plantaId, this.g()).subscribe({
      next: (v) => {
        this.validando.set(false);
        this.errosValidacao.set(v.erros);
        if (v.valido) this.aviso.set(`Desenho válido — ${v.totalEnderecos.toLocaleString('pt-BR')} endereços.`);
      },
      error: (e) => {
        this.validando.set(false);
        this.aviso.set(mensagemErro(e, 'Não foi possível validar.'));
      },
    });
  }

  salvar(depois?: () => void): void {
    if (this.salvando()) return;
    this.salvando.set(true);
    this.service.salvarRascunho(this.plantaId, this.g(), this.observacao() || null).subscribe({
      next: (v) => {
        this.salvando.set(false);
        this.alterado.set(false);
        this.rascunhoId.set(v.id);
        this.aviso.set(`Rascunho salvo (versão ${v.numero}).`);
        this.carregarVersoes();
        depois?.();
      },
      error: (e) => {
        this.salvando.set(false);
        this.aviso.set(mensagemErro(e, 'Não foi possível salvar o rascunho.'));
      },
    });
  }

  abrirPublicar(): void {
    const abrir = () => {
      this.confirmacao.set('');
      this.erroPublicar.set(null);
      this.publicarAberto.set(true);
    };
    if (this.alterado() || !this.rascunhoId()) this.salvar(abrir);
    else abrir();
  }

  publicar(): void {
    const id = this.rascunhoId();
    if (!id) return;
    this.publicando.set(true);
    this.service.publicar(this.plantaId, id, this.confirmacao()).subscribe({
      next: (r) => {
        this.publicando.set(false);
        this.publicarAberto.set(false);
        this.rascunhoId.set(null);
        const desativados = Number(r.enderecosDesativados);
        this.aviso.set(
          `Versão ${r.versao} publicada — ${Number(r.enderecosAtivos).toLocaleString('pt-BR')} endereços ativos` +
            (desativados ? `, ${desativados} desativado(s).` : '.'),
        );
        this.carregarVersoes();
      },
      error: (e) => {
        this.publicando.set(false);
        const corpo = e?.error;
        this.erroPublicar.set([corpo?.erro ?? 'Não foi possível publicar.', ...(corpo?.detalhes ?? [])]);
      },
    });
  }

  abrirVersao(v: VersaoResumo): void {
    if (this.alterado() && !window.confirm('Há alterações não salvas. Abrir outra versão mesmo assim?')) return;
    this.service.desenho(this.plantaId, v.id).subscribe((d) => {
      if (!d) return;
      this.g.set(this.normalizar(d.geometria));
      this.rascunhoId.set(d.versao.status === 'RASCUNHO' ? d.versao.id : null);
      this.desfazer = [];
      this.refazer = [];
      this.atualizarHistorico();
      this.selecao.set(new Set());
      this.alterado.set(false);
      this.enquadrar();
      this.atualizarPrevia();
      this.aviso.set(d.versao.status === 'RASCUNHO' ? null : `Versão ${d.versao.numero} aberta só para consulta. Para alterar, use "Restaurar como rascunho".`);
    });
  }

  restaurar(v: VersaoResumo): void {
    if (!window.confirm(`Copiar a versão ${v.numero} para o rascunho? O rascunho atual será substituído.`)) return;
    this.service.restaurar(this.plantaId, v.id).subscribe({
      next: (r) => {
        this.carregarVersoes();
        this.abrirVersaoPorId(r.id);
      },
      error: (e) => this.aviso.set(mensagemErro(e, 'Não foi possível restaurar a versão.')),
    });
  }

  private abrirVersaoPorId(id: string): void {
    this.service.desenho(this.plantaId, id).subscribe((d) => {
      if (!d) return;
      this.g.set(this.normalizar(d.geometria));
      this.rascunhoId.set(d.versao.id);
      this.alterado.set(false);
      this.atualizarPrevia();
      this.aviso.set(`Versão restaurada no rascunho ${d.versao.numero}.`);
    });
  }

  enderecosDe(o: ObjetoPlanta): number {
    return o.tipo === 'ESTRUTURA' ? enderecosDaEstrutura(o, this.g().padraoEndereco).length : 0;
  }

  exemploEndereco(): string {
    const o = this.unico();
    const e = o && o.tipo === 'ESTRUTURA' ? enderecosDaEstrutura(o, this.g().padraoEndereco) : [];
    return e.length ? `${e[0].codigo} … ${e[e.length - 1].codigo}` : '';
  }

  // ─── importar / exportar / comparar ───────────────────────────────────────

  /** Baixa o desenho aberto em JSON (mesmo formato que o Importar lê). */
  exportarJson(): void {
    const nome = (this.planta()?.nome ?? 'planta').replace(/[^\w-]+/g, '_');
    const conteudo = JSON.stringify({ formato: 'planta-galpao', versao: 1, planta: this.planta()?.nome ?? null, geometria: this.g() }, null, 2);
    const url = URL.createObjectURL(new Blob([conteudo], { type: 'application/json' }));
    const a = document.createElement('a');
    a.href = url;
    a.download = `planta-${nome}.json`;
    a.click();
    setTimeout(() => URL.revokeObjectURL(url), 1000);
  }

  /** Lê um JSON exportado (ou só a geometria) e substitui o desenho aberto — dá pra desfazer. */
  importarJson(ev: Event): void {
    const input = ev.target as HTMLInputElement;
    const arquivo = input.files?.[0];
    input.value = '';
    if (!arquivo) return;
    arquivo.text().then(
      (texto) => {
        let g: GeometriaPlanta;
        try {
          const dado = JSON.parse(texto);
          g = dado?.geometria ?? dado;
        } catch {
          this.aviso.set('Arquivo inválido: não é um JSON.');
          return;
        }
        const problema = this.problemaImportacao(g);
        if (problema) {
          this.aviso.set(`Arquivo não importado: ${problema}`);
          return;
        }
        if (!window.confirm(`Substituir o desenho aberto pelo de "${arquivo.name}" (${g.objetos.length} objetos)? Dá para desfazer com Ctrl+Z.`)) return;
        this.alterar(() => this.normalizar(g));
        this.selecao.set(new Set());
        this.enquadrar();
        this.aviso.set(`Desenho importado de "${arquivo.name}". Valide e salve o rascunho para manter.`);
      },
      () => this.aviso.set('Não foi possível ler o arquivo.'),
    );
  }

  private problemaImportacao(g: GeometriaPlanta | null | undefined): string | null {
    if (!g || typeof g !== 'object') return 'conteúdo vazio.';
    if (!(Number(g.largura) > 0) || !(Number(g.profundidade) > 0)) return 'largura e comprimento da planta são obrigatórios.';
    if (!Array.isArray(g.objetos)) return 'lista de objetos ausente.';
    const tipos = new Set<string>(this.tipos);
    const ruim = g.objetos.find((o) => !o || typeof o.codigo !== 'string' || !tipos.has(o.tipo) || !Number.isFinite(Number(o.x)) || !Number.isFinite(Number(o.z)));
    if (ruim) return `objeto inválido (${ruim?.codigo ?? 'sem código'}).`;
    return null;
  }

  /** Compara uma versão salva com o desenho aberto: objetos e endereços que entram, saem ou mudam. */
  comparar(v: VersaoResumo): void {
    this.service.desenho(this.plantaId, v.id).subscribe({
      next: (d) => {
        if (!d) return;
        const antes = this.normalizar(d.geometria);
        const depois = this.g();
        const mapaAntes = new Map(antes.objetos.map((o) => [o.codigo, o]));
        const mapaDepois = new Map(depois.objetos.map((o) => [o.codigo, o]));
        const campos: (keyof ObjetoPlanta)[] = ['tipo', 'x', 'z', 'largura', 'profundidade', 'altura', 'rotacao', 'setor', 'modulos', 'niveis', 'nivelPicking', 'capQtd', 'capPeso', 'capVolume'];
        const alterados = depois.objetos
          .filter((o) => mapaAntes.has(o.codigo))
          .map((o) => ({ codigo: o.codigo, campos: campos.filter((c) => (mapaAntes.get(o.codigo)![c] ?? null) !== (o[c] ?? null)) }))
          .filter((x) => x.campos.length > 0);
        const endAntes = new Set(gerarEnderecos(antes).map((e) => e.codigo));
        const endDepois = new Set(gerarEnderecos(depois).map((e) => e.codigo));
        const geral: string[] = [];
        if (antes.largura !== depois.largura || antes.profundidade !== depois.profundidade) geral.push(`Planta ${antes.largura} × ${antes.profundidade} m → ${depois.largura} × ${depois.profundidade} m`);
        if (antes.padraoEndereco !== depois.padraoEndereco) geral.push(`Nomenclatura ${antes.padraoEndereco} → ${depois.padraoEndereco}`);
        if (JSON.stringify(antes.setores) !== JSON.stringify(depois.setores)) geral.push('Setores alterados');
        this.comparacao.set({
          titulo: `Versão ${v.numero} (${v.status.toLowerCase()}) × desenho aberto`,
          geral,
          adicionados: depois.objetos.filter((o) => !mapaAntes.has(o.codigo)).map((o) => o.codigo),
          removidos: antes.objetos.filter((o) => !mapaDepois.has(o.codigo)).map((o) => o.codigo),
          alterados,
          enderecosNovos: [...endDepois].filter((c) => !endAntes.has(c)),
          enderecosRemovidos: [...endAntes].filter((c) => !endDepois.has(c)),
          totalAntes: endAntes.size,
          totalDepois: endDepois.size,
        });
      },
      error: (e) => this.aviso.set(mensagemErro(e, 'Não foi possível abrir a versão para comparar.')),
    });
  }

  readonly rotuloCampoFn = (c: string): string => this.rotuloCampo(c);

  rotuloCampo(c: string): string {
    return (
      { tipo: 'tipo', x: 'posição X', z: 'posição Z', largura: 'largura', profundidade: 'comprimento', altura: 'altura', rotacao: 'rotação', setor: 'setor', modulos: 'módulos', niveis: 'níveis', nivelPicking: 'níveis de picking', capQtd: 'capacidade (un)', capPeso: 'capacidade (kg)', capVolume: 'capacidade (m³)' } as Record<string, string>
    )[c] ?? c;
  }

  private carregarVersoes(): void {
    this.service.versoes(this.plantaId).subscribe({ next: (v) => this.versoes.set(v), error: () => undefined });
  }

  private atualizarHistorico(): void {
    this.podeDesfazer.set(this.desfazer.length > 0);
    this.podeRefazer.set(this.refazer.length > 0);
  }

  private limparSelecaoInexistente(): void {
    const codigos = new Set(this.g().objetos.map((o) => o.codigo));
    this.selecao.set(new Set([...this.selecao()].filter((c) => codigos.has(c))));
  }

  /** Completa campos ausentes (desenho gravado por versão anterior do editor). */
  private normalizar(g: GeometriaPlanta): GeometriaPlanta {
    return {
      ...geometriaVazia(g.largura, g.profundidade),
      ...g,
      objetos: g.objetos.map((o) => ({ ...o, rotacao: o.rotacao ?? 0, modulos: o.modulos ?? 1, niveis: o.niveis ?? 1, nivelPicking: o.nivelPicking ?? 1 })),
    };
  }
}
