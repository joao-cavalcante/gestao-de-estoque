import { Injectable, signal } from '@angular/core';
import { roteiroDeModelo, roteirosDemonstracao } from './roteiros-demo.data';
import { Roteiro, TipoRoteiro, novoId } from './roteiros.model';

/**
 * PROTÓTIPO — guarda os roteiros só no localStorage deste navegador. Não há backend, tabela ou
 * migração: limpar os dados do navegador (ou "Restaurar demonstração") volta aos exemplos.
 */
const CHAVE = 'wms_prototipo_roteiros_v1';

@Injectable({ providedIn: 'root' })
export class RoteirosDemoService {
  readonly roteiros = signal<Roteiro[]>(this.carregar());

  obter(id: string): Roteiro | undefined {
    const r = this.roteiros().find((x) => x.id === id);
    return r ? structuredClone(r) : undefined;
  }

  salvar(r: Roteiro): Roteiro {
    const salvo: Roteiro = { ...structuredClone(r), atualizadoEm: new Date().toISOString() };
    this.roteiros.update((lista) => {
      const i = lista.findIndex((x) => x.id === r.id);
      return i < 0 ? [salvo, ...lista] : lista.map((x) => (x.id === r.id ? salvo : x));
    });
    this.gravar();
    return salvo;
  }

  /** Rascunho nunca publicado sai como v1; a partir daí cada publicação é uma versão nova. */
  publicar(r: Roteiro): Roteiro {
    const anterior = this.roteiros().find((x) => x.id === r.id);
    const primeira = !anterior || (anterior.status === 'RASCUNHO' && anterior.versao === 1);
    return this.salvar({ ...r, status: 'PUBLICADO', versao: primeira ? 1 : r.versao + 1 });
  }

  duplicar(id: string): Roteiro | undefined {
    const origem = this.obter(id);
    if (!origem) return undefined;
    const mapa = new Map(origem.etapas.map((e) => [e.id, novoId('etapa')]));
    return this.salvar({
      ...origem,
      id: novoId('roteiro'),
      nome: `${origem.nome} (cópia)`,
      status: 'RASCUNHO',
      versao: 1,
      etapas: origem.etapas.map((e) => ({ ...e, id: mapa.get(e.id)! })),
      conexoes: origem.conexoes.map((c) => ({ ...c, id: novoId('lig'), de: mapa.get(c.de)!, para: mapa.get(c.para)! })),
    });
  }

  criar(nome: string, tipo: TipoRoteiro, descricao: string, modeloId: string | null): Roteiro {
    return this.salvar(roteiroDeModelo(modeloId, { id: novoId('roteiro'), nome, descricao, tipo }));
  }

  restaurarDemonstracao(): void {
    this.roteiros.set(roteirosDemonstracao());
    this.gravar();
  }

  private carregar(): Roteiro[] {
    try {
      const bruto = localStorage.getItem(CHAVE);
      if (bruto) {
        const lista = JSON.parse(bruto) as Roteiro[];
        if (Array.isArray(lista)) return lista;
      }
    } catch {
      // Navegador sem localStorage (modo privado, bloqueado): segue só em memória.
    }
    return roteirosDemonstracao();
  }

  private gravar(): void {
    try {
      localStorage.setItem(CHAVE, JSON.stringify(this.roteiros()));
    } catch {
      // Sem armazenamento: as alterações valem até recarregar a página.
    }
  }
}
