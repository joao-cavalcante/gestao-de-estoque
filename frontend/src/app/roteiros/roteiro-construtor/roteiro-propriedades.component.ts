import { Component, computed, inject } from '@angular/core';
import { OqIconComponent } from '../../shared/icons/oq-icon.component';
import { entradas, saidas } from '../roteiro-logica';
import {
  AcaoDivergencia,
  AoConcluir,
  CONDICOES,
  CondicaoId,
  ConfigEtapa,
  Etapa,
  Execucao,
  ROTULO_RESPONSAVEL,
  ROTULO_TIPO,
  Responsavel,
  TipoRoteiro,
  definicao,
  ehEstrutural,
} from '../roteiros.model';
import { RoteiroEditorStore } from './roteiro-editor.store';

/** Perguntas que fazem sentido em cada tipo de roteiro. */
const CONDICOES_POR_TIPO: Record<TipoRoteiro, CondicaoId[]> = {
  SAIDA: ['PESAVEIS', 'DIVERGENCIAS', 'ENTREGA', 'ORDEM_CARGA'],
  ENTRADA: ['PESAVEIS', 'DIVERGENCIAS', 'AVARIA'],
  DEVOLUCAO: ['DIVERGENCIAS', 'AVARIA'],
};

/**
 * PROTÓTIPO — painel direito do construtor: propriedades da etapa, da ligação ou do roteiro.
 * Configurações ilustrativas, em linguagem operacional; nenhuma vira regra de negócio real.
 */
@Component({
  selector: 'app-roteiro-propriedades',
  standalone: true,
  imports: [OqIconComponent],
  templateUrl: './roteiro-propriedades.component.html',
  styleUrl: './roteiro-propriedades.component.scss',
})
export class RoteiroPropriedadesComponent {
  readonly store = inject(RoteiroEditorStore);

  readonly condicoes = CONDICOES;
  readonly rotuloTipo = ROTULO_TIPO;
  readonly responsaveis = Object.entries(ROTULO_RESPONSAVEL) as [Exclude<Responsavel, ''>, string][];
  readonly execucoes: { valor: Execucao; rotulo: string; ajuda: string }[] = [
    { valor: 'OBRIGATORIA', rotulo: 'Obrigatória', ajuda: 'Sempre executada.' },
    { valor: 'CONDICIONAL', rotulo: 'Condicional', ajuda: 'Só em uma situação que você escolhe.' },
    { valor: 'OPCIONAL', rotulo: 'Opcional', ajuda: 'O operador pode pular.' },
  ];
  readonly divergencias: { valor: AcaoDivergencia; rotulo: string }[] = [
    { valor: 'LIBERACAO', rotulo: 'Encaminhar para a liberação de corte' },
    { valor: 'BLOQUEAR', rotulo: 'Bloquear até nova conferência' },
    { valor: 'PERMITIR', rotulo: 'Permitir finalizar com divergência' },
  ];

  readonly etapa = this.store.etapaSelecionada;
  readonly conexao = this.store.conexaoSelecionada;
  readonly leitura = computed(() => this.store.somenteLeitura());

  readonly definicao = computed(() => {
    const e = this.etapa();
    return e ? definicao(e.tipo) : null;
  });

  readonly condicoesDisponiveis = computed(() => CONDICOES_POR_TIPO[this.store.roteiro()?.tipo ?? 'SAIDA']);

  /** Validações da etapa selecionada, para mostrar no próprio painel. */
  readonly problemas = computed(() => {
    const e = this.etapa();
    return e ? this.store.validacoes().filter((v) => v.etapaId === e.id) : [];
  });

  readonly vizinhas = computed(() => {
    const e = this.etapa();
    const r = this.store.roteiro();
    if (!e || !r) return { antes: [] as string[], depois: [] as { nome: string; ramo: string | null }[] };
    const nome = (id: string) => r.etapas.find((x) => x.id === id)?.nome ?? '?';
    return {
      antes: entradas(r, e.id).map((c) => nome(c.de)),
      depois: saidas(r, e.id).map((c) => ({ nome: nome(c.para), ramo: c.ramo === 'SIM' ? 'Sim' : c.ramo === 'NAO' ? 'Não' : null })),
    };
  });

  readonly descricaoConexao = computed(() => {
    const c = this.conexao();
    const r = this.store.roteiro();
    if (!c || !r) return null;
    const nome = (id: string) => r.etapas.find((x) => x.id === id)?.nome ?? '?';
    return { de: nome(c.de), para: nome(c.para), ramo: c.ramo === 'SIM' ? 'Sim' : c.ramo === 'NAO' ? 'Não' : null };
  });

  ehEstrutural(e: Etapa): boolean {
    return ehEstrutural(e.tipo);
  }

  requisitos(): string {
    const d = this.definicao();
    return d?.requer?.length ? d.requer.map((t) => definicao(t).rotulo).join(', ') : 'Nenhuma';
  }

  nome(e: Etapa, valor: string): void {
    this.store.atualizarEtapa(e.id, { nome: valor });
  }

  config(e: Etapa, patch: Partial<ConfigEtapa>): void {
    this.store.atualizarConfig(e.id, patch);
  }

  execucao(e: Etapa, valor: Execucao): void {
    this.config(e, { execucao: valor, condicao: valor === 'CONDICIONAL' ? (e.config.condicao ?? null) : null });
  }

  aoConcluir(e: Etapa, valor: AoConcluir): void {
    this.config(e, { aoConcluir: valor });
  }

  documento(e: Etapa, chave: 'nota' | 'romaneio' | 'etiquetas', marcado: boolean): void {
    const atual = e.config.documentos ?? { nota: false, romaneio: false, etiquetas: false };
    this.config(e, { documentos: { ...atual, [chave]: marcado } });
  }

  condicaoOuNulo(valor: string): CondicaoId | null {
    return (valor || null) as CondicaoId | null;
  }
}
