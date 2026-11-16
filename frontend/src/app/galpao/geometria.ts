import { GeometriaPlanta, ObjetoPlanta, PADRAO_ENDERECO, TipoObjeto } from './galpao.model';

/**
 * Mesma regra de wms.backend.galpao.GeradorEnderecos — aqui só pra PRÉVIA no editor (quantos endereços,
 * onde ficam no 3D). Quem vale é o backend, na publicação.
 */
export interface EnderecoPrevia {
  codigo: string;
  setor: string;
  estrutura: string;
  modulo: number;
  nivel: number;
  tipo: 'PICKING' | 'PULMAO';
  x: number;
  y: number;
  z: number;
  rotacao: number;
  largura: number;
  comprimento: number;
  altura: number;
}

const MAX_MODULOS = 500;
const MAX_NIVEIS = 30;
const FOLGA_NIVEL = 0.15;

const limitar = (v: number, min: number, max: number) => Math.min(max, Math.max(min, Math.round(v || 0)));

export function codigoEndereco(padrao: string, estrutura: string, modulo: number, nivel: number): string {
  return padrao
    .split('{estrutura}').join(estrutura)
    .split('{modulo}').join(String(modulo).padStart(2, '0'))
    .split('{nivel}').join(String(nivel).padStart(2, '0'));
}

export function enderecosDaEstrutura(o: ObjetoPlanta, padrao = PADRAO_ENDERECO): EnderecoPrevia[] {
  const modulos = limitar(o.modulos, 1, MAX_MODULOS);
  const niveis = limitar(o.niveis, 1, MAX_NIVEIS);
  const ang = (o.rotacao * Math.PI) / 180;
  const compModulo = o.profundidade / modulos;
  const altNivel = o.altura / niveis;
  const saida: EnderecoPrevia[] = [];
  for (let m = 0; m < modulos; m++) {
    const dz = -o.profundidade / 2 + (m + 0.5) * compModulo;
    const x = o.x - dz * Math.sin(ang);
    const z = o.z + dz * Math.cos(ang);
    for (let n = 0; n < niveis; n++) {
      saida.push({
        codigo: codigoEndereco(padrao, o.codigo, m + 1, n + 1),
        setor: o.setor ?? '',
        estrutura: o.codigo,
        modulo: m + 1,
        nivel: n + 1,
        tipo: n + 1 <= o.nivelPicking ? 'PICKING' : 'PULMAO',
        x,
        y: n * altNivel,
        z,
        rotacao: o.rotacao,
        largura: o.largura,
        comprimento: compModulo,
        altura: Math.max(altNivel - FOLGA_NIVEL, 0.01),
      });
    }
  }
  return saida;
}

export function gerarEnderecos(g: GeometriaPlanta): EnderecoPrevia[] {
  return g.objetos.filter((o) => o.tipo === 'ESTRUTURA').flatMap((o) => enderecosDaEstrutura(o, g.padraoEndereco));
}

export function totalEnderecos(g: GeometriaPlanta): number {
  return g.objetos
    .filter((o) => o.tipo === 'ESTRUTURA')
    .reduce((s, o) => s + limitar(o.modulos, 1, MAX_MODULOS) * limitar(o.niveis, 1, MAX_NIVEIS), 0);
}

export function geometriaVazia(largura: number, profundidade: number): GeometriaPlanta {
  return {
    largura,
    profundidade,
    corredorMinimo: 2,
    padraoEndereco: PADRAO_ENDERECO,
    setores: [
      { codigo: 'SECO', nome: 'Seco', cor: '#8a7a5c' },
      { codigo: 'RESFRIADO', nome: 'Resfriado', cor: '#3d6b94' },
      { codigo: 'CONGELADO', nome: 'Congelado', cor: '#0e7490' },
    ],
    objetos: [],
  };
}

const PADROES: Record<TipoObjeto, Omit<ObjetoPlanta, 'codigo' | 'x' | 'z'>> = {
  ESTRUTURA: { tipo: 'ESTRUTURA', largura: 1.2, profundidade: 13.5, altura: 6, rotacao: 0, modulos: 5, niveis: 4, nivelPicking: 1, capQtd: 100 },
  CORREDOR: { tipo: 'CORREDOR', largura: 3, profundidade: 20, altura: 0.02, rotacao: 0, modulos: 1, niveis: 1, nivelPicking: 0 },
  DOCA: { tipo: 'DOCA', largura: 3.5, profundidade: 4, altura: 0.6, rotacao: 0, modulos: 1, niveis: 1, nivelPicking: 0 },
  AREA: { tipo: 'AREA', largura: 8, profundidade: 6, altura: 0.02, rotacao: 0, modulos: 1, niveis: 1, nivelPicking: 0 },
  PAREDE: { tipo: 'PAREDE', largura: 0.2, profundidade: 10, altura: 8, rotacao: 0, modulos: 1, niveis: 1, nivelPicking: 0 },
};

const PREFIXO: Record<TipoObjeto, string> = { ESTRUTURA: 'R', CORREDOR: 'C', DOCA: 'D', AREA: 'A', PAREDE: 'W' };

/** Próximo código livre do tipo (R01, R02…). */
export function proximoCodigo(g: GeometriaPlanta, tipo: TipoObjeto): string {
  const usados = new Set(g.objetos.map((o) => o.codigo));
  for (let i = 1; ; i++) {
    const c = PREFIXO[tipo] + String(i).padStart(2, '0');
    if (!usados.has(c)) return c;
  }
}

export function novoObjeto(g: GeometriaPlanta, tipo: TipoObjeto, x: number, z: number): ObjetoPlanta {
  const p = PADROES[tipo];
  return {
    ...p,
    codigo: proximoCodigo(g, tipo),
    x,
    z,
    setor: tipo === 'ESTRUTURA' ? (g.setores[0]?.codigo ?? null) : null,
  };
}

/** Caixa alinhada aos eixos do objeto girado (largura em X, profundidade em Z). */
export function extensao(o: ObjetoPlanta): { rx: number; rz: number } {
  const t = (o.rotacao * Math.PI) / 180;
  return {
    rx: Math.abs(o.largura * Math.cos(t)) + Math.abs(o.profundidade * Math.sin(t)),
    rz: Math.abs(o.largura * Math.sin(t)) + Math.abs(o.profundidade * Math.cos(t)),
  };
}

export function arredondar(v: number, passo: number): number {
  return passo > 0 ? Math.round(v / passo) * passo : v;
}
