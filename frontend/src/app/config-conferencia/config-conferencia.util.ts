import { CampoCatalogo } from './config-conferencia.model';

export interface SecaoAgrupada {
  titulo: string;
  campos: CampoCatalogo[];
}

/** Agrupa preservando a ordem de aparição no catálogo — não reordena alfabeticamente. */
export function agruparPorSubSecao(camposCatalogo: CampoCatalogo[]): SecaoAgrupada[] {
  const mapa = new Map<string, CampoCatalogo[]>();
  for (const campo of camposCatalogo) {
    if (!mapa.has(campo.subSecao)) mapa.set(campo.subSecao, []);
    mapa.get(campo.subSecao)!.push(campo);
  }
  return [...mapa.entries()].map(([titulo, campos]) => ({ titulo, campos }));
}

export function campoHabilitado(campo: CampoCatalogo, valores: Record<string, string | null>): boolean {
  return campo.condicao ? campo.condicao(valores) : true;
}
