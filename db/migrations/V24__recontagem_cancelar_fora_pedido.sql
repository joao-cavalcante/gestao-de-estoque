-- ============================================================================
-- V24 — mais 4 pontos do fluxo de conferência portados do projeto base:
--
-- - Recontagem: reaproveita ConferenciaSP.salvarCabecalhoConferencia com
--   iniciarRecontagem=true (contrato já confirmado nesta sessão, só não
--   tínhamos usado esse parâmetro ainda).
-- - Cancelar sessão: desiste a conferência inteira (não só devolve 1 item).
-- - Produto fora do pedido: PRODUTOSFORAPED da CCO — permite bipar produto
--   que não estava na nota original (catálogo local, não os itens da nota).
-- - Modos de código de barras isolados (C/R/U/E) já usam o mesmo campo
--   buscar_codigo_barra_por que já existe — sem migration nova pra isso.
-- ============================================================================

alter table app.separacao_sessoes add column produtos_fora_ped text;
alter table app.separacao_itens add column fora_pedido boolean not null default false;
