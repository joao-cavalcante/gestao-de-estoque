import { Injectable } from '@angular/core';
import { Subject } from 'rxjs';

/**
 * Cliente do Agente de Balanças local (Electron, reaproveitado sem
 * nenhuma mudança — roda em ws://127.0.0.1:3099). Mesmo protocolo JSON do
 * sistema atual:
 *   cliente->agente: {tipo:'subscribe'|'unsubscribe'|'listar-portas', portaCom}
 *   agente->cliente: {tipo:'peso'|'erro'|'portas', ...}
 *
 * Estabilização é client-side (não faz parte do protocolo — o agente
 * sempre manda um peso, sem indicar estabilidade real): peso dentro de
 * ±0.005kg de uma referência por 2s seguidos = estável.
 *
 * Uma estação pode ter mais de uma balança no mesmo agente: só a porta
 * assinada por último é ouvida (leitura de outra porta é descartada), senão
 * os pesos das duas se misturam e o display fica oscilando.
 */
export type StatusBalanca = 'desconectado' | 'conectando' | 'conectado';

export interface LeituraPeso {
  portaCom: string;
  peso: number;
  estavel: boolean;
}

const URL_AGENTE = 'ws://127.0.0.1:3099';
const LIMIAR_ESTABILIDADE_KG = 0.005;
const TEMPO_ESTABILIDADE_MS = 2000;
const RECONEXAO_MS = 3000;
/** Abaixo disso o display é considerado "zerado". */
const LIMIAR_ZERO_KG = 0.005;
/** A balança em modo contínuo intercala frames de 0 com o peso real (motion/entre
 * frames). Só aceita a ida pra zero se ela PERSISTIR esse tempo — senão o display
 * fica piscando entre o valor e 0. */
const TEMPO_ZERO_MS = 800;

@Injectable({ providedIn: 'root' })
export class LocalScaleService {
  private socket: WebSocket | null = null;
  private status: StatusBalanca = 'desconectado';
  private pesoAnterior: number | null = null;
  private timerEstabilidade: ReturnType<typeof setTimeout> | null = null;
  private timerZero: ReturnType<typeof setTimeout> | null = null;
  private taraValor = 0;
  /** Porta assinada no momento — leituras de outras portas são ignoradas. */
  private portaAtiva: string | null = null;
  /** Peso de referência da janela de estabilidade atual. */
  private referenciaEstavel: number | null = null;
  /** Última leitura que completou 2s estável e ainda não variou depois disso. */
  private leituraEstavel: LeituraPeso | null = null;

  readonly peso$ = new Subject<LeituraPeso>();
  readonly pesoEstavel$ = new Subject<LeituraPeso>();
  readonly erro$ = new Subject<string>();
  readonly status$ = new Subject<StatusBalanca>();
  readonly portas$ = new Subject<string[]>();

  conectar(): void {
    if (this.socket) return;
    this.abrirConexao();
  }

  private abrirConexao(): void {
    this.atualizarStatus('conectando');
    this.socket = new WebSocket(URL_AGENTE);

    this.socket.onopen = () => this.atualizarStatus('conectado');

    this.socket.onmessage = (evento) => {
      const msg = JSON.parse(evento.data);
      if (msg.tipo === 'peso') {
        this.processarLeitura({ portaCom: msg.portaCom, peso: msg.valor, estavel: msg.estavel });
      } else if (msg.tipo === 'erro') {
        this.erro$.next(msg.mensagem);
      } else if (msg.tipo === 'portas') {
        // O agente local (fila-conferencia-agente-local/src/wsServer.js) responde
        // { tipo:'portas', valores:[...] } — nunca 'portas'.
        this.portas$.next(msg.valores ?? msg.portas ?? []);
      }
    };

    this.socket.onclose = () => {
      this.socket = null;
      this.atualizarStatus('desconectado');
      setTimeout(() => this.abrirConexao(), RECONEXAO_MS);
    };

    this.socket.onerror = () => this.socket?.close();
  }

  subscribe(portaCom: string): void {
    // Trocou de balança: solta a porta anterior no agente e zera a tara (era da outra balança).
    if (this.portaAtiva && !this.mesmaPorta(this.portaAtiva, portaCom)) {
      this.enviar({ tipo: 'unsubscribe', portaCom: this.portaAtiva });
      this.taraValor = 0;
    }
    this.portaAtiva = portaCom;
    // Novo ciclo de leitura — zera o estado de estabilização/anti-flicker.
    this.pesoAnterior = null;
    this.referenciaEstavel = null;
    this.leituraEstavel = null;
    if (this.timerZero) {
      clearTimeout(this.timerZero);
      this.timerZero = null;
    }
    if (this.timerEstabilidade) {
      clearTimeout(this.timerEstabilidade);
      this.timerEstabilidade = null;
    }
    this.enviar({ tipo: 'subscribe', portaCom });
  }

  /** Solta a porta informada — ou, sem argumento, a porta assinada no momento. */
  unsubscribe(portaCom?: string): void {
    const porta = portaCom ?? this.portaAtiva;
    if (!porta) return;
    this.enviar({ tipo: 'unsubscribe', portaCom: porta });
    if (this.portaAtiva && this.mesmaPorta(this.portaAtiva, porta)) {
      this.portaAtiva = null;
      if (this.timerEstabilidade) {
        clearTimeout(this.timerEstabilidade);
        this.timerEstabilidade = null;
      }
      this.leituraEstavel = null;
    }
  }

  /** Leitura estável vigente (peso parado há 2s+ e sem variar depois), se houver. */
  leituraEstavelAtual(): LeituraPeso | null {
    return this.leituraEstavel;
  }

  private mesmaPorta(a: string, b: string): boolean {
    return a.trim().toUpperCase() === b.trim().toUpperCase();
  }

  listarPortas(): void {
    this.enviar({ tipo: 'listar-portas' });
  }

  /** Zera o display: peso bruto atual vira a referência de tara. */
  tarar(pesoBruto: number): void {
    this.taraValor = pesoBruto;
  }

  private processarLeitura(leitura: LeituraPeso): void {
    // Leitura de outra balança do mesmo agente (porta não assinada por esta tela) — ignora.
    if (!this.portaAtiva || (leitura.portaCom && !this.mesmaPorta(leitura.portaCom, this.portaAtiva))) return;
    const pesoLiquido = Math.max(0, leitura.peso - this.taraValor);
    // Reset automático de tara: se o peso bruto cair bem abaixo da tara
    // (objeto retirado), zera sozinho pra não travar o display em 0.
    if (leitura.peso < this.taraValor - 0.1) {
      this.taraValor = 0;
    }

    const ehZero = pesoLiquido < LIMIAR_ZERO_KG;
    // pesoAnterior null (logo após subscribe/reconexão) NÃO conta como "já
    // zerado" — senão a primeira leitura pós-reset pula a proteção de
    // TEMPO_ZERO_MS e um frame de "0" transitório vaza direto pro display.
    const displayEstavaZerado = this.pesoAnterior !== null && this.pesoAnterior < LIMIAR_ZERO_KG;

    // Frame zerado enquanto o display mostra peso → provável "0" transitório do
    // modo contínuo. Segura a emissão; só zera de verdade se persistir.
    if (ehZero && !displayEstavaZerado) {
      if (!this.timerZero) {
        this.timerZero = setTimeout(() => {
          this.timerZero = null;
          this.emitir({ ...leitura, peso: 0 });
        }, TEMPO_ZERO_MS);
      }
      return;
    }

    // Peso real (ou já estava zerado) — emite na hora e cancela qualquer
    // pendência de zerar.
    if (this.timerZero) {
      clearTimeout(this.timerZero);
      this.timerZero = null;
    }
    this.emitir({ ...leitura, peso: pesoLiquido });
  }

  private emitir(leituraAjustada: LeituraPeso): void {
    this.peso$.next(leituraAjustada);

    this.pesoAnterior = leituraAjustada.peso;

    // Compara com a referência da janela (não com o frame anterior) — senão um
    // peso subindo devagar, < 0.005kg por frame, "estabilizaria" no meio do caminho.
    const variou =
      this.referenciaEstavel === null || Math.abs(leituraAjustada.peso - this.referenciaEstavel) >= LIMIAR_ESTABILIDADE_KG;

    if (variou) {
      this.referenciaEstavel = leituraAjustada.peso;
      this.leituraEstavel = null;
      if (this.timerEstabilidade) clearTimeout(this.timerEstabilidade);
      this.timerEstabilidade = setTimeout(() => {
        this.timerEstabilidade = null;
        this.leituraEstavel = leituraAjustada;
        this.pesoEstavel$.next(leituraAjustada);
      }, TEMPO_ESTABILIDADE_MS);
    } else if (this.leituraEstavel) {
      // Segue estável — mantém o valor mais recente (dentro da tolerância).
      this.leituraEstavel = leituraAjustada;
    }
  }

  private enviar(msg: unknown): void {
    if (this.socket?.readyState === WebSocket.OPEN) {
      this.socket.send(JSON.stringify(msg));
    }
  }

  private atualizarStatus(status: StatusBalanca): void {
    this.status = status;
    this.status$.next(status);
  }

  obterStatus(): StatusBalanca {
    return this.status;
  }
}
