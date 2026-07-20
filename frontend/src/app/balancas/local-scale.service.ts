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
 * sempre manda um peso, sem indicar estabilidade real): debounce de 2s
 * sobre variação < 0.005kg, mesma lógica do sistema atual.
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

@Injectable({ providedIn: 'root' })
export class LocalScaleService {
  private socket: WebSocket | null = null;
  private status: StatusBalanca = 'desconectado';
  private pesoAnterior: number | null = null;
  private timerEstabilidade: ReturnType<typeof setTimeout> | null = null;
  private taraValor = 0;

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
        this.portas$.next(msg.portas ?? []);
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
    this.enviar({ tipo: 'subscribe', portaCom });
  }

  unsubscribe(portaCom: string): void {
    this.enviar({ tipo: 'unsubscribe', portaCom });
  }

  listarPortas(): void {
    this.enviar({ tipo: 'listar-portas' });
  }

  /** Zera o display: peso bruto atual vira a referência de tara. */
  tarar(pesoBruto: number): void {
    this.taraValor = pesoBruto;
  }

  private processarLeitura(leitura: LeituraPeso): void {
    const pesoLiquido = Math.max(0, leitura.peso - this.taraValor);
    // Reset automático de tara: se o peso bruto cair bem abaixo da tara
    // (objeto retirado), zera sozinho pra não travar o display em 0.
    if (leitura.peso < this.taraValor - 0.1) {
      this.taraValor = 0;
    }

    const leituraAjustada: LeituraPeso = { ...leitura, peso: pesoLiquido };
    this.peso$.next(leituraAjustada);

    const variou = this.pesoAnterior === null || Math.abs(pesoLiquido - this.pesoAnterior) >= LIMIAR_ESTABILIDADE_KG;
    this.pesoAnterior = pesoLiquido;

    if (variou) {
      if (this.timerEstabilidade) clearTimeout(this.timerEstabilidade);
      this.timerEstabilidade = setTimeout(() => this.pesoEstavel$.next(leituraAjustada), TEMPO_ESTABILIDADE_MS);
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
