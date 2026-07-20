import { Injectable } from '@angular/core';

export interface OperadorInfo {
  iniciais: string;
  nome: string;
  codigo: string;
  cargo: string;
}

export interface UnidadeInfo {
  iniciais: string;
  nome: string;
  versao: string;
  unidadeTurno: string;
}

/**
 * Dados de identidade (unidade + operador) exibidos no header global.
 * TODO: ainda não existe login/JWT amarrando o usuário a um tenant/unidade
 * real (Fase 0/1 do motor de tarefas) — fixo por enquanto, mesmo TODO já
 * usado no resto do app (ex.: FilaTarefasComponent.tenantAtual).
 */
@Injectable({ providedIn: 'root' })
export class SessaoContextoService {
  readonly unidade: UnidadeInfo = {
    iniciais: 'LG',
    nome: 'Logística Ágil',
    versao: 'HMI-OPS · v4.2.1',
    unidadeTurno: 'CD-SP-03 / TURNO A',
  };

  readonly operador: OperadorInfo = {
    iniciais: 'JC',
    nome: 'João Cavalcante',
    codigo: 'OP-2841',
    cargo: 'CONFERENTE SR',
  };
}
