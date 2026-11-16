import * as THREE from 'three';
import { OrbitControls } from 'three/examples/jsm/controls/OrbitControls.js';
import { PointerLockControls } from 'three/examples/jsm/controls/PointerLockControls.js';
import { GeometriaPlanta, ObjetoPlanta } from './galpao.model';

/** Posição desenhável no 3D — serve tanto pro endereço publicado quanto pra prévia do editor. */
export interface Posicao3d {
  codigo: string;
  setor: string;
  tipo: 'PICKING' | 'PULMAO';
  x: number;
  y: number;
  z: number;
  rotacao: number;
  largura: number;
  comprimento: number;
  altura: number;
  /** undefined = prévia (sem ocupação); null = sem capacidade (N/D). */
  ocupacao?: number | null;
  quantidade?: number;
  bloqueado?: boolean;
  /** Teve movimento físico recente — vira rota na camada "Rotas". */
  recente?: boolean;
}

/** Câmeras do Planta Log: isométrica, aérea, panorâmica, corte da estrutura e primeira pessoa (WASD). */
export type Camera = 'iso' | 'aerea' | 'panoramica' | 'corte' | 'primeira';

export interface Camadas {
  estruturas: boolean;
  posicoes: boolean;
  rotulos: boolean;
  rotas: boolean;
}

export interface OpcoesCena {
  /** Minimapa no canto e rótulo do endereço ao passar o mouse (visão); a prévia do editor não usa. */
  interativa?: boolean;
}

/** Altura do plano de corte (m): na câmera "Corte da estrutura" some tudo acima disso. */
const ALTURA_CORTE = 2.2;

const COR_SELECAO = '#f5d000';

/** Verde → amarelo → vermelho; acima de 100% fica vermelho escuro. */
export function corOcupacao(o: number): THREE.Color {
  if (o > 1) return new THREE.Color('#b42318');
  const c = new THREE.Color();
  if (o <= 0.7) c.lerpColors(new THREE.Color('#2e9e6b'), new THREE.Color('#57b98a'), o / 0.7);
  else c.lerpColors(new THREE.Color('#e0a40c'), new THREE.Color('#d9822b'), (o - 0.7) / 0.3);
  return c;
}

/**
 * Cena Three.js da planta: piso, estrutura (montantes e longarinas), docas, rótulos e as posições (endereços)
 * num InstancedMesh — uma draw call pra milhares de endereços. A cor de cada posição vem de [definirCores]
 * (a visão decide o modo); sem função, pinta pelo setor. O dono chama [destruir] no ngOnDestroy.
 */
export class Cena3d {
  private readonly renderer: THREE.WebGLRenderer;
  private readonly scene = new THREE.Scene();
  private readonly camera: THREE.PerspectiveCamera;
  private readonly controls: OrbitControls;
  private readonly primeiraPessoa: PointerLockControls;
  private readonly grupoPlanta = new THREE.Group();
  private readonly grupoEstrutura = new THREE.Group();
  private readonly grupoRotulos = new THREE.Group();
  private rotas: THREE.LineSegments | null = null;
  private posicoesMesh: THREE.InstancedMesh | null = null;
  private readonly selecaoCaixa = new THREE.Box3Helper(new THREE.Box3(), new THREE.Color(COR_SELECAO));
  private posicoes: Posicao3d[] = [];
  private geometria: GeometriaPlanta | null = null;
  private setores = new Map<string, string>();
  private corPara: ((p: Posicao3d, i: number) => string) | null = null;
  private destaques: Set<number> | null = null;
  private selecionado: number | null = null;
  private camadas: Camadas = { estruturas: true, posicoes: true, rotulos: true, rotas: false };
  private vista: Camera = 'iso';
  private readonly raycaster = new THREE.Raycaster();
  private readonly observer: ResizeObserver;
  private quadro = 0;
  private precisaRender = true;
  private ponteiroInicio: { x: number; y: number } | null = null;
  private sobre = -1;
  private readonly teclas = new Set<string>();
  private ultimoQuadro = performance.now();
  private readonly minimapa: HTMLCanvasElement | null = null;
  private readonly dica: HTMLDivElement | null = null;
  /** Cores do tema (variáveis --gp-* do componente; ver _galpao-comum.scss). */
  private cores = {
    palco: '#ece8f4', piso: '#f7f5fb', grade: '#ddd6ea', contorno: '#3d2c6b', rotulo: '#18181b',
    rotuloFundo: '#ffffff', montante: '#5b6b8a', longarina: '#d08a2e', doca: '#3d7f86', vazio: '#d9d4e4',
    area: '#e6dcc4', parede: '#b8b2c4',
  };

  aoSelecionar: ((indice: number | null) => void) | null = null;
  aoMudarVista: ((c: Camera) => void) | null = null;

  constructor(private readonly container: HTMLElement, opcoes: OpcoesCena = {}) {
    this.renderer = new THREE.WebGLRenderer({ antialias: true });
    this.renderer.setPixelRatio(Math.min(window.devicePixelRatio, 2));
    this.lerCores();
    container.appendChild(this.renderer.domElement);
    this.renderer.domElement.tabIndex = 0;
    this.renderer.domElement.setAttribute('aria-label', 'Planta 3D do armazém. Use a tabela para conferir os números.');

    this.camera = new THREE.PerspectiveCamera(46, 1, 0.1, 5000);
    this.controls = new OrbitControls(this.camera, this.renderer.domElement);
    this.controls.enableDamping = true;
    this.controls.dampingFactor = 0.09;
    this.controls.maxPolarAngle = Math.PI / 2 - 0.03;
    this.controls.addEventListener('change', () => (this.precisaRender = true));
    this.primeiraPessoa = new PointerLockControls(this.camera, this.renderer.domElement);
    this.primeiraPessoa.addEventListener('unlock', () => {
      this.teclas.clear();
      if (this.vista === 'primeira') this.definirCamera('iso');
    });

    this.scene.add(new THREE.HemisphereLight(0xffffff, 0x8a8494, 1.15));
    const sol = new THREE.DirectionalLight(0xffffff, 1.3);
    sol.position.set(60, 120, 40);
    this.scene.add(sol);
    const contraluz = new THREE.DirectionalLight(0xbcd7ff, 0.45);
    contraluz.position.set(-50, 30, -60);
    this.scene.add(contraluz);
    this.scene.add(this.grupoPlanta, this.grupoEstrutura, this.grupoRotulos);
    this.selecaoCaixa.visible = false;
    this.scene.add(this.selecaoCaixa);

    if (opcoes.interativa) {
      this.minimapa = document.createElement('canvas');
      this.minimapa.width = 180;
      this.minimapa.height = 140;
      this.minimapa.setAttribute('aria-label', 'Minimapa do armazém');
      this.minimapa.title = 'Clique para centralizar a câmera';
      Object.assign(this.minimapa.style, {
        position: 'absolute', right: '12px', bottom: '12px', width: '180px', height: '140px',
        border: '1px solid var(--oq-border)', borderRadius: 'var(--oq-radius-block)', background: 'var(--oq-surface)',
        cursor: 'crosshair', zIndex: '2',
      });
      this.minimapa.addEventListener('click', (e) => this.cliqueMinimapa(e));
      container.appendChild(this.minimapa);

      this.dica = document.createElement('div');
      this.dica.hidden = true;
      Object.assign(this.dica.style, {
        position: 'absolute', pointerEvents: 'none', zIndex: '3', padding: '4px 8px', whiteSpace: 'nowrap',
        font: '600 12px var(--oq-font-mono)', color: 'var(--oq-text-primary)', background: 'var(--oq-surface)',
        border: '1px solid var(--oq-border)', borderRadius: 'var(--oq-radius-input)', boxShadow: '0 2px 8px rgba(0,0,0,.18)',
      });
      container.appendChild(this.dica);
    }

    const el = this.renderer.domElement;
    el.addEventListener('pointerdown', (e) => (this.ponteiroInicio = { x: e.clientX, y: e.clientY }));
    el.addEventListener('pointerup', (e) => this.aoSoltar(e));
    el.addEventListener('pointermove', (e) => this.aoMover(e));
    el.addEventListener('pointerleave', () => this.esconderDica());
    window.addEventListener('keydown', this.teclaDesce);
    window.addEventListener('keyup', this.teclaSobe);

    this.observer = new ResizeObserver(() => this.redimensionar());
    this.observer.observe(container);
    this.redimensionar();
    this.loop();
  }

  /** Redesenha tudo. [manterCamera] = não reposiciona (atualização da prévia do editor ou depois de um movimento). */
  carregar(geometria: GeometriaPlanta | null, posicoes: Posicao3d[], manterCamera = false): void {
    this.geometria = geometria;
    this.posicoes = posicoes;
    this.setores = new Map((geometria?.setores ?? []).map((s) => [s.codigo, s.cor]));
    this.destaques = null;
    this.selecionado = null;
    this.selecaoCaixa.visible = false;
    this.lerCores();
    this.limpar(this.grupoPlanta);
    this.limpar(this.grupoEstrutura);
    this.limpar(this.grupoRotulos);
    if (geometria) this.montarPlanta(geometria);
    this.montarPosicoes();
    this.montarRotas();
    this.aplicarCamadas();
    if (!manterCamera) this.definirCamera('iso');
    this.precisaRender = true;
  }

  /**
   * Tema claro/escuro trocado com a tela aberta: relê as cores (--gp-*) e redesenha piso, estruturas, rótulos e
   * minimapa — câmera, seleção, destaque e camadas ficam como estavam.
   */
  aplicarTema(): void {
    this.lerCores();
    this.limpar(this.grupoPlanta);
    this.limpar(this.grupoEstrutura);
    this.limpar(this.grupoRotulos);
    if (this.geometria) this.montarPlanta(this.geometria);
    this.aplicarCamadas();
    this.pintar();
    this.precisaRender = true;
  }

  /** Função de cor por posição (modo da visão). null = cor do setor. */
  definirCores(fn: ((p: Posicao3d, i: number) => string) | null): void {
    this.corPara = fn;
    this.pintar();
  }

  /** Compatível com a prévia do editor: 'setor' = cor do setor. */
  definirModo(_modo: 'setor'): void {
    this.definirCores(null);
  }

  /** Realça [indices] e apaga o resto; enquadra o primeiro. Lista vazia = limpa. */
  destacar(indices: number[], enquadrar = true): void {
    this.destaques = indices.length ? new Set(indices) : null;
    this.pintar();
    if (enquadrar && indices.length) this.enquadrar(indices[0]);
  }

  selecionar(indice: number | null, enquadrar = false): void {
    this.selecionado = indice;
    if (indice == null) {
      this.selecaoCaixa.visible = false;
    } else {
      const p = this.posicoes[indice];
      const r = (p.rotacao * Math.PI) / 180;
      const w = Math.abs(p.largura * Math.cos(r)) + Math.abs(p.comprimento * Math.sin(r));
      const d = Math.abs(p.largura * Math.sin(r)) + Math.abs(p.comprimento * Math.cos(r));
      this.selecaoCaixa.box.setFromCenterAndSize(
        new THREE.Vector3(p.x, p.y + p.altura / 2 + 0.05, p.z),
        new THREE.Vector3(w + 0.15, p.altura + 0.15, d + 0.15),
      );
      this.selecaoCaixa.visible = true;
      if (enquadrar) this.enquadrar(indice);
    }
    this.pintar();
  }

  definirCamera(tipo: Camera): void {
    const g = this.geometria;
    const w = g?.largura ?? 40;
    const d = g?.profundidade ?? 30;
    const centro = new THREE.Vector3(w / 2, 0, d / 2);
    const dist = Math.max(w, d);
    if (this.primeiraPessoa.isLocked && tipo !== 'primeira') this.primeiraPessoa.unlock();
    this.vista = tipo;
    this.controls.enabled = tipo !== 'primeira';
    this.renderer.clippingPlanes = tipo === 'corte' ? [new THREE.Plane(new THREE.Vector3(0, -1, 0), ALTURA_CORTE)] : [];
    this.controls.target.copy(centro);
    switch (tipo) {
      case 'aerea':
        this.camera.position.set(w / 2, dist * 1.25 + 10, d / 2 + 0.01);
        break;
      case 'panoramica':
        this.camera.position.set(w / 2, Math.max(8, dist * 0.18), d + dist * 0.55);
        break;
      case 'corte':
        this.camera.position.set(w / 2 + dist * 0.5, dist * 0.42, d + dist * 0.5);
        break;
      case 'primeira': {
        // Entra pela primeira doca (ou pelo canto) na altura dos olhos.
        const doca = g?.objetos.find((o) => o.tipo === 'DOCA');
        this.camera.position.set(doca ? doca.x : 3, 1.7, doca ? Math.min(d - 1, Math.max(1, doca.z)) : 3);
        this.camera.lookAt(w / 2, 1.7, d / 2);
        this.primeiraPessoa.lock();
        break;
      }
      default:
        this.camera.position.set(w / 2 + dist * 0.62, dist * 0.62 + 6, d + dist * 0.62);
    }
    if (tipo !== 'primeira') {
      this.camera.lookAt(centro);
      this.controls.update();
    }
    this.pintar();
    this.aoMudarVista?.(tipo);
    this.precisaRender = true;
  }

  definirCamadas(c: Partial<Camadas>): void {
    this.camadas = { ...this.camadas, ...c };
    this.aplicarCamadas();
  }

  destruir(): void {
    cancelAnimationFrame(this.quadro);
    this.observer.disconnect();
    window.removeEventListener('keydown', this.teclaDesce);
    window.removeEventListener('keyup', this.teclaSobe);
    this.primeiraPessoa.unlock();
    this.primeiraPessoa.dispose();
    this.controls.dispose();
    this.limpar(this.grupoPlanta);
    this.limpar(this.grupoEstrutura);
    this.limpar(this.grupoRotulos);
    this.rotas?.geometry.dispose();
    this.posicoesMesh?.geometry.dispose();
    this.selecaoCaixa.geometry.dispose();
    this.renderer.dispose();
    this.renderer.domElement.remove();
    this.minimapa?.remove();
    this.dica?.remove();
  }

  // ─── montagem ────────────────────────────────────────────────────────────

  private montarPlanta(g: GeometriaPlanta): void {
    const piso = new THREE.Mesh(
      new THREE.PlaneGeometry(g.largura, g.profundidade),
      new THREE.MeshLambertMaterial({ color: this.cores.piso }),
    );
    piso.rotation.x = -Math.PI / 2;
    piso.position.set(g.largura / 2, 0, g.profundidade / 2);
    this.grupoPlanta.add(piso);

    // Grade a cada 5 m só dentro do piso.
    const pontos: number[] = [];
    for (let x = 0; x <= g.largura + 1e-6; x += 5) pontos.push(x, 0.005, 0, x, 0.005, g.profundidade);
    for (let z = 0; z <= g.profundidade + 1e-6; z += 5) pontos.push(0, 0.005, z, g.largura, 0.005, z);
    const gradeGeo = new THREE.BufferGeometry();
    gradeGeo.setAttribute('position', new THREE.Float32BufferAttribute(pontos, 3));
    this.grupoPlanta.add(new THREE.LineSegments(gradeGeo, new THREE.LineBasicMaterial({ color: this.cores.grade })));

    const contorno = new THREE.LineSegments(
      new THREE.EdgesGeometry(new THREE.BoxGeometry(g.largura, 0.01, g.profundidade)),
      new THREE.LineBasicMaterial({ color: this.cores.contorno }),
    );
    contorno.position.set(g.largura / 2, 0.01, g.profundidade / 2);
    this.grupoPlanta.add(contorno);

    const escala = Math.max(2.2, Math.min(5, Math.max(g.largura, g.profundidade) / 22));
    const montantes: THREE.Matrix4[] = [];
    const longarinas: THREE.Matrix4[] = [];
    for (const o of g.objetos) {
      if (o.tipo === 'ESTRUTURA') {
        this.estrutura(o, montantes, longarinas);
        const topo = this.local(o, 0, -o.profundidade / 2 - 0.8);
        this.rotulo(o.codigo, topo.x, o.altura + 0.9, topo.z, escala);
      } else {
        this.grupoPlanta.add(this.objeto3d(o));
        if (o.tipo === 'DOCA') this.rotulo(o.codigo, o.x, 1.4, o.z, escala * 0.9);
      }
    }
    this.instancias(montantes, this.cores.montante);
    this.instancias(longarinas, this.cores.longarina);
  }

  /** Ponto no mundo a partir de (dx na largura, dz no comprimento) local do objeto girado. */
  private local(o: ObjetoPlanta, dx: number, dz: number): { x: number; z: number } {
    const a = (o.rotacao * Math.PI) / 180;
    return { x: o.x + dx * Math.cos(a) - dz * Math.sin(a), z: o.z + dx * Math.sin(a) + dz * Math.cos(a) };
  }

  /** Montantes a cada módulo nos dois lados e longarinas por nível — como o porta-palete do Planta Log. */
  private estrutura(o: ObjetoPlanta, montantes: THREE.Matrix4[], longarinas: THREE.Matrix4[]): void {
    const q = new THREE.Quaternion().setFromAxisAngle(new THREE.Vector3(0, 1, 0), (-o.rotacao * Math.PI) / 180);
    const modulos = Math.max(1, Math.round(o.modulos || 1));
    const niveis = Math.max(1, Math.round(o.niveis || 1));
    for (let i = 0; i <= modulos; i++) {
      for (const lado of [-1, 1]) {
        const p = this.local(o, (lado * o.largura) / 2, -o.profundidade / 2 + (i * o.profundidade) / modulos);
        montantes.push(new THREE.Matrix4().compose(new THREE.Vector3(p.x, o.altura / 2, p.z), q, new THREE.Vector3(0.09, o.altura, 0.09)));
      }
    }
    for (let n = 0; n < niveis; n++) {
      for (const lado of [-1, 1]) {
        const p = this.local(o, (lado * o.largura) / 2, 0);
        const y = 0.08 + (n * o.altura) / niveis;
        longarinas.push(new THREE.Matrix4().compose(new THREE.Vector3(p.x, y, p.z), q, new THREE.Vector3(0.09, 0.09, o.profundidade)));
      }
    }
  }

  private instancias(lista: THREE.Matrix4[], cor: string): void {
    if (!lista.length) return;
    const mesh = new THREE.InstancedMesh(new THREE.BoxGeometry(1, 1, 1), new THREE.MeshLambertMaterial({ color: cor }), lista.length);
    lista.forEach((m, i) => mesh.setMatrixAt(i, m));
    mesh.instanceMatrix.needsUpdate = true;
    this.grupoEstrutura.add(mesh);
  }

  private objeto3d(o: ObjetoPlanta): THREE.Object3D {
    const grupo = new THREE.Group();
    grupo.position.set(o.x, 0, o.z);
    grupo.rotation.y = (-o.rotacao * Math.PI) / 180;
    const caixa = (h: number, cor: string, opacidade = 1) => {
      const m = new THREE.Mesh(
        new THREE.BoxGeometry(o.largura, h, o.profundidade),
        new THREE.MeshLambertMaterial({ color: cor, transparent: opacidade < 1, opacity: opacidade }),
      );
      m.position.y = h / 2;
      return m;
    };
    switch (o.tipo) {
      case 'CORREDOR':
        grupo.add(caixa(0.02, this.cores.grade));
        break;
      case 'AREA':
        grupo.add(caixa(0.02, this.cores.area, 0.7));
        break;
      case 'DOCA':
        grupo.add(caixa(0.12, this.cores.doca));
        break;
      case 'PAREDE':
        grupo.add(caixa(o.altura, this.cores.parede, 0.45));
        break;
    }
    return grupo;
  }

  /** Placa de texto (sprite) que sempre olha pra câmera. */
  private rotulo(texto: string, x: number, y: number, z: number, escala: number): void {
    const c = document.createElement('canvas');
    c.width = 320;
    c.height = 80;
    const ctx = c.getContext('2d');
    if (!ctx) return;
    ctx.fillStyle = this.cores.rotuloFundo;
    ctx.strokeStyle = this.cores.contorno;
    ctx.lineWidth = 3;
    ctx.beginPath();
    ctx.roundRect(3, 3, 314, 74, 12);
    ctx.fill();
    ctx.stroke();
    ctx.font = '600 34px system-ui, sans-serif';
    ctx.textAlign = 'center';
    ctx.textBaseline = 'middle';
    ctx.fillStyle = this.cores.rotulo;
    ctx.fillText(texto, 160, 42);
    const sprite = new THREE.Sprite(new THREE.SpriteMaterial({ map: new THREE.CanvasTexture(c), depthTest: false, transparent: true }));
    sprite.position.set(x, y, z);
    sprite.scale.set(escala, escala / 4, 1);
    sprite.renderOrder = 10;
    this.grupoRotulos.add(sprite);
  }

  private montarPosicoes(): void {
    if (this.posicoesMesh) {
      this.scene.remove(this.posicoesMesh);
      this.posicoesMesh.geometry.dispose();
      (this.posicoesMesh.material as THREE.Material).dispose();
      this.posicoesMesh = null;
    }
    if (this.posicoes.length === 0) return;
    const mesh = new THREE.InstancedMesh(
      new THREE.BoxGeometry(1, 1, 1),
      new THREE.MeshLambertMaterial({ color: 0xffffff }),
      this.posicoes.length,
    );
    const m = new THREE.Matrix4();
    const q = new THREE.Quaternion();
    const eixoY = new THREE.Vector3(0, 1, 0);
    this.posicoes.forEach((p, i) => {
      q.setFromAxisAngle(eixoY, (-p.rotacao * Math.PI) / 180);
      // Altura do palete pela ocupação (como no Planta Log): vazio = base fina; prévia = cheio.
      let fracao = 1;
      if (p.ocupacao !== undefined) {
        const qtd = p.quantidade ?? 0;
        fracao = qtd > 0 ? Math.max(0.22, Math.min(1, p.ocupacao ?? 0.6)) : 0.06;
      }
      const h = p.altura * fracao * 0.94;
      m.compose(new THREE.Vector3(p.x, p.y + h / 2 + 0.08, p.z), q, new THREE.Vector3(p.largura * 0.9, h, p.comprimento * 0.9));
      mesh.setMatrixAt(i, m);
    });
    mesh.instanceMatrix.needsUpdate = true;
    mesh.computeBoundingSphere();
    this.posicoesMesh = mesh;
    this.scene.add(mesh);
    this.pintar();
  }

  /** Rotas de eventos físicos: da primeira doca até cada endereço com movimento recente (até 80). */
  private montarRotas(): void {
    if (this.rotas) {
      this.scene.remove(this.rotas);
      this.rotas.geometry.dispose();
      (this.rotas.material as THREE.Material).dispose();
      this.rotas = null;
    }
    const doca = this.geometria?.objetos.find((o) => o.tipo === 'DOCA');
    const origem = new THREE.Vector3(doca?.x ?? 2, 0.4, doca?.z ?? 2);
    const pontos: THREE.Vector3[] = [];
    for (const p of this.posicoes.filter((x) => x.recente).slice(0, 80)) pontos.push(origem, new THREE.Vector3(p.x, 0.5, p.z));
    this.rotas = new THREE.LineSegments(
      new THREE.BufferGeometry().setFromPoints(pontos),
      new THREE.LineBasicMaterial({ color: '#e0a40c', transparent: true, opacity: 0.75 }),
    );
    this.scene.add(this.rotas);
  }

  private aplicarCamadas(): void {
    this.grupoEstrutura.visible = this.camadas.estruturas;
    this.grupoRotulos.visible = this.camadas.rotulos;
    if (this.posicoesMesh) this.posicoesMesh.visible = this.camadas.posicoes;
    if (this.rotas) this.rotas.visible = this.camadas.rotas;
    this.precisaRender = true;
  }

  private corBase(p: Posicao3d, i: number): string {
    if (this.corPara) return this.corPara(p, i);
    return this.setores.get(p.setor) ?? this.cores.vazio;
  }

  private pintar(): void {
    const mesh = this.posicoesMesh;
    if (!mesh) return;
    const apagado = new THREE.Color(this.cores.piso).lerp(new THREE.Color(this.cores.grade), 0.6);
    const c = new THREE.Color();
    this.posicoes.forEach((p, i) => {
      c.set(this.corBase(p, i));
      if (i === this.selecionado) c.set(COR_SELECAO);
      else if (this.destaques && !this.destaques.has(i)) c.copy(apagado);
      else if (this.vista === 'corte' && p.y > ALTURA_CORTE) c.multiplyScalar(0.25);
      mesh.setColorAt(i, c);
    });
    if (mesh.instanceColor) mesh.instanceColor.needsUpdate = true;
    this.desenharMinimapa();
    this.precisaRender = true;
  }

  private enquadrar(indice: number): void {
    const p = this.posicoes[indice];
    if (!p) return;
    const alvo = new THREE.Vector3(p.x, p.y + p.altura / 2, p.z);
    const dir = this.camera.position.clone().sub(this.controls.target).normalize();
    this.controls.target.copy(alvo);
    this.camera.position.copy(alvo.clone().add(dir.multiplyScalar(Math.max(24, p.altura * 14))));
    this.controls.update();
    this.precisaRender = true;
  }

  // ─── minimapa ────────────────────────────────────────────────────────────

  private desenharMinimapa(): void {
    const cv = this.minimapa;
    const g = this.geometria;
    const ctx = cv?.getContext('2d');
    if (!cv || !ctx) return;
    const W = cv.width;
    const H = cv.height;
    ctx.clearRect(0, 0, W, H);
    if (!g) return;
    const m = 8;
    const esc = Math.min((W - 2 * m) / g.largura, (H - 2 * m) / g.profundidade);
    const ox = (W - g.largura * esc) / 2;
    const oz = (H - g.profundidade * esc) / 2;
    ctx.fillStyle = this.cores.piso;
    ctx.fillRect(ox, oz, g.largura * esc, g.profundidade * esc);
    ctx.strokeStyle = this.cores.contorno;
    ctx.lineWidth = 1;
    ctx.strokeRect(ox + 0.5, oz + 0.5, g.largura * esc - 1, g.profundidade * esc - 1);
    for (const o of g.objetos) {
      if (o.tipo !== 'DOCA') continue;
      ctx.fillStyle = this.cores.doca;
      ctx.fillRect(ox + (o.x - o.largura / 2) * esc, oz + (o.z - o.profundidade / 2) * esc, Math.max(2, o.largura * esc), Math.max(2, o.profundidade * esc));
    }
    const lado = Math.max(2, Math.min(5, esc * 1.1));
    // Só o primeiro nível (vista de cima), na cor do modo atual.
    this.posicoes.forEach((p, i) => {
      if (p.y > 0.5) return;
      ctx.fillStyle = this.destaques && !this.destaques.has(i) ? this.cores.grade : this.corBase(p, i);
      ctx.fillRect(ox + p.x * esc - lado / 2, oz + p.z * esc - lado / 2, lado, lado);
    });
    if (this.selecionado != null) {
      const p = this.posicoes[this.selecionado];
      ctx.strokeStyle = COR_SELECAO;
      ctx.lineWidth = 2;
      ctx.strokeRect(ox + p.x * esc - 5, oz + p.z * esc - 5, 10, 10);
    }
    // Para onde a câmera olha.
    const t = this.controls.target;
    ctx.fillStyle = this.cores.contorno;
    ctx.beginPath();
    ctx.arc(ox + t.x * esc, oz + t.z * esc, 3, 0, Math.PI * 2);
    ctx.fill();
  }

  private cliqueMinimapa(e: MouseEvent): void {
    const g = this.geometria;
    const cv = this.minimapa;
    if (!g || !cv || this.vista === 'primeira') return;
    const r = cv.getBoundingClientRect();
    const W = cv.width;
    const H = cv.height;
    const esc = Math.min((W - 16) / g.largura, (H - 16) / g.profundidade);
    const ox = (W - g.largura * esc) / 2;
    const oz = (H - g.profundidade * esc) / 2;
    const x = ((e.clientX - r.left) * (W / r.width) - ox) / esc;
    const z = ((e.clientY - r.top) * (H / r.height) - oz) / esc;
    const novo = new THREE.Vector3(Math.min(g.largura, Math.max(0, x)), 0, Math.min(g.profundidade, Math.max(0, z)));
    const delta = novo.clone().sub(this.controls.target);
    this.controls.target.add(delta);
    this.camera.position.add(delta);
    this.controls.update();
    this.desenharMinimapa();
    this.precisaRender = true;
  }

  // ─── interação / ciclo ───────────────────────────────────────────────────

  private indiceNoPonteiro(e: PointerEvent): number {
    if (!this.posicoesMesh || !this.camadas.posicoes) return -1;
    const r = this.renderer.domElement.getBoundingClientRect();
    const ndc = new THREE.Vector2(((e.clientX - r.left) / r.width) * 2 - 1, -((e.clientY - r.top) / r.height) * 2 + 1);
    this.raycaster.setFromCamera(ndc, this.camera);
    const hits = this.raycaster.intersectObject(this.posicoesMesh);
    // No corte, ignora o que ficou acima do plano.
    const hit = this.vista === 'corte' ? hits.find((h) => h.point.y <= ALTURA_CORTE) : hits[0];
    return hit?.instanceId ?? -1;
  }

  private aoMover(e: PointerEvent): void {
    if (this.primeiraPessoa.isLocked || !this.dica || e.buttons) {
      this.esconderDica();
      return;
    }
    const i = this.indiceNoPonteiro(e);
    this.sobre = i;
    this.renderer.domElement.style.cursor = i >= 0 ? 'pointer' : 'grab';
    if (i < 0) return this.esconderDica();
    const p = this.posicoes[i];
    const r = this.renderer.domElement.getBoundingClientRect();
    this.dica.textContent = `${p.codigo} · ${p.quantidade == null ? 'N/D' : p.quantidade.toLocaleString('pt-BR', { maximumFractionDigits: 3 })} un`;
    this.dica.hidden = false;
    this.dica.style.left = `${Math.min(e.clientX - r.left + 14, r.width - 220)}px`;
    this.dica.style.top = `${Math.max(8, e.clientY - r.top - 34)}px`;
  }

  private esconderDica(): void {
    if (this.dica) this.dica.hidden = true;
  }

  private aoSoltar(e: PointerEvent): void {
    const inicio = this.ponteiroInicio;
    this.ponteiroInicio = null;
    // Arrastar (orbitar) não seleciona.
    if (this.primeiraPessoa.isLocked || !inicio || Math.hypot(e.clientX - inicio.x, e.clientY - inicio.y) > 5) return;
    const i = this.indiceNoPonteiro(e);
    const indice = i >= 0 ? i : null;
    this.selecionar(indice);
    this.aoSelecionar?.(indice);
  }

  private readonly teclaDesce = (e: KeyboardEvent): void => {
    if (this.primeiraPessoa.isLocked) this.teclas.add(e.code);
  };

  private readonly teclaSobe = (e: KeyboardEvent): void => {
    this.teclas.delete(e.code);
  };

  private redimensionar(): void {
    const w = Math.max(this.container.clientWidth, 1);
    const h = Math.max(this.container.clientHeight, 1);
    this.renderer.setSize(w, h, false);
    this.renderer.domElement.style.width = '100%';
    this.renderer.domElement.style.height = '100%';
    this.camera.aspect = w / h;
    this.camera.updateProjectionMatrix();
    this.precisaRender = true;
  }

  private loop = (): void => {
    this.quadro = requestAnimationFrame(this.loop);
    const agora = performance.now();
    const dt = Math.min((agora - this.ultimoQuadro) / 1000, 0.06);
    this.ultimoQuadro = agora;
    let mudou = false;
    if (this.primeiraPessoa.isLocked) {
      const passo = dt * (this.teclas.has('ShiftLeft') || this.teclas.has('ShiftRight') ? 10 : 5);
      if (this.teclas.has('KeyW')) this.primeiraPessoa.moveForward(passo);
      if (this.teclas.has('KeyS')) this.primeiraPessoa.moveForward(-passo);
      if (this.teclas.has('KeyA')) this.primeiraPessoa.moveRight(-passo);
      if (this.teclas.has('KeyD')) this.primeiraPessoa.moveRight(passo);
      const g = this.geometria;
      if (g) {
        this.camera.position.x = THREE.MathUtils.clamp(this.camera.position.x, 0.5, g.largura - 0.5);
        this.camera.position.z = THREE.MathUtils.clamp(this.camera.position.z, 0.5, g.profundidade - 0.5);
      }
      this.camera.position.y = 1.7;
      mudou = true;
    } else {
      mudou = this.controls.update();
      if (mudou) this.desenharMinimapaSuave();
    }
    if (mudou || this.precisaRender) {
      this.renderer.render(this.scene, this.camera);
      this.precisaRender = false;
    }
  };

  private ultimoMinimapa = 0;
  /** Minimapa acompanha a câmera, no máximo ~8 vezes por segundo. */
  private desenharMinimapaSuave(): void {
    const agora = performance.now();
    if (agora - this.ultimoMinimapa < 120) return;
    this.ultimoMinimapa = agora;
    this.desenharMinimapa();
  }

  private lerCores(): void {
    const css = getComputedStyle(this.container);
    const ler = (v: string, padrao: string) => css.getPropertyValue(v).trim() || padrao;
    this.cores = {
      palco: ler('--gp-palco', this.cores.palco),
      piso: ler('--gp-piso', this.cores.piso),
      grade: ler('--gp-grade', this.cores.grade),
      contorno: ler('--gp-contorno', this.cores.contorno),
      rotulo: ler('--gp-rotulo', this.cores.rotulo),
      rotuloFundo: ler('--gp-rotulo-fundo', this.cores.rotuloFundo),
      montante: ler('--gp-montante', this.cores.montante),
      longarina: ler('--gp-longarina', this.cores.longarina),
      doca: ler('--gp-doca', this.cores.doca),
      vazio: ler('--gp-vazio', this.cores.vazio),
      area: ler('--gp-area', this.cores.area),
      parede: ler('--gp-parede', this.cores.parede),
    };
    this.renderer.setClearColor(new THREE.Color(this.cores.palco));
  }

  private limpar(grupo: THREE.Group): void {
    grupo.traverse((o) => {
      const m = o as THREE.Mesh;
      m.geometry?.dispose();
      const mat = m.material as THREE.Material | THREE.Material[] | undefined;
      const lista = Array.isArray(mat) ? mat : mat ? [mat] : [];
      lista.forEach((x) => {
        (x as THREE.SpriteMaterial).map?.dispose();
        x.dispose();
      });
    });
    grupo.clear();
  }
}
