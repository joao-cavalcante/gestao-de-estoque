import { Component, OnInit, computed, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { ActivatedRoute, Router } from '@angular/router';
import { HttpErrorResponse } from '@angular/common/http';
import { Observable } from 'rxjs';
import { IntegracaoForm, MasterService } from '../master.service';
import {
  ACAO_ROTULO,
  CampoPersonalizado,
  Diagnostico,
  Instalacao,
  ItemDiagnostico,
  ParametrosTenant,
  RegistroAuditoria,
  STATUS_ROTULO,
  TenantMaster,
  UsuarioTenant,
} from '../master.model';
import { OqStatusChipComponent } from '../../conferencia/oq-status-chip/oq-status-chip.component';
import { OqIconComponent } from '../../shared/icons/oq-icon.component';
import { OqSpinnerComponent } from '../../shared/icons/oq-spinner.component';
import { ChipTone } from '../../conferencia/conferencia.model';
import { dataHora, textoIntegracao, tomIntegracao, tomStatus } from '../master-situacao';

type Aba = 'geral' | 'integracao' | 'modulos' | 'parametros' | 'campos' | 'usuarios' | 'diagnostico' | 'historico';

/** Alteração crítica aguardando o Master digitar o identificador do cliente (conferido também no backend). */
interface Confirmacao {
  titulo: string;
  descricao: string;
  executar: (confirmacao: string) => Observable<unknown>;
  sucesso: string;
}

/** Tolerância de pesável sem NUCCO, por sentido: não libera | até X% | sem limite. */
type ModoSentido = 'nao' | 'pct' | 'sem';

const FUSOS = ['America/Sao_Paulo', 'America/Bahia', 'America/Fortaleza', 'America/Recife', 'America/Belem', 'America/Manaus', 'America/Cuiaba', 'America/Campo_Grande', 'America/Porto_Velho', 'America/Boa_Vista', 'America/Rio_Branco', 'America/Noronha'];

/** Painel Master — configuração de UM cliente. Nada de regra de negócio aqui: o backend valida tudo. */
@Component({
  selector: 'app-master-tenant',
  standalone: true,
  imports: [FormsModule, OqStatusChipComponent, OqIconComponent, OqSpinnerComponent],
  templateUrl: './master-tenant.component.html',
  styleUrl: '../master.scss',
})
export class MasterTenantComponent implements OnInit {
  private readonly service = inject(MasterService);
  private readonly route = inject(ActivatedRoute);
  readonly router = inject(Router);

  readonly abas: { valor: Aba; label: string }[] = [
    { valor: 'geral', label: 'Dados gerais' },
    { valor: 'integracao', label: 'Integração Sankhya' },
    { valor: 'modulos', label: 'Módulos' },
    { valor: 'parametros', label: 'Parâmetros operacionais' },
    { valor: 'campos', label: 'Campos personalizados' },
    { valor: 'usuarios', label: 'Usuários e permissões' },
    { valor: 'diagnostico', label: 'Diagnóstico' },
    { valor: 'historico', label: 'Histórico' },
  ];
  readonly FUSOS = FUSOS;
  readonly STATUS_ROTULO = STATUS_ROTULO;
  readonly dataHora = dataHora;

  id = '';
  readonly aba = signal<Aba>('geral');
  readonly tm = signal<TenantMaster | null>(null);
  readonly instalacao = signal<Instalacao | null>(null);
  readonly carregando = signal(true);
  readonly erro = signal<string | null>(null);
  readonly mensagem = signal<string | null>(null);

  readonly conexao = computed(() => this.tm()?.tenant.erpConnections.find((c) => c.erpType === 'sankhya') ?? null);

  // Dados gerais
  nome = '';
  motivoStatus = '';

  // Integração
  integ: IntegracaoForm = { baseUrl: '', gatewayPath: null, dialect: 'ORACLE', ativo: true };
  clientId = '';
  clientSecret = '';
  xToken = '';
  readonly testando = signal(false);
  readonly resultadoTeste = signal<string | null>(null);

  // Módulos
  modulosSel = new Set<string>();

  // Parâmetros
  param: ParametrosTenant | null = null;
  eventosTexto = '';
  modoTolerancia: 'produto' | 'instalacao' = 'produto';
  modoAcima: ModoSentido = 'nao';
  modoAbaixo: ModoSentido = 'nao';
  pctAcima = '';
  pctAbaixo = '';
  liberadorUsuario = '';
  liberadorSenha = '';

  // Leituras sob demanda
  readonly campos = signal<CampoPersonalizado[]>([]);
  readonly usuarios = signal<UsuarioTenant[] | null>(null);
  readonly diagnostico = signal<Diagnostico | null>(null);
  readonly diagnosticando = signal(false);
  readonly historico = signal<RegistroAuditoria[] | null>(null);

  readonly existenciaCampos = computed(() => {
    const mapa = new Map<string, ItemDiagnostico>();
    this.diagnostico()?.itens.filter((i) => i.grupo === 'Campos personalizados').forEach((i) => mapa.set(i.titulo, i));
    return mapa;
  });

  // Confirmação
  readonly confirmacao = signal<Confirmacao | null>(null);
  readonly confirmando = signal(false);
  readonly confirmacaoErro = signal<string | null>(null);
  textoConfirmacao = '';

  ngOnInit(): void {
    this.id = this.route.snapshot.paramMap.get('id') ?? '';
    this.service.instalacao().subscribe({ next: (i) => this.instalacao.set(i) });
    this.service.camposPersonalizados().subscribe({ next: (c) => this.campos.set(c) });
    this.recarregar();
  }

  recarregar(): void {
    this.carregando.set(true);
    this.service.buscar(this.id).subscribe({
      next: (tm) => {
        this.tm.set(tm);
        this.preencherFormularios(tm);
        this.carregando.set(false);
      },
      error: (e: HttpErrorResponse) => {
        this.carregando.set(false);
        this.erro.set(e.status === 404 ? 'Cliente não encontrado.' : 'Não foi possível carregar o cliente.');
      },
    });
  }

  private preencherFormularios(tm: TenantMaster): void {
    const c = tm.tenant.erpConnections.find((x) => x.erpType === 'sankhya');
    this.nome = tm.tenant.nome;
    this.integ = { baseUrl: c?.baseUrl ?? '', gatewayPath: c?.gatewayPath ?? null, dialect: c?.dialect ?? 'ORACLE', ativo: c?.ativo ?? true };
    this.clientId = this.clientSecret = this.xToken = '';
    this.modulosSel = new Set(c?.modulos ?? []);
    if (c) this.carregarParametros(c.parametros);
  }

  private carregarParametros(p: ParametrosTenant): void {
    this.param = structuredClone(p);
    this.eventosTexto = p.liberacaoHerdada.eventos.join(', ');
    const t = p.toleranciaPesavel.semConfiguracaoNucco;
    this.modoTolerancia = t ? 'instalacao' : 'produto';
    this.modoAcima = !t ? 'nao' : t.acimaSemLimite ? 'sem' : t.acimaPct != null ? 'pct' : 'nao';
    this.modoAbaixo = !t ? 'nao' : t.abaixoSemLimite ? 'sem' : t.abaixoPct != null ? 'pct' : 'nao';
    this.pctAcima = t?.acimaPct != null ? String(t.acimaPct) : '';
    this.pctAbaixo = t?.abaixoPct != null ? String(t.abaixoPct) : '';
  }

  trocarAba(aba: Aba): void {
    this.aba.set(aba);
    this.mensagem.set(null);
    if (aba === 'usuarios' && this.usuarios() === null) {
      this.service.usuarios(this.id).subscribe({ next: (u) => this.usuarios.set(u), error: () => this.usuarios.set([]) });
    }
    if (aba === 'historico') this.carregarHistorico();
  }

  carregarHistorico(): void {
    this.service.auditoria(this.id).subscribe({ next: (h) => this.historico.set(h), error: () => this.historico.set([]) });
  }

  // ─── Situação ─────────────────────────────────────────────────────────

  tomStatus(): ChipTone {
    return tomStatus(this.tm()?.tenant.status ?? 'trial');
  }

  tomIntegracao(): ChipTone {
    const tm = this.tm();
    return tm ? tomIntegracao(tm) : 'neutral';
  }

  textoIntegracao(): string {
    const tm = this.tm();
    return tm ? textoIntegracao(tm) : '';
  }

  // ─── Dados gerais ─────────────────────────────────────────────────────

  salvarNome(): void {
    this.service.salvarDadosGerais(this.id, this.nome.trim()).subscribe({
      next: () => this.concluir('Dados gerais salvos.'),
      error: (e: HttpErrorResponse) => this.erro.set(e.error?.erro ?? 'Não foi possível salvar.'),
    });
  }

  pedirStatus(status: 'active' | 'suspended' | 'trial'): void {
    const textos = {
      active: ['Ativar cliente', 'Libera o login e a sincronização deste cliente.'],
      suspended: ['Desativar cliente', 'Bloqueia novos logins e para a sincronização com o Sankhya. Nenhum dado é apagado; dá para reativar.'],
      trial: ['Voltar para implantação', 'O cliente continua acessando; serve para marcar instalação ainda em configuração.'],
    }[status];
    const motivo = this.motivoStatus.trim();
    this.pedirConfirmacao({
      titulo: textos[0],
      descricao: textos[1],
      executar: (c) => this.service.alterarStatus(this.id, status, motivo, c),
      sucesso: 'Situação do cliente alterada.',
    });
  }

  // ─── Integração ───────────────────────────────────────────────────────

  salvarIntegracao(): void {
    const form: IntegracaoForm = {
      ...this.integ,
      clientId: this.clientId.trim() || null,
      clientSecret: this.clientSecret || null,
      xToken: this.xToken || null,
    };
    const trocaCredencial = !!(form.clientId || form.clientSecret || form.xToken);
    this.pedirConfirmacao({
      titulo: 'Alterar integração Sankhya',
      descricao: `Endereço ${form.baseUrl}${trocaCredencial ? ' e NOVAS credenciais' : ' (credenciais mantidas)'}. Toda chamada ao Sankhya deste cliente passa a usar estes dados.`,
      executar: (c) => this.service.salvarIntegracao(this.id, form, c),
      sucesso: 'Integração salva.',
    });
  }

  testarIntegracao(): void {
    this.testando.set(true);
    this.resultadoTeste.set(null);
    this.service.testarIntegracao(this.id).subscribe({
      next: (r) => {
        this.testando.set(false);
        this.resultadoTeste.set(r.autenticado === 'true' ? 'Autenticado no Sankhya.' : `Falhou: ${r.erro ?? 'sem detalhe'}`);
      },
      error: () => {
        this.testando.set(false);
        this.resultadoTeste.set('Não foi possível testar.');
      },
    });
  }

  // ─── Módulos ──────────────────────────────────────────────────────────

  alternarModulo(id: string, marcado: boolean): void {
    const novo = new Set(this.modulosSel);
    if (marcado) novo.add(id);
    else novo.delete(id);
    this.modulosSel = novo;
  }

  modulosDesconhecidos(): string[] {
    const conhecidos = new Set(this.instalacao()?.modulos.map((m) => m.id) ?? []);
    return [...this.modulosSel].filter((m) => !conhecidos.has(m));
  }

  salvarModulos(): void {
    const lista = [...this.modulosSel].sort();
    this.pedirConfirmacao({
      titulo: 'Alterar módulos',
      descricao: `Módulos ligados: ${lista.length ? lista.join(', ') : 'nenhum'}. Vale para as próximas operações do cliente.`,
      executar: (c) => this.service.salvarModulos(this.id, lista, c),
      sucesso: 'Módulos salvos.',
    });
  }

  // ─── Parâmetros ───────────────────────────────────────────────────────

  private montarParametros(): ParametrosTenant | string {
    if (!this.param) return 'cadastre a integração Sankhya antes';
    const eventos = this.eventosTexto.split(/[,;\s]+/).filter(Boolean).map(Number);
    if (eventos.some((n) => !Number.isInteger(n) || n <= 0)) return 'eventos: informe códigos inteiros separados por vírgula';
    if (eventos.includes(64)) return 'eventos: o corte (evento 64) nunca é herdado';
    const pct = (v: string) => (v.trim() === '' ? null : Number(v.replace(',', '.')));
    const acima = this.modoAcima === 'pct' ? pct(this.pctAcima) : null;
    const abaixo = this.modoAbaixo === 'pct' ? pct(this.pctAbaixo) : null;
    if (this.modoTolerancia === 'instalacao' && [acima, abaixo].some((v) => v !== null && (Number.isNaN(v) || v < 0))) {
      return 'tolerância: percentual inválido';
    }
    if (this.modoTolerancia === 'instalacao' && ((this.modoAcima === 'pct' && acima === null) || (this.modoAbaixo === 'pct' && abaixo === null))) {
      return 'tolerância: informe o percentual';
    }
    const u = this.param.usuarioSankhya;
    const aj = this.param.ajustePesoCompra ?? {
      habilitado: false,
      ajusteEntrada: false,
      ajusteSaida: false,
      topEntrada: null,
      topSaida: null,
      serieEntrada: null,
      serieSaida: null,
      codTipVenda: null,
      codParc: null,
    };
    const num = (v: unknown) => (v === null || v === undefined || `${v}`.trim() === '' ? null : Number(v));
    const serie = (v: string | null | undefined) => (v ?? '').toString().trim() || null;
    const ajuste = {
      habilitado: !!aj.habilitado,
      ajusteEntrada: !!aj.ajusteEntrada,
      ajusteSaida: !!aj.ajusteSaida,
      topEntrada: num(aj.topEntrada),
      topSaida: num(aj.topSaida),
      serieEntrada: serie(aj.serieEntrada),
      serieSaida: serie(aj.serieSaida),
      codTipVenda: num(aj.codTipVenda),
      codParc: num(aj.codParc),
    };
    if ([ajuste.serieEntrada, ajuste.serieSaida].some((v) => v !== null && v.length > 3)) {
      return 'ajuste de peso: série com até 3 caracteres';
    }
    if ([ajuste.topEntrada, ajuste.topSaida].some((v) => v !== null && (!Number.isInteger(v) || v <= 0))) {
      return 'ajuste de peso: informe o código da TOP';
    }
    if (ajuste.ajusteEntrada && ajuste.topEntrada === null) return 'diferença de peso: ajuste a maior ligado sem TOP de entrada';
    if (ajuste.ajusteSaida && ajuste.topSaida === null) return 'diferença de peso: ajuste a menor ligado sem TOP de saída';
    return {
      ...this.param,
      liberacaoHerdada: { ...this.param.liberacaoHerdada, eventos, todosEventos: !!this.param.liberacaoHerdada.todosEventos },
      toleranciaPesavel: {
        semConfiguracaoNucco:
          this.modoTolerancia === 'produto'
            ? null
            : { acimaPct: acima, acimaSemLimite: this.modoAcima === 'sem', abaixoPct: abaixo, abaixoSemLimite: this.modoAbaixo === 'sem' },
      },
      fuso: this.param.fuso?.trim() || null,
      usuarioSankhya: {
        ...u,
        usuarioModelo: u.usuarioModelo ? Number(u.usuarioModelo) : null,
        codGrupo: u.codGrupo !== null && `${u.codGrupo}` !== '' ? Number(u.codGrupo) : null,
        dominioEmail: u.dominioEmail?.trim() || null,
      },
      ajustePesoCompra: ajuste,
    };
  }

  salvarParametros(): void {
    const p = this.montarParametros();
    if (typeof p === 'string') {
      this.erro.set(p);
      return;
    }
    this.erro.set(null);
    this.pedirConfirmacao({
      titulo: 'Alterar parâmetros operacionais',
      descricao: 'Liberação herdada, tolerância de pesáveis, fuso do faturamento, criação de usuários e ajuste de peso na compra deste cliente. Afeta as próximas operações.',
      executar: (c) => this.service.salvarParametros(this.id, p, c),
      sucesso: 'Parâmetros salvos.',
    });
  }

  salvarLiberador(): void {
    const usuario = this.liberadorUsuario.trim();
    const senha = this.liberadorSenha;
    if (!usuario || !senha) {
      this.erro.set('Informe usuário e senha do liberador.');
      return;
    }
    this.erro.set(null);
    this.pedirConfirmacao({
      titulo: 'Gravar credencial do liberador',
      descricao: `O usuário ${usuario} será validado no Sankhya DESTE cliente e passa a fazer as liberações automáticas dele.`,
      executar: (c) => this.service.salvarLiberador(this.id, usuario, senha, c),
      sucesso: 'Credencial do liberador gravada.',
    });
  }

  removerLiberador(): void {
    this.pedirConfirmacao({
      titulo: 'Remover credencial do liberador',
      descricao: 'Sem credencial, a auto-liberação de pesáveis e a liberação herdada deste cliente passam para a liberação manual.',
      executar: (c) => this.service.salvarLiberador(this.id, null, null, c),
      sucesso: 'Credencial do liberador removida.',
    });
  }

  quantidadeCamposFixos(): number {
    return Object.keys(this.param?.usuarioSankhya.camposFixos ?? {}).length;
  }

  // ─── Diagnóstico ──────────────────────────────────────────────────────

  executarDiagnostico(): void {
    this.diagnosticando.set(true);
    this.service.diagnostico(this.id).subscribe({
      next: (d) => {
        this.diagnostico.set(d);
        this.diagnosticando.set(false);
      },
      error: () => {
        this.diagnosticando.set(false);
        this.erro.set('Não foi possível executar o diagnóstico.');
      },
    });
  }

  tomDiagnostico(s: ItemDiagnostico['situacao']): ChipTone {
    return s === 'OK' ? 'success' : s === 'ALERTA' ? 'warning' : s === 'ERRO' ? 'critical' : 'neutral';
  }

  contagem(s: ItemDiagnostico['situacao']): number {
    return this.diagnostico()?.itens.filter((i) => i.situacao === s).length ?? 0;
  }

  // ─── Histórico ────────────────────────────────────────────────────────

  rotuloAcao(acao: string): string {
    return ACAO_ROTULO[acao] ?? acao;
  }

  detalhe(r: RegistroAuditoria): string {
    const d = r.detalhe;
    return Object.keys(d).length ? JSON.stringify(d) : '';
  }

  tomResultado(r: RegistroAuditoria): ChipTone {
    return r.resultado === 'ok' ? 'success' : r.resultado === 'negado' ? 'warning' : 'critical';
  }

  // ─── Confirmação ──────────────────────────────────────────────────────

  private pedirConfirmacao(c: Confirmacao): void {
    this.textoConfirmacao = '';
    this.confirmacaoErro.set(null);
    this.confirmacao.set(c);
  }

  cancelarConfirmacao(): void {
    if (!this.confirmando()) this.confirmacao.set(null);
  }

  confirmar(): void {
    const c = this.confirmacao();
    if (!c) return;
    this.confirmando.set(true);
    this.confirmacaoErro.set(null);
    c.executar(this.textoConfirmacao.trim()).subscribe({
      next: () => {
        this.confirmando.set(false);
        this.confirmacao.set(null);
        this.liberadorSenha = '';
        this.concluir(c.sucesso);
      },
      error: (e: HttpErrorResponse) => {
        this.confirmando.set(false);
        this.confirmacaoErro.set(e.error?.erro ?? 'A alteração não foi aplicada.');
      },
    });
  }

  private concluir(texto: string): void {
    this.mensagem.set(texto);
    this.erro.set(null);
    this.recarregar();
    if (this.historico() !== null) this.carregarHistorico();
  }
}
