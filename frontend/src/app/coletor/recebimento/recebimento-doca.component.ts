import { Component, ElementRef, OnInit, ViewChild, computed, inject, signal } from '@angular/core';
import { DatePipe, DecimalPipe } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { ActivatedRoute, Router } from '@angular/router';
import { HttpErrorResponse } from '@angular/common/http';
import { Recebimento } from '../../entrada/entrada.service';
import { Palete, PaleteItem, qtdPalete } from '../../paletes/paletes.service';
import { ActionFeedbackService } from '../../shared/action-feedback/action-feedback.service';
import { dataBr, diasAte, lerGs1, lerValidadeDigitada, pareceGs1 } from '../../shared/gs1';
import { OqIconComponent } from '../../shared/icons/oq-icon.component';
import { OqSpinnerComponent } from '../../shared/icons/oq-spinner.component';
import { AlertaPedido, Doca, Foto, LinhaLote, Logistica, LoteXml, RecebimentoDocaService, loteAutomatico, reduzirFoto } from './recebimento-doca.service';

type Passo = 'doca' | 'chegada' | 'palete';
type Aviso = { tom: 'ok' | 'erro' | 'atencao'; titulo: string; mensagem?: string } | null;

/** Linha do editor de lote (texto da validade como o operador digitou). */
interface LinhaEdicao {
  lote: string;
  validadeTexto: string;
  fabricacao: string | null;
  origem: LinhaLote['origem'];
  qtdTexto: string;
}

/** Validade mínima de recebimento (dias) — padrão até a Negri definir por grupo (desenho §7). Só alerta na fase 1. */
const VALIDADE_MINIMA_DIAS = 30;
const MOTIVOS_AVARIA = ['Caixa amassada', 'Molhada', 'Rasgada / aberta', 'Fora da temperatura', 'Vazando'];

/**
 * Compra na doca (27/11/2026, etapa D1 — sankhya-hub docs/projetos/torre-recebimento-doca.md):
 *  - chegada do caminhão (placa + checklist OPCIONAL: lacre, baú, temperatura);
 *  - o que a NF-e diz: volumes e peso bruto (alerta de falta de volume ao vivo) e lotes do XML;
 *  - Tempo 1 = conferência atual do coletor (às cegas pela CCO + "Fechar palete"), aberta daqui e que volta para cá;
 *  - Tempo 2 = lote/validade por palete (GS1 · XML · mesmo do anterior · digitado · sem lote na caixa), outro lote no
 *    mesmo palete, temperatura do palete e avaria; comprovante do motorista e "liberar motorista".
 * Nada grava no Sankhya nesta etapa (lote vai na D3).
 */
@Component({
  selector: 'app-recebimento-doca',
  standalone: true,
  imports: [FormsModule, DatePipe, DecimalPipe, OqIconComponent, OqSpinnerComponent],
  templateUrl: './recebimento-doca.component.html',
  styleUrls: ['../coletor-fila.component.scss', './recebimento-doca.component.scss'],
})
export class RecebimentoDocaComponent implements OnInit {
  private readonly api = inject(RecebimentoDocaService);
  private readonly route = inject(ActivatedRoute);
  private readonly router = inject(Router);
  private readonly feedback = inject(ActionFeedbackService);

  @ViewChild('campo') campo?: ElementRef<HTMLInputElement>;

  readonly nunota = Number(this.route.snapshot.paramMap.get('nunota'));
  readonly recebimento: Recebimento | null = history.state?.recebimento ?? null;
  readonly qtd = qtdPalete;
  readonly dataBr = dataBr;
  readonly motivosAvaria = MOTIVOS_AVARIA;

  readonly doca = signal<Doca | null>(null);
  readonly carregando = signal(false);
  readonly ocupado = signal(false);
  readonly aviso = signal<Aviso>(null);
  readonly passo = signal<Passo>('doca');
  readonly palete = signal<Palete | null>(null);
  readonly item = signal<PaleteItem | null>(null);

  // chegada
  placa = '';
  lacre: string | null = null;
  bau: string | null = null;
  temperaturaBau = '';
  obs = '';

  // editor do palete
  codigo = '';
  linhas: LinhaEdicao[] = [];
  temperaturaPalete = '';
  avariaQtd = '';
  avariaMotivo: string | null = null;
  avariaOutro = '';
  private ultimoLote: Record<number, LinhaEdicao> = {};
  // D2a: amostragem de embalagem, norma do palete (1ª entrada), foto da avaria
  amostraUnidades = '';
  lastro = '';
  camadas = '';
  readonly enviandoFoto = signal(false);

  readonly faltaVolume = computed(() => {
    const d = this.doca();
    const v = d?.nota.volumes;
    return v && d ? Math.max(0, Math.round((v - d.volumesFechados) * 1000) / 1000) : null;
  });
  /** Norma do produto em edição (null = não cadastrada → pedir na 1ª entrada). */
  readonly normaItem = computed<Logistica | null>(() => {
    const it = this.item();
    return (this.doca()?.logistica ?? []).find((l) => l.codprod === it?.codprod) ?? null;
  });
  readonly fotosItem = computed<Foto[]>(() => {
    const p = this.palete();
    const it = this.item();
    return (this.doca()?.fotos ?? []).filter((f) => f.paleteId === p?.id && f.codprod === it?.codprod);
  });
  readonly alertasPedido = computed<AlertaPedido[]>(() => this.doca()?.pedido?.alertas ?? []);
  readonly notaSemPedido = computed(() => {
    const p = this.doca()?.pedido;
    return !!p && p.itensNota > 0 && p.itensComPedido === 0;
  });
  readonly notaTemLote = computed(() => (this.doca()?.nota.itens ?? []).some((i) => i.lotes.length > 0));
  readonly lotesXml = computed<LoteXml[]>(() => {
    const it = this.item();
    const d = this.doca();
    if (!it || !d) return [];
    const doProduto = d.nota.itens.filter((x) => x.codprod === it.codprod).flatMap((x) => x.lotes);
    const vistos = new Set<string>();
    return doProduto.filter((l) => (vistos.has(l.lote) ? false : (vistos.add(l.lote), true)));
  });

  ngOnInit(): void {
    this.carregar();
  }

  carregar(): void {
    this.carregando.set(true);
    this.api.doca(this.nunota).subscribe({
      next: (d) => {
        this.doca.set(d);
        this.carregando.set(false);
        for (const p of d.paletes) for (const i of p.itens) if (i.lote) this.ultimoLote[i.codprod] = this.linhaDe(i);
        const pal = this.palete();
        if (pal) this.palete.set(d.paletes.find((p) => p.id === pal.id) ?? null);
      },
      error: (e) => {
        this.carregando.set(false);
        this.aviso.set({ tom: 'erro', titulo: 'A compra não carregou', mensagem: erro(e) });
      },
    });
  }

  private linhaDe(i: PaleteItem): LinhaEdicao {
    return { lote: i.lote ?? '', validadeTexto: i.validade ? dataBr(i.validade) : '', fabricacao: i.fabricacao ?? null, origem: 'ANTERIOR', qtdTexto: '' };
  }

  voltar(): void {
    if (this.passo() !== 'doca') {
      this.passo.set('doca');
      this.aviso.set(null);
      return;
    }
    this.router.navigate(['/coletor/recebimento']);
  }

  // ─── Tempo 1 ─────────────────────────────────────────────────────────────────────────────────────────

  conferir(): void {
    const r = this.recebimento;
    this.router.navigate(['/conferencia', this.nunota], {
      state: {
        origem: 'recebimento',
        tarefa: { nf: r?.numeroNota ? String(r.numeroNota) : '—', cliente: r?.fornecedor ?? '—', responsavel: '—' },
      },
    });
  }

  // ─── Chegada ─────────────────────────────────────────────────────────────────────────────────────────

  abrirChegada(): void {
    const c = this.doca()?.chegada;
    this.placa = c?.placa ?? this.doca()?.nota.placa ?? '';
    this.lacre = c?.lacre ?? null;
    this.bau = c?.bau ?? null;
    this.temperaturaBau = c?.temperaturaBau != null ? String(c.temperaturaBau).replace('.', ',') : '';
    this.obs = c?.observacao ?? '';
    this.aviso.set(null);
    this.passo.set('chegada');
  }

  salvarChegada(): void {
    const t = this.temperaturaBau.trim() ? Number(this.temperaturaBau.replace(',', '.')) : null;
    if (t != null && Number.isNaN(t)) {
      this.aviso.set({ tom: 'erro', titulo: 'Temperatura inválida' });
      return;
    }
    this.ocupado.set(true);
    this.api.registrarChegada(this.nunota, { placa: this.placa, lacre: this.lacre as never, bau: this.bau as never, temperaturaBau: t, observacao: this.obs }).subscribe({
      next: () => {
        this.ocupado.set(false);
        this.feedback.trigger('PRODUTO_CORRETO');
        this.aviso.set({ tom: 'ok', titulo: 'Chegada registrada' });
        this.passo.set('doca');
        this.carregar();
      },
      error: (e) => {
        this.ocupado.set(false);
        this.aviso.set({ tom: 'erro', titulo: 'Não registrou a chegada', mensagem: erro(e) });
      },
    });
  }

  comprovante(): void {
    window.open(`/recebimento/comprovante/${this.nunota}`, '_blank');
  }

  liberarMotorista(): void {
    this.ocupado.set(true);
    this.api.liberarMotorista(this.nunota).subscribe({
      next: () => {
        this.ocupado.set(false);
        this.feedback.trigger('ETAPA_CONCLUIDA');
        this.aviso.set({ tom: 'ok', titulo: 'Motorista liberado', mensagem: 'Agora registre o lote dos paletes (Tempo 2).' });
        this.carregar();
      },
      error: (e) => {
        this.ocupado.set(false);
        this.aviso.set({ tom: 'erro', titulo: 'Não liberou', mensagem: erro(e) });
      },
    });
  }

  // ─── Tempo 2: lote / validade / avaria por palete ───────────────────────────────────────────────────

  abrirPalete(p: Palete, i: PaleteItem): void {
    this.palete.set(p);
    this.item.set(i);
    const doProduto = p.itens.filter((x) => x.codprod === i.codprod);
    this.linhas = doProduto.some((x) => x.lote)
      ? doProduto.map((x) => ({ ...this.linhaDe(x), origem: (x.origemLote as LinhaLote['origem']) ?? 'DIGITADO', qtdTexto: doProduto.length > 1 ? String(x.qtdComercial ?? x.qtd) : '' }))
      : [{ lote: '', validadeTexto: '', fabricacao: null, origem: 'DIGITADO', qtdTexto: '' }];
    this.temperaturaPalete = p.temperatura != null ? String(p.temperatura).replace('.', ',') : '';
    const av = doProduto.find((x) => x.qtdAvaria);
    this.avariaQtd = av?.qtdAvaria ? String(av.qtdAvaria) : '';
    this.avariaMotivo = av?.motivoAvaria && MOTIVOS_AVARIA.includes(av.motivoAvaria) ? av.motivoAvaria : av?.motivoAvaria ? 'Outro' : null;
    this.avariaOutro = av?.motivoAvaria && !MOTIVOS_AVARIA.includes(av.motivoAvaria) ? av.motivoAvaria : '';
    this.amostraUnidades = i.amostraUnidades != null ? String(i.amostraUnidades) : '';
    const n = (this.doca()?.logistica ?? []).find((l) => l.codprod === i.codprod);
    this.lastro = n?.lastro ? String(n.lastro) : '';
    this.camadas = n?.camadas ? String(n.camadas) : '';
    this.aviso.set(null);
    this.passo.set('palete');
    setTimeout(() => this.campo?.nativeElement.focus({ preventScroll: true }));
  }

  /** Bipe na tela do palete: GS1 preenche lote/validade/fabricação da linha em edição; texto comum vira o lote. */
  onLer(): void {
    const c = this.codigo.trim();
    this.codigo = '';
    if (!c) return;
    const linha = this.linhas[this.linhas.length - 1];
    if (pareceGs1(c)) {
      const g = lerGs1(c);
      if (g?.lote || g?.validade) {
        if (g.lote) linha.lote = g.lote;
        if (g.validade) linha.validadeTexto = dataBr(g.validade);
        if (g.fabricacao) linha.fabricacao = g.fabricacao;
        linha.origem = 'GS1';
        this.feedback.trigger('PRODUTO_CORRETO');
        this.aviso.set({ tom: 'ok', titulo: 'Etiqueta GS1 lida', mensagem: [g.lote && `Lote ${g.lote}`, g.validade && `validade ${dataBr(g.validade)}`].filter(Boolean).join(' · ') });
        return;
      }
    }
    linha.lote = c.toUpperCase().slice(0, 20);
    linha.origem = 'DIGITADO';
  }

  usarLoteXml(l: LoteXml): void {
    const linha = this.linhas[this.linhas.length - 1];
    linha.lote = l.lote;
    linha.validadeTexto = l.validade ? dataBr(l.validade) : linha.validadeTexto;
    linha.fabricacao = l.fabricacao;
    linha.origem = 'XML';
  }

  usarAnterior(): void {
    const ant = this.ultimoLote[this.item()?.codprod ?? -1];
    if (!ant) return;
    const linha = this.linhas[this.linhas.length - 1];
    linha.lote = ant.lote;
    linha.validadeTexto = ant.validadeTexto;
    linha.fabricacao = ant.fabricacao;
    linha.origem = 'ANTERIOR';
  }

  semLote(): void {
    const linha = this.linhas[this.linhas.length - 1];
    linha.lote = loteAutomatico();
    linha.origem = 'AUTOMATICO';
  }

  outroLote(): void {
    const it = this.item();
    if (this.linhas.length === 1 && !this.linhas[0].qtdTexto && it) this.linhas[0].qtdTexto = '';
    this.linhas.push({ lote: '', validadeTexto: '', fabricacao: null, origem: 'DIGITADO', qtdTexto: '' });
  }

  removerLinha(i: number): void {
    if (this.linhas.length > 1) this.linhas.splice(i, 1);
  }

  temAnterior(): boolean {
    return !!this.ultimoLote[this.item()?.codprod ?? -1];
  }

  /** Validade lida da linha + situação (vencido bloqueia; abaixo do mínimo alerta). */
  situacaoValidade(l: LinhaEdicao): { iso: string | undefined; texto: string; tom: 'ok' | 'atencao' | 'erro' | '' } {
    if (!l.validadeTexto.trim()) return { iso: undefined, texto: '', tom: '' };
    const iso = lerValidadeDigitada(l.validadeTexto);
    if (!iso) return { iso, texto: 'Use DD/MM/AA ou MM/AA', tom: 'erro' };
    const d = diasAte(iso);
    if (d < 0) return { iso, texto: `Vencido há ${-d} dias`, tom: 'erro' };
    if (d < VALIDADE_MINIMA_DIAS) return { iso, texto: `Vence em ${d} dias (mínimo ${VALIDADE_MINIMA_DIAS})`, tom: 'atencao' };
    return { iso, texto: `${dataBr(iso)} · ${d} dias`, tom: 'ok' };
  }

  salvarLote(): void {
    const p = this.palete();
    const it = this.item();
    if (!p || !it) return;
    const linhas: LinhaLote[] = [];
    const varias = this.linhas.length > 1;
    const fator = it.qtdComercial && it.qtd ? it.qtd / it.qtdComercial : 1; // qtd digitada na unidade da etiqueta
    for (const l of this.linhas) {
      if (!l.lote.trim()) return this.erroLote('Informe o lote', 'Bipe a etiqueta, escolha um lote ou use "Sem lote na caixa".');
      const v = this.situacaoValidade(l);
      if (v.tom === 'erro') return this.erroLote('Validade inválida ou vencida', v.texto);
      let qtd: number | null = null;
      if (varias) {
        const q = Number(l.qtdTexto.replace(',', '.'));
        if (!(q > 0)) return this.erroLote('Informe a quantidade de cada lote');
        qtd = Math.round(q * fator * 1000) / 1000;
      }
      linhas.push({ lote: l.lote.trim(), validade: v.iso ?? null, fabricacao: l.fabricacao, origem: l.origem, qtd });
    }
    const t = this.temperaturaPalete.trim() ? Number(this.temperaturaPalete.replace(',', '.')) : null;
    this.ocupado.set(true);
    this.api.definirLotes(p.id, it.codprod, linhas, t != null && !Number.isNaN(t) ? t : null).subscribe({
      next: () => {
        this.ocupado.set(false);
        this.ultimoLote[it.codprod] = { ...this.linhas[this.linhas.length - 1] };
        this.feedback.trigger('SUCESSO_SANKHYA');
        const alerta = this.linhas.some((l) => this.situacaoValidade(l).tom === 'atencao');
        this.aviso.set(alerta
          ? { tom: 'atencao', titulo: `Palete ${p.uma}: lote gravado`, mensagem: 'Validade abaixo do mínimo — avise o líder.' }
          : { tom: 'ok', titulo: `Palete ${p.uma}: lote gravado` });
        this.passo.set('doca');
        this.carregar();
      },
      error: (e) => {
        this.ocupado.set(false);
        this.aviso.set({ tom: 'erro', titulo: 'Não gravou o lote', mensagem: erro(e) });
      },
    });
  }

  private erroLote(titulo: string, mensagem?: string): void {
    this.feedback.trigger('OPERACAO_NAO_PERMITIDA');
    this.aviso.set({ tom: 'erro', titulo, mensagem });
  }

  salvarAvaria(): void {
    const p = this.palete();
    const it = this.item();
    if (!p || !it) return;
    const q = this.avariaQtd.trim() ? Number(this.avariaQtd.replace(',', '.')) : 0;
    if (Number.isNaN(q) || q < 0) return this.erroLote('Quantidade de avaria inválida');
    const motivo = this.avariaMotivo === 'Outro' ? this.avariaOutro.trim() : this.avariaMotivo;
    if (q > 0 && !motivo) return this.erroLote('Escolha o motivo da avaria');
    const fator = it.qtdComercial && it.qtd ? it.qtd / it.qtdComercial : 1;
    this.ocupado.set(true);
    this.api.definirAvaria(p.id, it.codprod, Math.round(q * fator * 1000) / 1000, motivo).subscribe({
      next: () => {
        this.ocupado.set(false);
        this.feedback.trigger(q > 0 ? 'CONTROLE_NECESSARIO' : 'PRODUTO_CORRETO');
        this.aviso.set({ tom: q > 0 ? 'atencao' : 'ok', titulo: q > 0 ? `Avaria registrada: ${this.avariaQtd} ${it.unidadeComercial ?? it.unidade ?? ''}` : 'Avaria removida' });
        this.carregar();
      },
      error: (e) => {
        this.ocupado.set(false);
        this.aviso.set({ tom: 'erro', titulo: 'Não gravou a avaria', mensagem: erro(e) });
      },
    });
  }

  textoAlerta(a: AlertaPedido): string {
    const f = (n: number | null) => (n == null ? '—' : n.toLocaleString('pt-BR', { maximumFractionDigits: 2 }));
    switch (a.tipo) {
      case 'SEM_PEDIDO':
        return 'não está no pedido de compra';
      case 'QTD_ACIMA':
        return `quantidade ${f(a.nota)} acima do pedido (${f(a.pedido)})`;
      case 'PRECO_ACIMA':
        return `preço R$ ${f(a.nota)} acima do pedido (R$ ${f(a.pedido)})`;
      default:
        return `preço R$ ${f(a.nota)} abaixo do pedido (R$ ${f(a.pedido)})`;
    }
  }

  /** Amostragem: abre uma caixa e conta as unidades; o esperado vem do cadastro (fator da unidade de compra). */
  salvarAmostra(): void {
    const p = this.palete();
    const it = this.item();
    const u = Number(this.amostraUnidades.replace(',', '.'));
    if (!p || !it || !(u > 0)) return this.erroLote('Informe quantas unidades havia na caixa');
    this.ocupado.set(true);
    this.api.registrarAmostra(p.id, it.codprod, u).subscribe({
      next: (nova) => {
        this.ocupado.set(false);
        const novoItem = nova.itens.find((x) => x.codprod === it.codprod);
        this.item.set(novoItem ?? it);
        const esp = novoItem?.amostraEsperado;
        if (esp != null && Math.abs(esp - u) > 0.001) {
          this.feedback.trigger('CONTROLE_NECESSARIO');
          this.aviso.set({ tom: 'atencao', titulo: `Embalagem diferente: ${u} na caixa, cadastro diz ${esp}`, mensagem: 'Avise o líder: a conversão de estoque deste produto vai errar.' });
        } else {
          this.feedback.trigger('PRODUTO_CORRETO');
          this.aviso.set({ tom: 'ok', titulo: esp != null ? 'Embalagem confere com o cadastro' : 'Amostra registrada (sem fator no cadastro)' });
        }
        this.carregar();
      },
      error: (e) => {
        this.ocupado.set(false);
        this.aviso.set({ tom: 'erro', titulo: 'Não registrou a amostra', mensagem: erro(e) });
      },
    });
  }

  /** Norma do palete na 1ª entrada do produto: caixas por camada × camadas. */
  salvarNorma(): void {
    const it = this.item();
    const l = Number(this.lastro);
    const c = Number(this.camadas);
    if (!it || !(l > 0) || !(c > 0)) return this.erroLote('Informe caixas por camada e número de camadas');
    this.ocupado.set(true);
    this.api.registrarLogistica(it.codprod, l, c).subscribe({
      next: () => {
        this.ocupado.set(false);
        this.feedback.trigger('PRODUTO_CORRETO');
        this.aviso.set({ tom: 'ok', titulo: `Norma registrada: ${l} × ${c} = ${l * c} caixas por palete` });
        this.carregar();
      },
      error: (e) => {
        this.ocupado.set(false);
        this.aviso.set({ tom: 'erro', titulo: 'Não registrou a norma', mensagem: erro(e) });
      },
    });
  }

  async tirarFoto(ev: Event): Promise<void> {
    const input = ev.target as HTMLInputElement;
    const arq = input.files?.[0];
    input.value = '';
    const p = this.palete();
    const it = this.item();
    if (!arq || !p || !it) return;
    this.enviandoFoto.set(true);
    try {
      const dataUrl = await reduzirFoto(arq);
      this.api.enviarFoto(this.nunota, p.id, it.codprod, dataUrl).subscribe({
        next: () => {
          this.enviandoFoto.set(false);
          this.feedback.trigger('PRODUTO_CORRETO');
          this.aviso.set({ tom: 'ok', titulo: 'Foto da avaria gravada' });
          this.carregar();
        },
        error: (e) => {
          this.enviandoFoto.set(false);
          this.aviso.set({ tom: 'erro', titulo: 'Não enviou a foto', mensagem: erro(e) });
        },
      });
    } catch {
      this.enviandoFoto.set(false);
      this.aviso.set({ tom: 'erro', titulo: 'Não abriu a foto' });
    }
  }

  unidadeItem(i: PaleteItem | null): string {
    return i?.unidadeComercial ?? i?.unidade ?? '';
  }

  avariaTexto(i: PaleteItem): string {
    if (!i.qtdAvaria) return '';
    const q = i.qtdComercial && i.qtd ? i.qtdAvaria / (i.qtd / i.qtdComercial) : i.qtdAvaria;
    return `${Math.round(q * 1000) / 1000} ${this.unidadeItem(i)} avariada`;
  }
}

function erro(e: unknown): string {
  if (e instanceof HttpErrorResponse) {
    if (e.status === 0) return 'Sem comunicação com o servidor.';
    return (e.error as { erro?: string } | null)?.erro ?? 'Tente de novo.';
  }
  return 'Tente de novo.';
}
