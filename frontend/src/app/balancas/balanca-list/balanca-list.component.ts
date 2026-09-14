import { Component, OnInit, OnDestroy, inject, signal } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { Subscription } from 'rxjs';
import { BalancaService } from '../balanca.service';
import { Balanca, SalvarBalancaRequest, TipoComunicacao } from '../balanca.model';
import { LocalScaleService, StatusBalanca } from '../local-scale.service';
import { OqPanelSectionComponent } from '../../conferencia/oq-panel-section/oq-panel-section.component';
import { OqStatusChipComponent } from '../../conferencia/oq-status-chip/oq-status-chip.component';
import { OqIconComponent, OqIconName } from '../../shared/icons/oq-icon.component';

/** Form usado tanto pra criar quanto editar — mesmos campos do sistema atual (balanca.dto.ts). */
interface FormBalanca {
  nome: string;
  tipoComunicacao: TipoComunicacao;
  fabricante: string;
  ip: string;
  porta: number | null;
  rota: string;
  portaCom: string;
  baudRate: number | null;
  dataBits: number | null;
  paridade: string;
  stopBits: number | null;
  protocoloSerial: string;
}

interface LinhaLog {
  hora: string;
  texto: string;
  ok: boolean;
}

function formVazio(): FormBalanca {
  return {
    nome: '', tipoComunicacao: 'HTTP', fabricante: '', ip: '', porta: null, rota: '',
    portaCom: '', baudRate: 9600, dataBits: 8, paridade: 'N', stopBits: 1, protocoloSerial: 'CONTINUO',
  };
}

@Component({
  selector: 'app-balanca-list',
  standalone: true,
  imports: [CommonModule, FormsModule, OqPanelSectionComponent, OqStatusChipComponent, OqIconComponent],
  templateUrl: './balanca-list.component.html',
})
export class BalancaListComponent implements OnInit, OnDestroy {
  private readonly service = inject(BalancaService);
  private readonly agente = inject(LocalScaleService);
  private subs: Subscription[] = [];

  balancas = signal<Balanca[]>([]);
  carregando = signal(true);

  // Modal criar/editar
  modalAberto = signal(false);
  editandoId = signal<string | null>(null);
  form: FormBalanca = formVazio();
  modalErro = signal<string | null>(null);
  modalCarregando = signal(false);

  // Teste de comunicação (dentro do modal)
  statusTeste = signal<StatusBalanca | 'erro'>('desconectado');
  pesoAtual = signal<number | null>(null);
  logs = signal<LinhaLog[]>([]);
  lendoSerial = signal(false);

  // Modal confirmação de exclusão
  removendoBalanca = signal<Balanca | null>(null);
  removendoCarregando = signal(false);

  // Portas COM disponíveis (via agente local)
  portasDisponiveis = signal<string[]>([]);
  buscandoPortas = signal(false);

  private subPortas: Subscription | null = null;

  ngOnInit(): void {
    this.carregar();
    this.subPortas = this.agente.portas$.subscribe((portas) => {
      this.portasDisponiveis.set(portas);
      this.buscandoPortas.set(false);
    });
  }

  ngOnDestroy(): void {
    this.subs.forEach((s) => s.unsubscribe());
    this.subPortas?.unsubscribe();
  }

  /** Pede ao agente local a lista de portas COM disponíveis na estação. */
  buscarPortas(): void {
    this.buscandoPortas.set(true);
    this.agente.conectar();
    if (this.agente.obterStatus() === 'conectado') {
      this.agente.listarPortas();
    } else {
      this.subs.push(
        this.agente.status$.subscribe((s) => {
          if (s === 'conectado') this.agente.listarPortas();
        }),
      );
    }
  }

  carregar(): void {
    this.carregando.set(true);
    this.service.listar().subscribe({
      next: (bs) => {
        this.balancas.set(bs);
        this.carregando.set(false);
      },
      error: () => this.carregando.set(false),
    });
  }

  ehSerial(): boolean {
    return this.form.tipoComunicacao === 'SERIAL_USB' || this.form.tipoComunicacao === 'SERIAL_RS232';
  }

  abrirCriacao(): void {
    this.editandoId.set(null);
    this.form = formVazio();
    this.modalErro.set(null);
    this.resetarTeste();
    this.modalAberto.set(true);
  }

  /** Chamado ao trocar o select de tipo de comunicação — busca as portas assim que vira serial. */
  aoTrocarTipo(): void {
    if (this.ehSerial() && this.portasDisponiveis().length === 0) {
      this.buscarPortas();
    }
  }

  abrirEdicao(b: Balanca): void {
    this.editandoId.set(b.id);
    this.form = {
      nome: b.nome,
      tipoComunicacao: b.tipoComunicacao,
      fabricante: b.fabricante ?? '',
      ip: b.ip ?? '',
      porta: b.porta,
      rota: b.rota ?? '',
      portaCom: b.portaCom ?? '',
      baudRate: b.baudRate ?? 9600,
      dataBits: b.dataBits ?? 8,
      paridade: b.paridade ?? 'N',
      stopBits: b.stopBits ?? 1,
      protocoloSerial: b.protocoloSerial ?? 'CONTINUO',
    };
    this.modalErro.set(null);
    this.resetarTeste();
    this.modalAberto.set(true);
    if (this.ehSerial()) this.buscarPortas();
  }

  fecharModal(): void {
    this.pararLeitura();
    this.modalAberto.set(false);
  }

  salvar(): void {
    this.modalErro.set(null);
    const ehHttp = this.form.tipoComunicacao === 'HTTP';
    const req: SalvarBalancaRequest = {
      nome: this.form.nome,
      fabricante: this.form.fabricante || null,
      tipoComunicacao: this.form.tipoComunicacao,
      portaCom: ehHttp ? null : this.form.portaCom,
      baudRate: ehHttp ? null : this.form.baudRate,
      dataBits: ehHttp ? null : this.form.dataBits,
      paridade: ehHttp ? null : this.form.paridade,
      stopBits: ehHttp ? null : this.form.stopBits,
      protocoloSerial: ehHttp ? null : this.form.protocoloSerial,
      ip: ehHttp ? this.form.ip : null,
      porta: ehHttp ? this.form.porta : null,
      rota: ehHttp ? this.form.rota : null,
      ativo: true,
    };

    this.modalCarregando.set(true);
    const id = this.editandoId();
    const obs = id ? this.service.atualizar(id, req) : this.service.criar(req);
    obs.subscribe({
      next: () => {
        this.modalCarregando.set(false);
        this.fecharModal();
        this.carregar();
      },
      error: (err) => {
        this.modalCarregando.set(false);
        this.modalErro.set(err?.error?.erro ?? 'Falha ao salvar balança.');
      },
    });
  }

  pedirExclusao(b: Balanca): void {
    this.removendoBalanca.set(b);
  }

  cancelarExclusao(): void {
    this.removendoBalanca.set(null);
  }

  confirmarExclusao(): void {
    const b = this.removendoBalanca();
    if (!b) return;
    this.removendoCarregando.set(true);
    this.service.remover(b.id).subscribe({
      next: () => {
        this.removendoCarregando.set(false);
        this.removendoBalanca.set(null);
        this.carregar();
      },
      error: () => {
        this.removendoCarregando.set(false);
        this.removendoBalanca.set(null);
      },
    });
  }

  private resetarTeste(): void {
    this.statusTeste.set('desconectado');
    this.pesoAtual.set(null);
    this.logs.set([]);
    this.lendoSerial.set(false);
  }

  private registrarLog(texto: string, ok: boolean): void {
    const hora = new Date().toLocaleTimeString('pt-BR');
    this.logs.update((ls) => [...ls, { hora, texto, ok }].slice(-50));
  }

  /** HTTP: uma chamada única via backend (BalancaHttpClient). */
  testarHttp(): void {
    const id = this.editandoId();
    if (!id) {
      this.registrarLog('Salve a configuração antes de testar.', false);
      return;
    }
    this.statusTeste.set('conectando');
    this.registrarLog('Testando conexão HTTP…', true);
    this.service.capturarPeso(id).subscribe({
      next: (res) => {
        this.statusTeste.set('conectado');
        this.pesoAtual.set(res.peso);
        this.registrarLog(`Peso capturado: ${res.peso} kg`, true);
      },
      error: (err) => {
        this.statusTeste.set('erro');
        this.registrarLog(`Erro: ${err?.error?.erro ?? 'falha na comunicação'}`, false);
      },
    });
  }

  /** Serial: usa o agente local (Electron, ws://127.0.0.1:3099) — mesmo protocolo do sistema atual. */
  iniciarLeituraSerial(): void {
    if (!this.form.portaCom) {
      this.registrarLog('Informe a porta COM antes de iniciar a leitura.', false);
      return;
    }
    this.registrarLog(`Conectando ao agente local — porta ${this.form.portaCom}…`, true);
    this.agente.conectar();

    this.subs.push(
      this.agente.status$.subscribe((s) => {
        this.statusTeste.set(s);
        if (s === 'conectado') {
          this.agente.subscribe(this.form.portaCom);
          this.registrarLog('Agente conectado — aguardando leituras…', true);
        }
      }),
    );
    this.subs.push(
      this.agente.pesoEstavel$.subscribe((leitura) => {
        this.pesoAtual.set(leitura.peso);
        this.registrarLog(`Peso estável: ${leitura.peso.toFixed(3)} kg`, true);
      }),
    );
    this.subs.push(
      this.agente.erro$.subscribe((msg) => {
        this.statusTeste.set('erro');
        this.registrarLog(`Erro do agente: ${msg}`, false);
      }),
    );
    this.lendoSerial.set(true);
  }

  pararLeitura(): void {
    if (this.lendoSerial() && this.form.portaCom) {
      this.agente.unsubscribe(this.form.portaCom);
    }
    this.subs.forEach((s) => s.unsubscribe());
    this.subs = [];
    this.lendoSerial.set(false);
  }

  limparLog(): void {
    this.logs.set([]);
  }

  endereco(b: Balanca): string {
    return b.tipoComunicacao === 'HTTP'
      ? `${b.ip}:${b.porta}${b.rota ?? ''}`
      : `${b.portaCom ?? '—'} · ${b.baudRate ?? '—'}-${b.dataBits ?? '—'}${b.paridade ?? '—'}${b.stopBits ?? '—'} · ${b.protocoloSerial ?? '—'}`;
  }

  iconePorTipo(tipo: TipoComunicacao): OqIconName {
    return tipo === 'HTTP' ? 'sync' : 'scale';
  }
}
