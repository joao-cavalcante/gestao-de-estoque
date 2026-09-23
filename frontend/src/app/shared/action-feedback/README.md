# action-feedback — biblioteca central de feedback de ação

Um único ponto para **som + visual + mensagem** das ações do operador.
Telas só dizem *o que aconteceu*; o que isso significa (som, cor, toast,
duração, prioridade, se bloqueia) mora em `action-feedback.config.ts`.

```ts
private readonly feedback = inject(ActionFeedbackService);

this.feedback.trigger('ITEM_CONFERIDO');
this.feedback.trigger('ERRO_SANKHYA', { mensagem: err?.error?.erro });
this.feedback.trigger('ERRO_SANKHYA', { toast: false }); // a tela já mostra a mensagem
this.feedback.trigger('PESAGEM_OK', { som: false });      // silêncio pontual
```

| Arquivo | Papel |
|---|---|
| `action-feedback.types.ts` | Lista de eventos, ids de som, prioridades, tipos |
| `action-feedback.config.ts` | Mapa evento → som / tom visual / toast / bloqueante |
| `action-feedback.audio.ts` | Motor de áudio: 1 AudioContext, preload, concorrência |
| `action-feedback.service.ts` | `trigger()`, anti-rajada, pilha de toasts, `ultimo` |
| `oq-action-feedback-host.component.ts` | Toasts globais (montado 1x no `app.component`) |
| `oq-feedback-flash.directive.ts` | `[oqFeedbackFlash]`: pisca o elemento com o tom do evento |

## Novo evento

1. Adicione o nome em `FeedbackEvento` (`action-feedback.types.ts`).
2. Adicione a linha em `FEEDBACK_CONFIG` (o TypeScript obriga).
3. Chame `feedback.trigger('NOVO_EVENTO')` na tela.

Som novo: acrescente em `SONS` no gerador (`frontend/tools/sons/gerar-sons.py`),
rode `python tools/sons/gerar-sons.py` e inclua o id em `SomId`.

## Regras de comportamento

- **Prioridade** (`DISCRETO < CONFIRMACAO < ALERTA < ERRO`): um som só
  interrompe outro de prioridade menor ou igual. Alerta tocando não é
  atropelado por um "ok" logo em seguida; entre iguais, o mais recente vence
  (bipagem rápida não acumula fila de beeps).
- **Anti-rajada**: o mesmo evento em menos de 90 ms dispara uma vez só
  (leitor duplicando Enter, duplo clique).
- **Bloqueante**: toast fica até o operador fechar. Decisões de negócio
  (divergência, corte, faturamento) continuam nos modais das telas: o evento
  só dá o som e o visual.
- **Corte silencioso**: `CORTE_AUTOMATICO` é mudo por configuração. A
  auto-liberação de pesável dentro da tolerância acontece no backend e chega à
  tela como finalização normal: nenhum alerta extra.
- Sem áudio disponível (navegador bloqueou, arquivo faltando) tudo continua
  funcionando, só sem som.

## Mapa de sons

| Evento(s) | Arquivo | Descrição |
|---|---|---|
| ITEM_LIDO | `item-lido` | tique agudo 45 ms (1760 Hz), volume 60% |
| ITEM_CONFERIDO, QUANTIDADE_OK | `item-conferido` | 2 beeps ascendentes 900 → 1200 Hz (o "ok" de antes) |
| CONTROLE_NECESSARIO, CORTE_NEGADO | `atencao` | beep 700 Hz (o "atenção" de antes) |
| PESAGEM_OK | `pesagem-ok` | confirmação curta 1320 Hz |
| PRODUTO_INCORRETO | `produto-incorreto` | 2 beeps rápidos 620 Hz |
| PRODUTO_NAO_ENCONTRADO, ERRO | `erro` | descendente 300 → 260 Hz (o "erro" de antes) |
| DIVERGENCIA, PESO_DIVERGENTE, QUANTIDADE_DIVERGENTE, FINALIZACAO_DIVERGENTE | `divergencia` | 2 tons alternados 880/660 Hz |
| CORTE_LIBERADO | `corte-liberado` | 1000 → 1500 Hz, marcante |
| FINALIZACAO, TODOS_CONFERIDOS | `finalizacao` | 800 → 1000 → 1300 Hz (o "finalizado" de antes) |
| ENVIANDO_SANKHYA | `enviando-sankhya` | pulso senoidal duplo, volume 35% |
| SUCESSO_SANKHYA, ETAPA_CONCLUIDA | `sucesso-sankhya` | 1047 → 1319 Hz |
| ERRO_SANKHYA | `erro-sankhya` | 600 → 450 → 300 Hz |
| BLOQUEADO, OPERACAO_NAO_PERMITIDA | `bloqueado` | buzzer 190 Hz, 160 ms |
| CORTE_AUTOMATICO, PESAGEM_INICIADA, PRODUTO_ENCONTRADO, PRODUTO_CORRETO, PROCESSANDO, CARREGANDO | (mudo) | só visual ou ganchos para o futuro |

## Origem e licença dos sons

Todos os arquivos de `src/assets/sounds/` são **sintetizados pelo próprio
projeto** (`frontend/tools/sons/gerar-sons.py`): onda quadrada com passa-baixa
de 2200 Hz, a mesma família sonora do feedback anterior. Não há áudio de
terceiros, portanto não há licença externa a cumprir. O script normaliza todos
os arquivos no mesmo nível (RMS 0,5, pico ≤ −0,3 dBFS). A diferença de volume
entre eventos fica só no campo `volume` da config.

Para trocar um som por um arquivo de biblioteca (Mixkit, Pixabay, Freesound),
substitua o `.wav` com o mesmo nome, normalize o nível e registre a origem e a
licença nesta seção.
