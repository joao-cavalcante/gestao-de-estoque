-- ============================================================================
-- V6 — campo que faltou no V5: balança tipo HTTP precisa do path da rota
-- (ex: "/peso") além de ip/porta — o driver faz GET http://{ip}:{porta}{rota}
-- e extrai o peso da resposta (número puro, string numérica, ou JSON com
-- campo peso/value/weight/data). Único tipo lido no SERVIDOR — os demais
-- (serial) são 100% client-side via o agente local.
-- ============================================================================

alter table app.balancas
  add column rota text;
