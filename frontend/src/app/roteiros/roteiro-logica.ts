import {
  CONDICOES,
  CondicaoId,
  Conexao,
  Etapa,
  Ramo,
  Roteiro,
  definicao,
  ehEstrutural,
  tamanhoEtapa,
} from './roteiros.model';

/**
 * PROTÓTIPO — regras demonstrativas do construtor: validação do desenho, simulação de caminho e
 * organização automática. Funções puras sobre o Roteiro (testadas em roteiro-logica.spec.ts).
 * Nenhuma delas substitui ou consulta regra do Sankhya ou da conferência real.
 */

export type NivelValidacao = 'ERRO' | 'AVISO';

export interface Validacao {
  nivel: NivelValidacao;
  mensagem: string;
  etapaId?: string;
  conexaoId?: string;
}

const nomeDe = (e: Etapa) => `“${e.nome}”`;

export function saidas(r: Roteiro, etapaId: string): Conexao[] {
  return r.conexoes.filter((c) => c.de === etapaId);
}

export function entradas(r: Roteiro, etapaId: string): Conexao[] {
  return r.conexoes.filter((c) => c.para === etapaId);
}

/** Etapas alcançáveis a partir de `inicioId`, seguindo as ligações. */
function alcancaveis(r: Roteiro, inicioId: string): Set<string> {
  const vistos = new Set<string>([inicioId]);
  const fila = [inicioId];
  while (fila.length) {
    const atual = fila.shift()!;
    for (const c of saidas(r, atual)) {
      if (!vistos.has(c.para)) {
        vistos.add(c.para);
        fila.push(c.para);
      }
    }
  }
  return vistos;
}

/** Etapas que vêm antes de `etapaId` em algum caminho. */
function antecessoras(r: Roteiro, etapaId: string): Set<string> {
  const vistos = new Set<string>();
  const fila = [etapaId];
  while (fila.length) {
    const atual = fila.shift()!;
    for (const c of entradas(r, atual)) {
      if (!vistos.has(c.de)) {
        vistos.add(c.de);
        fila.push(c.de);
      }
    }
  }
  return vistos;
}

/** Primeira ligação que fecha um ciclo (busca em profundidade), ou null. */
export function ligacaoCircular(r: Roteiro): Conexao | null {
  const estado = new Map<string, 'visitando' | 'feito'>();
  let achada: Conexao | null = null;
  const visitar = (id: string) => {
    if (achada) return;
    estado.set(id, 'visitando');
    for (const c of saidas(r, id)) {
      const s = estado.get(c.para);
      if (s === 'visitando') {
        achada = c;
        return;
      }
      if (!s) visitar(c.para);
      if (achada) return;
    }
    estado.set(id, 'feito');
  };
  const inicios = r.etapas.filter((e) => e.tipo === 'INICIO').map((e) => e.id);
  for (const id of [...inicios, ...r.etapas.map((e) => e.id)]) {
    if (!estado.has(id)) visitar(id);
    if (achada) break;
  }
  return achada;
}

export function validar(r: Roteiro): Validacao[] {
  const v: Validacao[] = [];
  const porId = new Map(r.etapas.map((e) => [e.id, e]));
  const inicios = r.etapas.filter((e) => e.tipo === 'INICIO');
  const finais = r.etapas.filter((e) => e.tipo === 'FINALIZACAO');

  if (inicios.length === 0) {
    v.push({ nivel: 'ERRO', mensagem: 'O roteiro não tem Início. Arraste o bloco Início da biblioteca para o começo do processo.' });
  } else if (inicios.length > 1) {
    for (const e of inicios.slice(1)) {
      v.push({ nivel: 'ERRO', etapaId: e.id, mensagem: 'O roteiro tem mais de um Início. Ele deve começar em um ponto só.' });
    }
  }
  if (finais.length === 0) {
    v.push({ nivel: 'ERRO', mensagem: 'O roteiro não tem Finalização. Adicione o bloco Finalização ao fim do processo.' });
  }

  const circular = ligacaoCircular(r);
  if (circular) {
    const de = porId.get(circular.de);
    const para = porId.get(circular.para);
    v.push({
      nivel: 'ERRO',
      conexaoId: circular.id,
      etapaId: circular.de,
      mensagem: `O roteiro volta de ${de ? nomeDe(de) : 'uma etapa'} para ${para ? nomeDe(para) : 'uma etapa anterior'}. Retornos não são permitidos neste modelo inicial.`,
    });
  }

  const alcance = inicios.length ? alcancaveis(r, inicios[0].id) : new Set<string>();

  for (const e of r.etapas) {
    const ent = entradas(r, e.id);
    const sai = saidas(r, e.id);
    const def = definicao(e.tipo);

    if (e.tipo === 'INICIO') {
      if (ent.length) v.push({ nivel: 'ERRO', etapaId: e.id, mensagem: 'Nenhuma etapa pode voltar para o Início.' });
      if (!sai.length) v.push({ nivel: 'ERRO', etapaId: e.id, mensagem: 'O Início não está ligado à primeira etapa.' });
      continue;
    }

    if (!ent.length) {
      v.push({ nivel: 'ERRO', etapaId: e.id, mensagem: `${nomeDe(e)} está solta: nenhuma etapa leva até ela.` });
    } else if (inicios.length && !alcance.has(e.id)) {
      v.push({ nivel: 'AVISO', etapaId: e.id, mensagem: `${nomeDe(e)} não é alcançada a partir do Início.` });
    }

    if (e.tipo === 'FINALIZACAO') {
      if (sai.length) v.push({ nivel: 'ERRO', etapaId: e.id, mensagem: 'A Finalização encerra o roteiro e não pode ter próxima etapa.' });
      continue;
    }

    if (e.tipo === 'CONDICAO') {
      if (!e.config.pergunta) {
        v.push({ nivel: 'ERRO', etapaId: e.id, mensagem: `Escolha a pergunta da condição ${nomeDe(e)}.` });
      }
      const temSim = sai.some((c) => c.ramo === 'SIM');
      const temNao = sai.some((c) => c.ramo === 'NAO');
      if (!temSim || !temNao) {
        const falta = !temSim && !temNao ? 'Sim e Não' : !temSim ? 'Sim' : 'Não';
        v.push({ nivel: 'ERRO', etapaId: e.id, mensagem: `A condição ${nomeDe(e)} não tem caminho para ${falta}.` });
      }
    } else {
      if (!sai.length) {
        v.push({ nivel: 'ERRO', etapaId: e.id, mensagem: `${nomeDe(e)} não tem próxima etapa. Ligue-a a outra etapa ou à Finalização.` });
      } else if (sai.length > 1) {
        v.push({ nivel: 'ERRO', etapaId: e.id, mensagem: `${nomeDe(e)} tem mais de uma saída. Use uma Condição para dividir o caminho.` });
      }
    }

    // Configuração obrigatória.
    if (!ehEstrutural(e.tipo) && e.config.execucao === 'CONDICIONAL' && !e.config.condicao) {
      v.push({ nivel: 'ERRO', etapaId: e.id, mensagem: `Escolha quando ${nomeDe(e)} deve ser executada.` });
    }
    if (e.tipo === 'IMPRESSAO') {
      const d = e.config.documentos;
      if (!d || (!d.nota && !d.romaneio && !d.etiquetas)) {
        v.push({ nivel: 'ERRO', etapaId: e.id, mensagem: `Escolha ao menos um documento para ${nomeDe(e)}.` });
      }
    }
    if (e.tipo === 'APROVACAO' && !e.config.responsavel) {
      v.push({ nivel: 'ERRO', etapaId: e.id, mensagem: `Defina quem aprova em ${nomeDe(e)}.` });
    }

    // Dependências técnicas dos módulos atuais (só referência visual).
    if (def.requer?.length) {
      const antes = antecessoras(r, e.id);
      for (const req of def.requer) {
        const tem = [...antes].some((id) => porId.get(id)?.tipo === req);
        if (!tem) {
          v.push({ nivel: 'ERRO', etapaId: e.id, mensagem: `${nomeDe(e)} precisa de uma ${definicao(req).rotulo} antes dela.` });
        }
      }
    }
    if (e.tipo === 'IMPRESSAO' && e.config.documentos?.nota) {
      const antes = antecessoras(r, e.id);
      if (![...antes].some((id) => porId.get(id)?.tipo === 'FATURAMENTO')) {
        v.push({ nivel: 'AVISO', etapaId: e.id, mensagem: `${nomeDe(e)} imprime a nota fiscal, mas não há Faturamento antes dela.` });
      }
    }
    if (e.tipo === 'FATURAMENTO') {
      const carregamento = r.etapas.find((x) => x.tipo === 'CARREGAMENTO');
      if (carregamento && !antecessoras(r, e.id).has(carregamento.id)) {
        v.push({
          nivel: 'AVISO',
          etapaId: e.id,
          mensagem: 'Hoje o Fechamento da Ordem de Carga só fatura com a carga concluída. Coloque o Carregamento antes do Faturamento.',
        });
      }
    }
    if (def.situacao === 'CONCEITUAL' && e.tipo !== 'CONDICAO') {
      v.push({ nivel: 'AVISO', etapaId: e.id, mensagem: `${nomeDe(e)} é um bloco conceitual: ainda não existe na aplicação.` });
    }
  }

  return v.sort((a, b) => (a.nivel === b.nivel ? 0 : a.nivel === 'ERRO' ? -1 : 1));
}

// ─── Simulação ────────────────────────────────────────────────────────────

export type Cenario = Partial<Record<CondicaoId, boolean>>;

export type ResultadoPasso = 'EXECUTADA' | 'IGNORADA' | 'DECISAO' | 'FIM';

export interface PassoSimulacao {
  etapaId: string;
  resultado: ResultadoPasso;
  /** Ligação usada para chegar à próxima etapa. */
  conexaoId: string | null;
  detalhe: string;
}

export interface ResultadoSimulacao {
  passos: PassoSimulacao[];
  /** null = chegou na Finalização; senão, onde e por que parou. */
  interrupcao: string | null;
}

/** Condições usadas no roteiro (perguntas e execuções condicionais) — viram as chaves do cenário. */
export function condicoesUsadas(r: Roteiro): CondicaoId[] {
  const set = new Set<CondicaoId>();
  for (const e of r.etapas) {
    if (e.tipo === 'CONDICAO' && e.config.pergunta) set.add(e.config.pergunta);
    if (!ehEstrutural(e.tipo) && e.config.execucao === 'CONDICIONAL' && e.config.condicao) set.add(e.config.condicao);
  }
  return [...set];
}

export function simular(r: Roteiro, cenario: Cenario): ResultadoSimulacao {
  const passos: PassoSimulacao[] = [];
  const inicio = r.etapas.find((e) => e.tipo === 'INICIO');
  if (!inicio) return { passos, interrupcao: 'O roteiro não tem Início.' };
  const porId = new Map(r.etapas.map((e) => [e.id, e]));
  const visitados = new Set<string>();
  let atual: Etapa | undefined = inicio;

  while (atual) {
    if (visitados.has(atual.id)) return { passos, interrupcao: `O caminho voltou para “${atual.nome}”.` };
    visitados.add(atual.id);
    const sai = saidas(r, atual.id);

    if (atual.tipo === 'FINALIZACAO') {
      passos.push({ etapaId: atual.id, resultado: 'FIM', conexaoId: null, detalhe: 'Roteiro concluído.' });
      return { passos, interrupcao: null };
    }

    let proxima: Conexao | undefined;
    if (atual.tipo === 'CONDICAO') {
      const pergunta = atual.config.pergunta;
      if (!pergunta) return { passos, interrupcao: `A condição “${atual.nome}” não tem pergunta.` };
      const resposta: Ramo = cenario[pergunta] ? 'SIM' : 'NAO';
      proxima = sai.find((c) => c.ramo === resposta);
      const texto = cenario[pergunta] ? CONDICOES[pergunta].sim : CONDICOES[pergunta].nao;
      passos.push({ etapaId: atual.id, resultado: 'DECISAO', conexaoId: proxima?.id ?? null, detalhe: `${resposta === 'SIM' ? 'Sim' : 'Não'}: ${texto.toLowerCase()}.` });
      if (!proxima) return { passos, interrupcao: `A condição “${atual.nome}” não tem caminho para ${resposta === 'SIM' ? 'Sim' : 'Não'}.` };
    } else {
      proxima = sai[0];
      const c = atual.config;
      const condicional = !ehEstrutural(atual.tipo) && c.execucao === 'CONDICIONAL' && c.condicao;
      const pular = condicional && !cenario[c.condicao!];
      passos.push({
        etapaId: atual.id,
        resultado: pular ? 'IGNORADA' : 'EXECUTADA',
        conexaoId: proxima?.id ?? null,
        detalhe: pular
          ? `Ignorada: só executa ${CONDICOES[c.condicao!].quando}.`
          : atual.tipo === 'INICIO'
            ? definicao('INICIO').instrucao
            : c.aoConcluir === 'AVANCAR'
              ? 'Avança sozinha ao concluir.'
              : 'Aguarda a confirmação do operador.',
      });
      if (!proxima) return { passos, interrupcao: `“${atual.nome}” não tem próxima etapa.` };
    }
    atual = porId.get(proxima.para);
  }
  return { passos, interrupcao: 'Uma ligação aponta para uma etapa que não existe.' };
}

// ─── Organização automática ───────────────────────────────────────────────

const ESPACO_X = 248;
const ESPACO_Y = 112;

/**
 * Camadas pelo caminho mais longo a partir do Início (ligações circulares ignoradas); dentro da camada,
 * a ordem em que a busca encontrou cada etapa, com o ramo Sim antes do Não. Etapas fora do alcance do
 * Início vão para uma coluna à direita.
 */
export function organizar(r: Roteiro): Roteiro {
  const inicio = r.etapas.find((e) => e.tipo === 'INICIO');
  const circular = new Set<string>();
  // Remove arestas de retorno, uma por vez, até não haver ciclo.
  const semCiclo: Roteiro = { ...r, conexoes: [...r.conexoes] };
  for (let i = 0; i < r.conexoes.length; i++) {
    const c = ligacaoCircular(semCiclo);
    if (!c) break;
    circular.add(c.id);
    semCiclo.conexoes = semCiclo.conexoes.filter((x) => x.id !== c.id);
  }

  const camada = new Map<string, number>();
  const ordem = new Map<string, number>();
  let contador = 0;
  if (inicio) {
    // Ordem de descoberta: profundidade, Sim antes de Não.
    const visitar = (id: string) => {
      if (ordem.has(id)) return;
      ordem.set(id, contador++);
      const filhos = saidas(semCiclo, id).sort((a, b) => (a.ramo === 'NAO' ? 1 : 0) - (b.ramo === 'NAO' ? 1 : 0));
      for (const f of filhos) visitar(f.para);
    };
    visitar(inicio.id);
    // Caminho mais longo (relaxamento simples; o grafo é pequeno e sem ciclo).
    camada.set(inicio.id, 0);
    for (let i = 0; i < r.etapas.length; i++) {
      let mudou = false;
      for (const c of semCiclo.conexoes) {
        const base = camada.get(c.de);
        if (base === undefined) continue;
        if ((camada.get(c.para) ?? -1) < base + 1) {
          camada.set(c.para, base + 1);
          mudou = true;
        }
      }
      if (!mudou) break;
    }
  }

  const porCamada = new Map<number, Etapa[]>();
  const soltas: Etapa[] = [];
  for (const e of r.etapas) {
    const n = camada.get(e.id);
    if (n === undefined) soltas.push(e);
    else porCamada.set(n, [...(porCamada.get(n) ?? []), e]);
  }

  const pos = new Map<string, { x: number; y: number }>();
  let maxLargura = 1;
  for (const [, lista] of porCamada) maxLargura = Math.max(maxLargura, lista.length);
  const centro = ((maxLargura - 1) * ESPACO_X) / 2 + 216 / 2;
  for (const [n, lista] of porCamada) {
    lista.sort((a, b) => (ordem.get(a.id) ?? 0) - (ordem.get(b.id) ?? 0));
    lista.forEach((e, i) => {
      const t = tamanhoEtapa(e.tipo);
      const cx = centro + (i - (lista.length - 1) / 2) * ESPACO_X;
      pos.set(e.id, { x: Math.round(cx - t.w / 2), y: n * ESPACO_Y });
    });
  }
  // Desvio de uma Condição (um ramo passa por etapas, o outro pula direto para mais adiante): o desvio
  // vai para o lado da sua porta, para os dois caminhos não ficarem empilhados na mesma coluna.
  for (const cond of r.etapas.filter((e) => e.tipo === 'CONDICAO')) {
    const sai = saidas(semCiclo, cond.id);
    const sim = sai.find((c) => c.ramo === 'SIM');
    const nao = sai.find((c) => c.ramo === 'NAO');
    if (!sim || !nao) continue;
    const cs = camada.get(sim.para) ?? 0;
    const cn = camada.get(nao.para) ?? 0;
    if (cs === cn) continue;
    const [desvio, juncao, lado] = cs < cn ? [sim.para, cn, -1] : [nao.para, cs, 1];
    const fila = [desvio];
    const vistos = new Set<string>();
    while (fila.length) {
      const id = fila.shift()!;
      if (vistos.has(id) || (camada.get(id) ?? 0) >= juncao) continue;
      vistos.add(id);
      const p = pos.get(id);
      if (p && (porCamada.get(camada.get(id)!)?.length ?? 0) === 1) pos.set(id, { ...p, x: p.x + lado * (ESPACO_X / 2) });
      for (const c of saidas(semCiclo, id)) fila.push(c.para);
    }
  }

  const colunaSoltas = centro + ((maxLargura + 1) * ESPACO_X) / 2 + 40;
  soltas.forEach((e, i) => pos.set(e.id, { x: Math.round(colunaSoltas), y: i * ESPACO_Y }));

  return { ...r, etapas: r.etapas.map((e) => ({ ...e, ...(pos.get(e.id) ?? {}) })) };
}
