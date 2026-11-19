import { Injectable, computed, signal } from '@angular/core';
import {
  Cenario,
  NivelValidacao,
  PassoSimulacao,
  ResultadoSimulacao,
  entradas,
  organizar,
  saidas,
  simular,
  validar,
} from '../roteiro-logica';
import {
  ConfigEtapa,
  Conexao,
  Etapa,
  Ramo,
  Roteiro,
  TipoBloco,
  configPadrao,
  definicao,
  novoId,
  tamanhoEtapa,
} from '../roteiros.model';

export type Selecao = { tipo: 'etapa'; id: string } | { tipo: 'conexao'; id: string };

export interface EstadoSimulacao {
  cenario: Cenario;
  resultado: ResultadoSimulacao;
  /** Passos já mostrados: 0 = nenhum; passos.length = terminou. */
  mostrados: number;
}

/**
 * PROTÓTIPO — estado do Construtor de Roteiros (um por tela; provido no componente). Só mexe no
 * objeto em memória; gravar é com o RoteirosDemoService (localStorage).
 */
@Injectable()
export class RoteiroEditorStore {
  readonly roteiro = signal<Roteiro | null>(null);
  readonly somenteLeitura = signal(false);
  readonly selecao = signal<Selecao | null>(null);
  readonly mensagem = signal<{ texto: string; tom: 'info' | 'erro' } | null>(null);
  readonly simulacao = signal<EstadoSimulacao | null>(null);
  /** Painel do simulador aberto (com ou sem simulação em andamento) — o canvas fica só leitura. */
  readonly painelSimulacao = signal(false);
  private readonly salvoJson = signal('');

  readonly validacoes = computed(() => {
    const r = this.roteiro();
    return r ? validar(r) : [];
  });
  readonly erros = computed(() => this.validacoes().filter((v) => v.nivel === 'ERRO').length);
  readonly avisos = computed(() => this.validacoes().filter((v) => v.nivel === 'AVISO').length);

  /** Pior nível de validação por etapa — o canvas pinta a borda do bloco. */
  readonly nivelPorEtapa = computed(() => {
    const m = new Map<string, NivelValidacao>();
    for (const v of this.validacoes()) {
      if (!v.etapaId) continue;
      if (m.get(v.etapaId) !== 'ERRO') m.set(v.etapaId, v.nivel);
    }
    return m;
  });

  readonly alterado = computed(() => {
    const r = this.roteiro();
    return !!r && JSON.stringify(r) !== this.salvoJson();
  });

  readonly etapaSelecionada = computed(() => {
    const s = this.selecao();
    const r = this.roteiro();
    return s?.tipo === 'etapa' && r ? (r.etapas.find((e) => e.id === s.id) ?? null) : null;
  });

  readonly conexaoSelecionada = computed(() => {
    const s = this.selecao();
    const r = this.roteiro();
    return s?.tipo === 'conexao' && r ? (r.conexoes.find((c) => c.id === s.id) ?? null) : null;
  });

  /** Passos já percorridos na simulação — o canvas destaca o caminho. */
  readonly passosMostrados = computed<PassoSimulacao[]>(() => {
    const s = this.simulacao();
    return s ? s.resultado.passos.slice(0, s.mostrados) : [];
  });

  readonly passoAtual = computed<PassoSimulacao | null>(() => {
    const p = this.passosMostrados();
    return p.length ? p[p.length - 1] : null;
  });

  readonly caminho = computed(() => {
    const passos = this.passosMostrados();
    const etapas = new Map<string, PassoSimulacao['resultado']>();
    const conexoes = new Set<string>();
    passos.forEach((p, i) => {
      etapas.set(p.etapaId, p.resultado);
      // A ligação do último passo mostrado ainda não foi percorrida.
      if (p.conexaoId && i < passos.length - 1) conexoes.add(p.conexaoId);
    });
    return { etapas, conexoes, atual: this.passoAtual()?.etapaId ?? null };
  });

  carregar(r: Roteiro, somenteLeitura: boolean): void {
    this.roteiro.set(r);
    this.salvoJson.set(JSON.stringify(r));
    this.somenteLeitura.set(somenteLeitura);
    this.selecao.set(null);
    this.simulacao.set(null);
    this.painelSimulacao.set(false);
  }

  marcarSalvo(r: Roteiro): void {
    this.roteiro.set(r);
    this.salvoJson.set(JSON.stringify(r));
  }

  avisar(texto: string, tom: 'info' | 'erro' = 'info'): void {
    this.mensagem.set({ texto, tom });
  }

  private alterar(fn: (r: Roteiro) => Roteiro): void {
    const r = this.roteiro();
    if (!r || this.somenteLeitura()) return;
    this.roteiro.set(fn(r));
  }

  atualizarRoteiro(patch: Partial<Pick<Roteiro, 'nome' | 'descricao' | 'tipo'>>): void {
    this.alterar((r) => ({ ...r, ...patch }));
  }

  // ─── Etapas ───────────────────────────────────────────────

  /**
   * Sem posição (clique na biblioteca): entra depois da etapa selecionada e herda a saída dela, ou no
   * meio da ligação selecionada; sem seleção, embaixo da última etapa.
   */
  adicionarEtapa(tipo: TipoBloco, posicao?: { x: number; y: number }): void {
    const r = this.roteiro();
    if (!r || this.somenteLeitura()) return;
    if (tipo === 'INICIO' && r.etapas.some((e) => e.tipo === 'INICIO')) {
      this.avisar('O roteiro já tem um Início.', 'erro');
      return;
    }
    const t = tamanhoEtapa(tipo);
    const nova: Etapa = { id: novoId('etapa'), tipo, nome: definicao(tipo).rotulo, x: 0, y: 0, config: configPadrao(tipo) };
    let conexoes = [...r.conexoes];
    const sel = this.selecao();

    if (posicao) {
      nova.x = Math.round(posicao.x - t.w / 2);
      nova.y = Math.round(posicao.y - t.h / 2);
    } else if (sel?.tipo === 'conexao') {
      const c = r.conexoes.find((x) => x.id === sel.id);
      const de = c && r.etapas.find((e) => e.id === c.de);
      const para = c && r.etapas.find((e) => e.id === c.para);
      if (c && de && para) {
        nova.x = Math.round((de.x + para.x) / 2 + (tamanhoEtapa(de.tipo).w - t.w) / 2);
        nova.y = Math.round((de.y + para.y) / 2);
        conexoes = conexoes.filter((x) => x.id !== c.id);
        conexoes.push({ id: novoId('lig'), de: c.de, para: nova.id, ramo: c.ramo });
        if (tipo !== 'FINALIZACAO') {
          conexoes.push({ id: novoId('lig'), de: nova.id, para: c.para, ramo: tipo === 'CONDICAO' ? 'SIM' : null });
        }
        this.empurrarAbaixo(r, para.id, nova.y);
      }
    } else if (sel?.tipo === 'etapa') {
      const ant = r.etapas.find((e) => e.id === sel.id);
      if (ant) {
        const ta = tamanhoEtapa(ant.tipo);
        nova.x = Math.round(ant.x + (ta.w - t.w) / 2);
        nova.y = ant.y + ta.h + 48;
        if (ant.tipo !== 'FINALIZACAO') {
          const saida = ant.tipo === 'CONDICAO' ? undefined : saidas(r, ant.id)[0];
          if (saida) {
            conexoes = conexoes.filter((x) => x.id !== saida.id);
            if (tipo !== 'FINALIZACAO') {
              conexoes.push({ id: novoId('lig'), de: nova.id, para: saida.para, ramo: tipo === 'CONDICAO' ? 'SIM' : null });
            }
            this.empurrarAbaixo(r, saida.para, nova.y);
          }
          const ramoLivre: Ramo | null =
            ant.tipo === 'CONDICAO' ? (['SIM', 'NAO'] as Ramo[]).find((rm) => !saidas(r, ant.id).some((c) => c.ramo === rm)) ?? null : null;
          if (ant.tipo !== 'CONDICAO' || ramoLivre) {
            conexoes.push({ id: novoId('lig'), de: ant.id, para: nova.id, ramo: ramoLivre });
            if (ramoLivre === 'NAO') nova.x += 248;
            if (ramoLivre === 'SIM' && saidas(r, ant.id).length === 0) nova.x -= 124;
          }
        }
      }
    } else {
      const ultima = [...r.etapas].sort((a, b) => b.y - a.y)[0];
      nova.x = ultima ? Math.round(ultima.x + (tamanhoEtapa(ultima.tipo).w - t.w) / 2) : 0;
      nova.y = ultima ? ultima.y + tamanhoEtapa(ultima.tipo).h + 48 : 0;
    }

    const atual = this.roteiro()!;
    this.roteiro.set({ ...atual, etapas: [...atual.etapas, nova], conexoes });
    this.selecao.set({ tipo: 'etapa', id: nova.id });
  }

  /** Abre espaço: desce a etapa `id` e tudo abaixo dela quando a nova etapa encosta. */
  private empurrarAbaixo(r: Roteiro, id: string, yNova: number): void {
    const alvo = r.etapas.find((e) => e.id === id);
    if (!alvo || alvo.y > yNova + 64 + 24) return;
    const delta = yNova + 64 + 48 - alvo.y;
    this.roteiro.set({ ...r, etapas: r.etapas.map((e) => (e.y >= alvo.y ? { ...e, y: e.y + delta } : e)) });
  }

  moverEtapa(id: string, x: number, y: number): void {
    this.alterar((r) => ({ ...r, etapas: r.etapas.map((e) => (e.id === id ? { ...e, x, y } : e)) }));
  }

  atualizarEtapa(id: string, patch: Partial<Pick<Etapa, 'nome'>>): void {
    this.alterar((r) => ({ ...r, etapas: r.etapas.map((e) => (e.id === id ? { ...e, ...patch } : e)) }));
  }

  atualizarConfig(id: string, patch: Partial<ConfigEtapa>): void {
    this.alterar((r) => ({
      ...r,
      etapas: r.etapas.map((e) => (e.id === id ? { ...e, config: { ...e.config, ...patch } } : e)),
    }));
  }

  // ─── Ligações ─────────────────────────────────────────────

  /** Uma saída por etapa (por ramo, na Condição): ligar de novo substitui a ligação anterior. */
  conectar(de: string, para: string, ramo: Ramo | null): void {
    const r = this.roteiro();
    if (!r || this.somenteLeitura() || de === para) return;
    const origem = r.etapas.find((e) => e.id === de);
    const destino = r.etapas.find((e) => e.id === para);
    if (!origem || !destino) return;
    if (destino.tipo === 'INICIO') {
      this.avisar('Nenhuma etapa pode voltar para o Início.', 'erro');
      return;
    }
    if (origem.tipo === 'FINALIZACAO') {
      this.avisar('A Finalização encerra o roteiro e não tem próxima etapa.', 'erro');
      return;
    }
    const conexoes = r.conexoes.filter((c) => !(c.de === de && (origem.tipo !== 'CONDICAO' || c.ramo === ramo)));
    const nova: Conexao = { id: novoId('lig'), de, para, ramo: origem.tipo === 'CONDICAO' ? ramo : null };
    this.roteiro.set({ ...r, conexoes: [...conexoes, nova] });
    this.selecao.set({ tipo: 'conexao', id: nova.id });
  }

  // ─── Exclusão ─────────────────────────────────────────────

  /** Excluir uma etapa com uma entrada e uma saída religa as vizinhas, sem deixar o caminho quebrado. */
  excluirSelecao(): void {
    const r = this.roteiro();
    const s = this.selecao();
    if (!r || !s || this.somenteLeitura()) return;
    if (s.tipo === 'conexao') {
      this.roteiro.set({ ...r, conexoes: r.conexoes.filter((c) => c.id !== s.id) });
    } else {
      const etapa = r.etapas.find((e) => e.id === s.id);
      if (!etapa) return;
      const ent = entradas(r, etapa.id);
      const sai = saidas(r, etapa.id);
      let conexoes = r.conexoes.filter((c) => c.de !== etapa.id && c.para !== etapa.id);
      if (ent.length === 1 && sai.length === 1 && etapa.tipo !== 'CONDICAO') {
        conexoes = [...conexoes, { id: novoId('lig'), de: ent[0].de, para: sai[0].para, ramo: ent[0].ramo }];
      }
      this.roteiro.set({ ...r, etapas: r.etapas.filter((e) => e.id !== etapa.id), conexoes });
    }
    this.selecao.set(null);
  }

  organizar(): void {
    this.alterar((r) => organizar(r));
  }

  // ─── Simulação ────────────────────────────────────────────

  abrirSimulacao(): void {
    this.selecao.set(null);
    this.painelSimulacao.set(true);
  }

  iniciarSimulacao(cenario: Cenario): void {
    const r = this.roteiro();
    if (!r) return;
    this.selecao.set(null);
    this.simulacao.set({ cenario, resultado: simular(r, cenario), mostrados: 1 });
  }

  avancarSimulacao(): void {
    this.simulacao.update((s) => (s ? { ...s, mostrados: Math.min(s.mostrados + 1, s.resultado.passos.length) } : s));
  }

  concluirSimulacao(): void {
    this.simulacao.update((s) => (s ? { ...s, mostrados: s.resultado.passos.length } : s));
  }

  encerrarSimulacao(): void {
    this.simulacao.set(null);
    this.painelSimulacao.set(false);
  }
}
