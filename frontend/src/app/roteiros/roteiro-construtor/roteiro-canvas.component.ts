import { AfterViewInit, Component, ElementRef, HostListener, NgZone, OnDestroy, ViewChild, computed, effect, inject, signal, untracked } from '@angular/core';
import { OqIconComponent } from '../../shared/icons/oq-icon.component';
import { CONDICOES, Etapa, Ramo, TipoBloco, definicao, ehEstrutural, tamanhoEtapa } from '../roteiros.model';
import { RoteiroEditorStore } from './roteiro-editor.store';

/** Tipo MIME do arrastar-e-soltar da biblioteca para o canvas. */
export const MIME_BLOCO = 'application/x-roteiro-bloco';

/** O SVG das ligações é grande e deslocado para cobrir coordenadas negativas sem recálculo. */
const DESLOCAMENTO = 4000;
const GRADE = 8;
const ZOOM_MIN = 0.35;
const ZOOM_MAX = 1.6;

type Gesto =
  | { tipo: 'pan'; x0: number; y0: number; panX: number; panY: number; moveu: boolean }
  | { tipo: 'mover'; id: string; offX: number; offY: number; moveu: boolean }
  | { tipo: 'ligar'; de: string; ramo: Ramo | null; x1: number; y1: number };

interface LigacaoDesenho {
  id: string;
  d: string;
  rotulo: string | null;
  rx: number;
  ry: number;
  mx: number;
  my: number;
}

/**
 * PROTÓTIPO — canvas do Construtor de Roteiros. SVG + HTML posicionado, sem biblioteca de fluxograma
 * (nenhuma dependência nova no package.json). Pan arrastando o fundo, zoom na roda do mouse ou nos
 * botões, ligar arrastando a porta de saída até outra etapa.
 */
@Component({
  selector: 'app-roteiro-canvas',
  standalone: true,
  imports: [OqIconComponent],
  templateUrl: './roteiro-canvas.component.html',
  styleUrl: './roteiro-canvas.component.scss',
})
export class RoteiroCanvasComponent implements AfterViewInit, OnDestroy {
  readonly store = inject(RoteiroEditorStore);
  private readonly zone = inject(NgZone);

  @ViewChild('viewport', { static: true }) viewport!: ElementRef<HTMLDivElement>;

  readonly deslocamento = DESLOCAMENTO;
  readonly zoom = signal(1);
  readonly pan = signal({ x: 40, y: 40 });
  readonly ligandoPara = signal<{ x: number; y: number } | null>(null);
  readonly arrastandoBloco = signal(false);
  private gesto: Gesto | null = null;
  private timerMensagem: ReturnType<typeof setTimeout> | null = null;
  private observador: ResizeObserver | null = null;
  private centralizadoPara: string | null = null;
  private enquadrado = false;

  readonly transformacao = computed(() => {
    const p = this.pan();
    return `translate(${p.x}px, ${p.y}px) scale(${this.zoom()})`;
  });

  readonly etapas = computed(() => this.store.roteiro()?.etapas ?? []);
  readonly editavel = computed(() => !this.store.somenteLeitura() && !this.store.painelSimulacao());
  readonly simulando = computed(() => this.store.painelSimulacao());

  readonly ligacoes = computed<LigacaoDesenho[]>(() => {
    const r = this.store.roteiro();
    if (!r) return [];
    const porId = new Map(r.etapas.map((e) => [e.id, e]));
    const lista: LigacaoDesenho[] = [];
    for (const c of r.conexoes) {
      const de = porId.get(c.de);
      const para = porId.get(c.para);
      if (!de || !para) continue;
      const s = this.porta(de, c.ramo);
      const t = this.entrada(para);
      lista.push({
        id: c.id,
        ...this.curva(s, t),
        rotulo: c.ramo === 'SIM' ? 'Sim' : c.ramo === 'NAO' ? 'Não' : null,
        rx: s.x + (c.ramo === 'NAO' ? 8 : -8),
        ry: s.y + 18,
      });
    }
    return lista;
  });

  readonly linhaTemporaria = computed(() => {
    const g = this.gesto;
    const fim = this.ligandoPara();
    if (!fim || g?.tipo !== 'ligar') return null;
    return this.curva({ x: g.x1, y: g.y1 }, fim).d;
  });

  constructor() {
    // Mensagens do store somem sozinhas.
    effect(() => {
      const m = this.store.mensagem();
      if (!m) return;
      if (this.timerMensagem) clearTimeout(this.timerMensagem);
      this.timerMensagem = setTimeout(() => this.store.mensagem.set(null), 4000);
    });
    // Na simulação, a etapa atual fora da área visível traz o canvas até ela.
    effect(() => {
      // Só a troca de etapa dispara; pan e zoom do usuário não puxam o canvas de volta.
      const id = this.store.caminho().atual;
      const e = id ? untracked(() => this.etapas().find((x) => x.id === id)) : null;
      const el = this.viewport?.nativeElement;
      if (!e || !el) return;
      const rect = el.getBoundingClientRect();
      const z = untracked(this.zoom);
      const p = untracked(this.pan);
      const t = tamanhoEtapa(e.tipo);
      const topo = e.y * z + p.y;
      const base = (e.y + t.h) * z + p.y;
      if (topo < 24 || base > rect.height - 72) {
        queueMicrotask(() => this.pan.set({ x: p.x, y: rect.height / 2 - (e.y + t.h / 2) * z }));
      }
    }, { allowSignalWrites: true });
    // Centraliza quando outro roteiro é carregado.
    effect(() => {
      const r = this.store.roteiro();
      if (r && r.id !== this.centralizadoPara) {
        this.centralizadoPara = r.id;
        queueMicrotask(() => this.centralizar());
      }
    });
  }

  ngAfterViewInit(): void {
    // A área pode nascer com largura 0 (layout ainda montando): enquadra quando ganhar tamanho.
    this.observador = new ResizeObserver(() => {
      if (!this.enquadrado) this.zone.run(() => this.centralizar());
    });
    this.observador.observe(this.viewport.nativeElement);
  }

  ngOnDestroy(): void {
    this.observador?.disconnect();
    if (this.timerMensagem) clearTimeout(this.timerMensagem);
  }

  // ─── Geometria ────────────────────────────────────────────

  tamanho(e: Etapa) {
    return tamanhoEtapa(e.tipo);
  }

  private porta(e: Etapa, ramo: Ramo | null): { x: number; y: number } {
    const t = tamanhoEtapa(e.tipo);
    const frac = e.tipo === 'CONDICAO' ? (ramo === 'NAO' ? 0.75 : 0.25) : 0.5;
    return { x: e.x + t.w * frac, y: e.y + t.h };
  }

  private entrada(e: Etapa): { x: number; y: number } {
    return { x: e.x + tamanhoEtapa(e.tipo).w / 2, y: e.y };
  }

  private curva(s: { x: number; y: number }, t: { x: number; y: number }) {
    const dy = t.y - s.y;
    const k = dy > 24 ? Math.max(32, dy / 2) : 80 + Math.min(120, Math.abs(dy) / 3);
    const c1 = { x: s.x, y: s.y + k };
    const c2 = { x: t.x, y: t.y - k };
    return {
      d: `M${s.x} ${s.y} C${c1.x} ${c1.y} ${c2.x} ${c2.y} ${t.x} ${t.y}`,
      mx: (s.x + 3 * c1.x + 3 * c2.x + t.x) / 8,
      my: (s.y + 3 * c1.y + 3 * c2.y + t.y) / 8,
    };
  }

  private paraMundo(clientX: number, clientY: number): { x: number; y: number } {
    const rect = this.viewport.nativeElement.getBoundingClientRect();
    const p = this.pan();
    const z = this.zoom();
    return { x: (clientX - rect.left - p.x) / z, y: (clientY - rect.top - p.y) / z };
  }

  // ─── Textos dos blocos ────────────────────────────────────

  icone(tipo: TipoBloco) {
    return definicao(tipo).icone;
  }

  conceitual(tipo: TipoBloco): boolean {
    return definicao(tipo).situacao === 'CONCEITUAL' && tipo !== 'CONDICAO';
  }

  subtitulo(e: Etapa): string {
    if (e.tipo === 'CONDICAO') return e.config.pergunta ? CONDICOES[e.config.pergunta].pergunta : 'Escolha a pergunta';
    if (ehEstrutural(e.tipo)) return '';
    const c = e.config;
    if (c.execucao === 'CONDICIONAL') return c.condicao ? `Só ${CONDICOES[c.condicao].quando}` : 'Condicional: escolha quando';
    const execucao = c.execucao === 'OPCIONAL' ? 'Opcional' : definicao(e.tipo).rotulo;
    return `${execucao} · ${c.aoConcluir === 'AVANCAR' ? 'avança sozinha' : 'operador confirma'}`;
  }

  categoria(e: Etapa): string {
    return definicao(e.tipo).categoria.toLowerCase();
  }

  /** O rótulo Sim/Não da porta só aparece enquanto o ramo não tem ligação (depois, a ligação mostra). */
  ramoLivre(e: Etapa, ramo: Ramo): boolean {
    return !this.store.roteiro()?.conexoes.some((c) => c.de === e.id && c.ramo === ramo);
  }

  nivel(id: string) {
    return this.store.nivelPorEtapa().get(id);
  }

  sim(id: string) {
    return this.store.caminho().etapas.get(id);
  }

  percorrida(id: string): boolean {
    return this.store.caminho().conexoes.has(id);
  }

  marcador(id: string): string {
    if (this.percorrida(id)) return 'url(#rt-seta-sim)';
    return this.store.selecao()?.id === id ? 'url(#rt-seta-sel)' : 'url(#rt-seta)';
  }

  // ─── Seleção e gestos ─────────────────────────────────────

  fundoDown(ev: PointerEvent): void {
    if (ev.button !== 0) return;
    this.viewport.nativeElement.focus({ preventScroll: true });
    const p = this.pan();
    this.gesto = { tipo: 'pan', x0: ev.clientX, y0: ev.clientY, panX: p.x, panY: p.y, moveu: false };
  }

  etapaDown(ev: PointerEvent, e: Etapa): void {
    if (ev.button !== 0) return;
    ev.stopPropagation();
    this.viewport.nativeElement.focus({ preventScroll: true });
    if (this.simulando()) return;
    this.store.selecao.set({ tipo: 'etapa', id: e.id });
    if (!this.editavel()) return;
    const m = this.paraMundo(ev.clientX, ev.clientY);
    this.gesto = { tipo: 'mover', id: e.id, offX: m.x - e.x, offY: m.y - e.y, moveu: false };
  }

  portaDown(ev: PointerEvent, e: Etapa, ramo: Ramo | null): void {
    if (ev.button !== 0 || !this.editavel()) return;
    ev.stopPropagation();
    const s = this.porta(e, ramo);
    this.gesto = { tipo: 'ligar', de: e.id, ramo, x1: s.x, y1: s.y };
    this.ligandoPara.set(s);
  }

  ligacaoDown(ev: PointerEvent, id: string): void {
    if (ev.button !== 0) return;
    ev.stopPropagation();
    if (this.simulando()) return;
    this.viewport.nativeElement.focus({ preventScroll: true });
    this.store.selecao.set({ tipo: 'conexao', id });
  }

  @HostListener('window:pointermove', ['$event'])
  aoMover(ev: PointerEvent): void {
    const g = this.gesto;
    if (!g) return;
    if (g.tipo === 'pan') {
      const dx = ev.clientX - g.x0;
      const dy = ev.clientY - g.y0;
      if (Math.abs(dx) + Math.abs(dy) > 3) g.moveu = true;
      this.pan.set({ x: g.panX + dx, y: g.panY + dy });
    } else if (g.tipo === 'mover') {
      const m = this.paraMundo(ev.clientX, ev.clientY);
      const x = Math.round((m.x - g.offX) / GRADE) * GRADE;
      const y = Math.round((m.y - g.offY) / GRADE) * GRADE;
      g.moveu = true;
      this.store.moverEtapa(g.id, x, y);
    } else {
      this.ligandoPara.set(this.paraMundo(ev.clientX, ev.clientY));
    }
  }

  @HostListener('window:pointerup', ['$event'])
  aoSoltar(ev: PointerEvent): void {
    const g = this.gesto;
    this.gesto = null;
    if (!g) return;
    if (g.tipo === 'pan' && !g.moveu && !this.simulando()) {
      this.store.selecao.set(null);
    } else if (g.tipo === 'ligar') {
      this.ligandoPara.set(null);
      const alvo = (document.elementFromPoint(ev.clientX, ev.clientY) as HTMLElement | null)?.closest('[data-etapa-id]');
      const id = alvo?.getAttribute('data-etapa-id');
      if (id) this.store.conectar(g.de, id, g.ramo);
      else this.store.avisar('Solte a ligação em cima de uma etapa.');
    }
  }

  tecla(ev: KeyboardEvent): void {
    if (!this.editavel()) return;
    if (ev.key === 'Delete' || ev.key === 'Backspace') {
      ev.preventDefault();
      this.store.excluirSelecao();
    } else if (ev.key === 'Escape') {
      this.store.selecao.set(null);
    }
  }

  // ─── Biblioteca → canvas ──────────────────────────────────

  arrastoSobre(ev: DragEvent): void {
    if (!this.editavel() || !ev.dataTransfer?.types.includes(MIME_BLOCO)) return;
    ev.preventDefault();
    ev.dataTransfer.dropEffect = 'copy';
    this.arrastandoBloco.set(true);
  }

  soltarBloco(ev: DragEvent): void {
    this.arrastandoBloco.set(false);
    const tipo = ev.dataTransfer?.getData(MIME_BLOCO) as TipoBloco | undefined;
    if (!tipo || !this.editavel()) return;
    ev.preventDefault();
    this.store.selecao.set(null);
    this.store.adicionarEtapa(tipo, this.paraMundo(ev.clientX, ev.clientY));
  }

  // ─── Zoom ─────────────────────────────────────────────────

  roda(ev: WheelEvent): void {
    ev.preventDefault();
    const rect = this.viewport.nativeElement.getBoundingClientRect();
    this.zoomEm(ev.deltaY < 0 ? 1.1 : 1 / 1.1, ev.clientX - rect.left, ev.clientY - rect.top);
  }

  aproximar(fator: number): void {
    const rect = this.viewport.nativeElement.getBoundingClientRect();
    this.zoomEm(fator, rect.width / 2, rect.height / 2);
  }

  private zoomEm(fator: number, cx: number, cy: number): void {
    const z = this.zoom();
    const nz = Math.min(ZOOM_MAX, Math.max(ZOOM_MIN, z * fator));
    const p = this.pan();
    this.pan.set({ x: cx - ((cx - p.x) * nz) / z, y: cy - ((cy - p.y) * nz) / z });
    this.zoom.set(nz);
  }

  /** Enquadra todas as etapas na área visível. */
  centralizar(): void {
    const etapas = this.etapas();
    const el = this.viewport?.nativeElement;
    if (!el) return;
    const rect = el.getBoundingClientRect();
    if (!etapas.length || rect.width === 0) {
      this.zoom.set(1);
      this.pan.set({ x: rect.width / 2 - 108, y: 48 });
      return;
    }
    const minX = Math.min(...etapas.map((e) => e.x));
    const minY = Math.min(...etapas.map((e) => e.y));
    const maxX = Math.max(...etapas.map((e) => e.x + tamanhoEtapa(e.tipo).w));
    const maxY = Math.max(...etapas.map((e) => e.y + tamanhoEtapa(e.tipo).h));
    this.enquadrado = true;
    // Roteiro alto não encolhe até ficar ilegível: abaixo de 80% ele fica alinhado pelo topo e o resto
    // se vê arrastando o fundo.
    const margem = 48;
    const caber = Math.min((rect.width - margem * 2) / (maxX - minX), (rect.height - margem * 2) / (maxY - minY));
    const z = Math.min(1, Math.max(0.8, caber));
    this.zoom.set(z);
    const altura = (maxY - minY) * z;
    this.pan.set({
      x: rect.width / 2 - ((minX + maxX) / 2) * z,
      y: altura + margem * 2 <= rect.height ? rect.height / 2 - ((minY + maxY) / 2) * z : margem - minY * z,
    });
  }

  organizar(): void {
    this.store.organizar();
    queueMicrotask(() => this.centralizar());
  }

  zoomPercentual(): number {
    return Math.round(this.zoom() * 100);
  }
}
