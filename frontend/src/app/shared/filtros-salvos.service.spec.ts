import { signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { AuthService } from '../auth/auth.service';
import { FiltrosSalvosService } from './filtros-salvos.service';

describe('FiltrosSalvosService', () => {
  const usuario = signal<{ id: string } | null>({ id: 'u1' });
  let service: FiltrosSalvosService;

  beforeEach(() => {
    localStorage.clear();
    usuario.set({ id: 'u1' });
    TestBed.configureTestingModule({
      providers: [{ provide: AuthService, useValue: { usuario, obterTenantSlug: () => 'negri' } }],
    });
    service = TestBed.inject(FiltrosSalvosService);
  });

  afterEach(() => localStorage.clear());

  it('guarda e devolve os filtros da tela', () => {
    service.salvar('fila-tarefas', { status: 'aguardando', itensPorPagina: 50 });
    expect(service.ler('fila-tarefas')).toEqual({ status: 'aguardando', itensPorPagina: 50 });
  });

  it('cada usuário tem os seus (estação compartilhada)', () => {
    service.salvar('fila-tarefas', { status: 'aguardando' });
    usuario.set({ id: 'u2' });
    expect(service.ler('fila-tarefas')).toBeNull();
    usuario.set({ id: 'u1' });
    expect(service.ler('fila-tarefas')).toEqual({ status: 'aguardando' });
  });

  it('telas diferentes não se misturam', () => {
    service.salvar('fila-tarefas', { status: 'aguardando' });
    expect(service.ler('mapa-separacao')).toBeNull();
  });

  it('sem usuário logado não lê nem grava', () => {
    usuario.set(null);
    service.salvar('fila-tarefas', { status: 'aguardando' });
    expect(service.ler('fila-tarefas')).toBeNull();
    expect(localStorage.length).toBe(0);
  });

  it('valor corrompido no storage não quebra a tela', () => {
    localStorage.setItem('wms_filtros:negri:u1:fila-tarefas', '{nao-e-json');
    expect(service.ler('fila-tarefas')).toBeNull();
  });
});
