export type ItemStatus = 'pending' | 'ok' | 'critical' | 'warning';

export interface ConferenciaItem {
  seq: number;
  code: string;
  name: string;
  control: string;
  expected: number;
  scanned: number;
  status: ItemStatus;
  divergenceReason?: string;
  preAlert?: string;
  imagemUrl?: string | null;
}

export type ChipTone = 'critical' | 'warning' | 'success' | 'neutral';
export type QtyTone = 'default' | 'muted' | 'critical';
