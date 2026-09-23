import { TestBed, fakeAsync, tick } from '@angular/core/testing';
import { ActionFeedbackAudio } from './action-feedback.audio';
import { FEEDBACK_CONFIG, JANELA_REPETICAO_MS, MAX_TOASTS } from './action-feedback.config';
import { ActionFeedbackService, ehFalhaComunicacao } from './action-feedback.service';
import { Prioridade } from './action-feedback.types';

describe('ActionFeedbackService', () => {
  let service: ActionFeedbackService;
  let audio: jasmine.SpyObj<ActionFeedbackAudio>;
  let agora: number;

  beforeEach(() => {
    audio = jasmine.createSpyObj<ActionFeedbackAudio>('ActionFeedbackAudio', ['preload', 'tocar']);
    TestBed.configureTestingModule({ providers: [{ provide: ActionFeedbackAudio, useValue: audio }] });
    agora = 1000;
    spyOn(performance, 'now').and.callFake(() => agora);
    service = TestBed.inject(ActionFeedbackService);
  });

  it('pré-carrega os sons ao ser criado', () => {
    expect(audio.preload).toHaveBeenCalledTimes(1);
  });

  it('leitura rápida e repetitiva: o mesmo evento em rajada toca uma vez só', () => {
    service.trigger('ITEM_LIDO');
    agora += 20;
    service.trigger('ITEM_LIDO'); // leitor duplicando Enter
    agora += 20;
    service.trigger('ITEM_LIDO');
    expect(audio.tocar).toHaveBeenCalledTimes(1);

    agora += JANELA_REPETICAO_MS + 1; // próximo bipe de verdade
    service.trigger('ITEM_LIDO');
    expect(audio.tocar).toHaveBeenCalledTimes(2);
  });

  it('eventos consecutivos diferentes disparam cada um com a prioridade da config', () => {
    service.trigger('ITEM_LIDO');
    service.trigger('ITEM_CONFERIDO');
    service.trigger('PESO_DIVERGENTE');
    expect(audio.tocar.calls.allArgs()).toEqual([
      ['item-lido', 0.6, Prioridade.DISCRETO],
      ['item-conferido', 1, Prioridade.CONFIRMACAO],
      ['divergencia', 1, Prioridade.ALERTA],
    ]);
  });

  it('corte silencioso (CORTE_AUTOMATICO) não emite som', () => {
    expect(FEEDBACK_CONFIG.CORTE_AUTOMATICO.som).toBeFalse();
    service.trigger('CORTE_AUTOMATICO');
    expect(audio.tocar).not.toHaveBeenCalled();
  });

  it('som: false silencia qualquer evento naquela chamada, mas mantém o visual', () => {
    service.trigger('CORTE_LIBERADO', { som: false });
    expect(audio.tocar).not.toHaveBeenCalled();
    expect(service.ultimo()?.evento).toBe('CORTE_LIBERADO');
    expect(service.ultimo()?.tom).toBe('success');
  });

  it('ultimo() muda a cada disparo (flash reage ao mesmo evento repetido)', () => {
    service.trigger('ITEM_CONFERIDO');
    const seq1 = service.ultimo()!.seq;
    agora += JANELA_REPETICAO_MS + 1;
    service.trigger('ITEM_CONFERIDO');
    expect(service.ultimo()!.seq).toBeGreaterThan(seq1);
  });

  it('erro de Sankhya: toast bloqueante com a mensagem, fica até ser fechado', fakeAsync(() => {
    service.trigger('ERRO_SANKHYA', { mensagem: 'Sankhya fora do ar' });
    expect(audio.tocar).toHaveBeenCalledWith('erro-sankhya', 1, Prioridade.ERRO);
    expect(service.toasts().length).toBe(1);
    expect(service.toasts()[0]).toEqual(jasmine.objectContaining({ titulo: 'Sankhya fora do ar', bloqueante: true, tom: 'critical' }));

    tick(60_000);
    expect(service.toasts().length).toBe(1);

    service.fecharToast(service.toasts()[0].id);
    expect(service.toasts().length).toBe(0);
  }));

  it('toast: false suprime o toast (a tela já mostra a mensagem) mas mantém o som', () => {
    service.trigger('ERRO_SANKHYA', { toast: false });
    expect(service.toasts().length).toBe(0);
    expect(audio.tocar).toHaveBeenCalledTimes(1);
  });

  it('erro comum some sozinho depois da duração configurada', fakeAsync(() => {
    service.trigger('ERRO', { mensagem: 'Falha ao gravar volumes' });
    expect(service.toasts().length).toBe(1);
    tick(FEEDBACK_CONFIG.ERRO.toast!.duracaoMs + 10);
    expect(service.toasts().length).toBe(0);
  }));

  it('mesma mensagem não empilha toast', fakeAsync(() => {
    service.trigger('ERRO', { mensagem: 'x' });
    agora += 500;
    service.trigger('ERRO', { mensagem: 'x' });
    expect(service.toasts().length).toBe(1);
    tick(10_000);
  }));

  it(`no máximo ${MAX_TOASTS} toasts — o mais antigo sai`, fakeAsync(() => {
    for (let i = 0; i < MAX_TOASTS + 2; i++) {
      agora += 500;
      service.trigger('ERRO', { mensagem: `erro ${i}` });
    }
    expect(service.toasts().map((t) => t.titulo)).toEqual(['erro 2', 'erro 3', 'erro 4']);
    tick(10_000);
    expect(service.toasts().length).toBe(0);
  }));

  it('eventos sem toast padrão não abrem toast (visual fica na própria tela)', () => {
    service.trigger('PRODUTO_NAO_ENCONTRADO');
    service.trigger('DIVERGENCIA');
    service.trigger('FINALIZACAO');
    expect(service.toasts().length).toBe(0);
  });
});

describe('ehFalhaComunicacao', () => {
  it('rede/timeout/5xx = falha de comunicação; 4xx = recusa de regra', () => {
    expect(ehFalhaComunicacao({ status: 0 })).toBeTrue();
    expect(ehFalhaComunicacao({ status: 502 })).toBeTrue();
    expect(ehFalhaComunicacao(null)).toBeTrue();
    expect(ehFalhaComunicacao({ status: 404 })).toBeFalse();
    expect(ehFalhaComunicacao({ status: 409 })).toBeFalse();
  });
});

describe('ActionFeedbackAudio — concorrência', () => {
  class FonteFalsa {
    buffer: unknown = null;
    onended: (() => void) | null = null;
    connect = jasmine.createSpy('connect').and.callFake((n: unknown) => n);
    disconnect = jasmine.createSpy('disconnect');
    start = jasmine.createSpy('start');
    stop = jasmine.createSpy('stop');
  }
  let fontes: FonteFalsa[];
  let tempo: number;
  let audio: ActionFeedbackAudio;

  beforeEach(() => {
    fontes = [];
    tempo = 0;
    const ctx = {
      state: 'running',
      get currentTime() {
        return tempo;
      },
      createBufferSource: () => {
        const f = new FonteFalsa();
        fontes.push(f);
        return f;
      },
      createGain: () => ({ gain: { value: 1 }, connect: (n: unknown) => n }),
      resume: () => Promise.resolve(),
    };
    audio = new ActionFeedbackAudio();
    const interno = audio as unknown as { ctx: unknown; mestre: unknown; buffers: Map<string, { duration: number }> };
    interno.ctx = ctx;
    interno.mestre = {};
    interno.buffers.set('divergencia', { duration: 0.4 });
    interno.buffers.set('item-conferido', { duration: 0.3 });
    interno.buffers.set('erro', { duration: 0.4 });
  });

  it('um "ok" logo depois de um alerta NÃO atropela o alerta', () => {
    audio.tocar('divergencia', 1, Prioridade.ALERTA);
    tempo = 0.1;
    audio.tocar('item-conferido', 1, Prioridade.CONFIRMACAO);
    expect(fontes.length).toBe(1);
    expect(fontes[0].stop).not.toHaveBeenCalled();
  });

  it('erro interrompe o que estiver tocando (prioridade maior)', () => {
    audio.tocar('item-conferido', 1, Prioridade.CONFIRMACAO);
    tempo = 0.05;
    audio.tocar('erro', 1, Prioridade.ERRO);
    expect(fontes.length).toBe(2);
    expect(fontes[0].stop).toHaveBeenCalled();
  });

  it('mesma prioridade: o mais recente vence (bipagem rápida não acumula fila)', () => {
    audio.tocar('item-conferido', 1, Prioridade.CONFIRMACAO);
    tempo = 0.1;
    audio.tocar('item-conferido', 1, Prioridade.CONFIRMACAO);
    expect(fontes.length).toBe(2);
    expect(fontes[0].stop).toHaveBeenCalled();
  });

  it('depois que o som acabou, qualquer prioridade toca', () => {
    audio.tocar('erro', 1, Prioridade.ERRO);
    tempo = 0.5;
    audio.tocar('item-conferido', 1, Prioridade.CONFIRMACAO);
    expect(fontes.length).toBe(2);
  });

  it('som não carregado (arquivo faltando) não quebra nada', () => {
    expect(() => audio.tocar('bloqueado', 1, Prioridade.ERRO)).not.toThrow();
    expect(fontes.length).toBe(0);
  });
});
