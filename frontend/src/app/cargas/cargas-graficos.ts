// ECharts modular (só o que a tela usa) — fica no chunk da rota /cargas (loadComponent), fora do bundle inicial.
import * as echarts from 'echarts/core';
import { BarChart, LineChart } from 'echarts/charts';
import { GridComponent, LegendComponent, MarkLineComponent, TooltipComponent } from 'echarts/components';
import { CanvasRenderer } from 'echarts/renderers';
import { Carga, SituacaoPedido } from './cargas.service';

echarts.use([BarChart, LineChart, GridComponent, LegendComponent, MarkLineComponent, TooltipComponent, CanvasRenderer]);

export { echarts };

/** Cores lidas dos tokens do tema atual (claro/escuro) — o gráfico acompanha a troca de tema. */
export interface CoresTema {
  texto: string;
  textoFraco: string;
  borda: string;
  superficie: string;
  carregado: string;
  aCarregar: string;
  conferindo: string;
  aConferir: string;
  corte: string;
  alerta: string;
  linha: string;
  fonte: string;
}

/**
 * Etapas: paleta própria por tema, validada (dataviz validate_palette: banda de luminosidade, separação CVD ≥ 8 e
 * visão normal ≥ 15 entre vizinhos). "A conferir" é cinza de propósito (não começou) — vem com legenda e vão de 1px.
 */
const ETAPAS_CLARO = { carregado: '#2E7D4F', aCarregar: '#2563EB', conferindo: '#C2760C', aConferir: '#9A97A3', corte: '#B42318' };
const ETAPAS_ESCURO = { carregado: '#3E9A68', aCarregar: '#5B84E6', conferindo: '#C98424', aConferir: '#7C7984', corte: '#D9443B' };

export function lerCores(el: HTMLElement): CoresTema {
  const cs = getComputedStyle(el);
  const v = (n: string, padrao: string) => cs.getPropertyValue(n).trim() || padrao;
  const escuro = document.documentElement.getAttribute('data-theme') === 'dark';
  return {
    texto: v('--oq-text-primary', '#1d1b22'),
    textoFraco: v('--oq-text-secondary', '#6b6773'),
    borda: v('--oq-border', '#e3e0ea'),
    superficie: v('--oq-surface', '#ffffff'),
    ...(escuro ? ETAPAS_ESCURO : ETAPAS_CLARO),
    alerta: v('--oq-critical-foreground', '#7A1610'),
    linha: v('--oq-text-primary', '#1d1b22'),
    fonte: v('--oq-font-body', 'Inter, sans-serif'),
  };
}

/** "JOSE CARLOS SILVA" → "Jose Carlos Silva" (preposições em minúsculas). */
export function nomeProprio(m: string | null | undefined, vazio = 'sem motorista'): string {
  if (!m) return vazio;
  return m
    .trim()
    .toLowerCase()
    .replace(/(^|\s)(\p{L})/gu, (_, e, l) => e + l.toUpperCase())
    .replace(/ (Da|De|Do|Das|Dos|E) /g, (x) => x.toLowerCase());
}

/** "ABC1D23" → "ABC-1D23". */
export function placaTexto(p: string | null | undefined): string {
  if (!p) return 'sem placa';
  const t = p.trim().toUpperCase().replace(/[^A-Z0-9]/g, '');
  return t.length === 7 ? `${t.slice(0, 3)}-${t.slice(3)}` : t;
}

/** Minutos inteiros desde [iso] (null sem data). */
export function minutosDesde(iso: string | null | undefined, agora = Date.now()): number | null {
  if (!iso) return null;
  return Math.max(0, Math.floor((agora - new Date(iso).getTime()) / 60_000));
}

export function duracaoTexto(min: number): string {
  if (min < 60) return `${min} min`;
  const h = Math.floor(min / 60);
  const m = min % 60;
  return m ? `${h}h${String(m).padStart(2, '0')}` : `${h}h`;
}

/** Etapas na ordem do fluxo (da esquerda para a direita na barra): o que já está pronto primeiro. */
export const ETAPAS: { chave: SituacaoPedido | 'pronto'; nome: string; cor: keyof CoresTema }[] = [
  { chave: 'pronto', nome: 'Carregado', cor: 'carregado' },
  { chave: 'a_carregar', nome: 'A carregar', cor: 'aCarregar' },
  { chave: 'em_conferencia', nome: 'Conferindo', cor: 'conferindo' },
  { chave: 'a_conferir', nome: 'A conferir', cor: 'aConferir' },
  { chave: 'corte', nome: 'Falta a aprovar', cor: 'corte' },
];

function contar(c: Carga, chave: SituacaoPedido | 'pronto'): number {
  if (chave === 'pronto') return c.pedidos.filter((p) => p.situacao === 'carregado' || p.situacao === 'com_nota').length;
  return c.pedidos.filter((p) => p.situacao === chave).length;
}

function curto(t: string, max: number): string {
  return t.length > max ? t.slice(0, max - 1) + '…' : t;
}

/** Altura por caminhão no gráfico (duas linhas de rótulo). */
export const ALTURA_LINHA = 46;

/**
 * Barras empilhadas: uma linha por caminhão ainda no galpão, pedidos por etapa. Rótulo à esquerda: OC + placa e a
 * região embaixo; à direita, o que falta (ou há quanto tempo está pronto).
 */
export function opcaoAndamento(
  cargas: Carga[],
  cores: CoresTema,
  ladoDireito: (c: Carga) => { texto: string; tom: 'ok' | 'falta' | 'alerta' | 'futuro' },
): echarts.EChartsCoreOption {
  const linhas = [...cargas].reverse(); // ECharts desenha a 1ª categoria embaixo
  const rotulos = linhas.map(
    (c) => `{oc|OC ${c.ordemCarga}}{sep|·}{placa|${placaTexto(c.placa)}}\n{reg|${curto(nomeProprio(c.regiao, 'sem região'), 26)}}`,
  );
  const direita = linhas.map((c) => {
    const d = ladoDireito(c);
    return `{${d.tom}|${d.texto}}`;
  });
  return {
    textStyle: { fontFamily: cores.fonte },
    animationDuration: 300,
    grid: { left: 8, right: 8, top: 34, bottom: 4, containLabel: true },
    legend: {
      top: 0,
      left: 0,
      icon: 'roundRect',
      itemWidth: 12,
      itemHeight: 12,
      itemGap: 16,
      textStyle: { color: cores.textoFraco, fontSize: 12 },
      data: ETAPAS.map((e) => e.nome),
    },
    tooltip: {
      trigger: 'axis',
      axisPointer: { type: 'shadow', shadowStyle: { color: 'rgba(127,127,127,0.08)' } },
      backgroundColor: cores.superficie,
      borderColor: cores.borda,
      textStyle: { color: cores.texto, fontSize: 12 },
      formatter: (ps: any) => {
        const arr = Array.isArray(ps) ? ps : [ps];
        const c = linhas[arr[0].dataIndex];
        const itens = arr.filter((p: any) => p.value > 0).map((p: any) => `${p.marker} ${p.seriesName}: <b>${p.value}</b>`).join('<br/>');
        return `<b>OC ${c.ordemCarga}</b> · ${placaTexto(c.placa)}<br/>${nomeProprio(c.motorista)} · ${nomeProprio(c.regiao, 'sem região')}<br/>${itens}<br/><span style="opacity:.7">clique para ver os pedidos</span>`;
      },
    },
    xAxis: {
      type: 'value',
      minInterval: 1,
      axisLabel: { color: cores.textoFraco, fontSize: 11 },
      splitLine: { lineStyle: { color: cores.borda } },
    },
    yAxis: [
      {
        type: 'category',
        data: rotulos,
        axisTick: { show: false },
        axisLine: { lineStyle: { color: cores.borda } },
        axisLabel: {
          lineHeight: 16,
          rich: {
            oc: { color: cores.texto, fontSize: 12.5, fontWeight: 700 },
            sep: { color: cores.textoFraco, fontSize: 12, padding: [0, 6] },
            placa: { color: cores.texto, fontSize: 12, fontFamily: 'IBM Plex Mono, monospace', fontWeight: 600 },
            reg: { color: cores.textoFraco, fontSize: 11.5 },
          },
        },
      },
      {
        type: 'category',
        position: 'right',
        data: direita,
        axisTick: { show: false },
        axisLine: { show: false },
        axisLabel: {
          rich: {
            ok: { color: cores.carregado, fontSize: 12, fontWeight: 700 },
            falta: { color: cores.textoFraco, fontSize: 12, fontWeight: 600 },
            alerta: { color: cores.alerta, fontSize: 12, fontWeight: 700 },
            futuro: { color: cores.textoFraco, fontSize: 12, fontStyle: 'italic' },
          },
        },
      },
    ],
    series: ETAPAS.map((e, i) => ({
      name: e.nome,
      type: 'bar',
      stack: 'pedidos',
      barMaxWidth: 22,
      barMinWidth: 12,
      itemStyle: {
        color: cores[e.cor],
        borderColor: cores.superficie,
        borderWidth: 1,
        borderRadius: i === ETAPAS.length - 1 ? [0, 4, 4, 0] : 0,
      },
      emphasis: { focus: 'series' },
      data: linhas.map((c) => contar(c, e.chave)),
    })),
  };
}

/** Saídas acumuladas por hora dentro da janela (dia ou noite), com a linha do total programado. */
export function opcaoSaidas(
  saidas: Carga[],
  total: number,
  inicio: Date,
  horasJanela: number,
  cores: CoresTema,
  agora = new Date(),
): echarts.EChartsCoreOption {
  const horas: string[] = [];
  const acumulado: (number | null)[] = [];
  const momentos = saidas.filter((c) => c.saiuEm).map((c) => new Date(c.saiuEm!).getTime());
  for (let i = 0; i <= horasJanela; i++) {
    const ini = inicio.getTime() + i * 3_600_000;
    horas.push(`${String(new Date(ini).getHours()).padStart(2, '0')}h`);
    acumulado.push(ini <= agora.getTime() ? momentos.filter((m) => m < ini + 3_600_000).length : null);
  }
  return {
    textStyle: { fontFamily: cores.fonte },
    animationDuration: 300,
    grid: { left: 4, right: 12, top: 18, bottom: 4, containLabel: true },
    tooltip: {
      trigger: 'axis',
      backgroundColor: cores.superficie,
      borderColor: cores.borda,
      textStyle: { color: cores.texto, fontSize: 12 },
      valueFormatter: (v: any) => (v == null ? '—' : `${v} de ${total} caminhões`),
    },
    xAxis: {
      type: 'category',
      data: horas,
      boundaryGap: false,
      axisTick: { show: false },
      axisLine: { lineStyle: { color: cores.borda } },
      axisLabel: { color: cores.textoFraco, fontSize: 11, interval: horasJanela > 12 ? 2 : 1 },
    },
    yAxis: {
      type: 'value',
      minInterval: 1,
      splitNumber: 3,
      max: Math.max(total, 1),
      axisLabel: { color: cores.textoFraco, fontSize: 11 },
      splitLine: { lineStyle: { color: cores.borda } },
    },
    series: [
      {
        name: 'Já saíram',
        type: 'line',
        step: 'end',
        data: acumulado,
        symbol: 'none',
        lineStyle: { width: 2, color: cores.carregado },
        areaStyle: { color: cores.carregado, opacity: 0.12 },
        markLine: {
          silent: true,
          symbol: 'none',
          lineStyle: { type: 'dashed', color: cores.textoFraco, width: 1 },
          label: { formatter: `programados: ${total}`, color: cores.textoFraco, fontSize: 11, position: 'insideEndTop' },
          data: [{ yAxis: total }],
        },
      },
    ],
  };
}
