export type TipoComunicacao = 'SERIAL_RS232' | 'SERIAL_USB' | 'HTTP' | 'TOLEDO_TCP';

export interface Balanca {
  id: string;
  nome: string;
  fabricante: string | null;
  tipoComunicacao: TipoComunicacao;
  portaCom: string | null;
  baudRate: number | null;
  dataBits: number | null;
  paridade: string | null;
  stopBits: number | null;
  protocoloSerial: string | null;
  ip: string | null;
  porta: number | null;
  rota: string | null;
  ativo: boolean;
}

export type SalvarBalancaRequest = Omit<Balanca, 'id'>;
