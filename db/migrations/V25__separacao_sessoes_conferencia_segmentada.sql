-- ============================================================================
-- V25 — snapshot na sessão de separação do flag "conferência segmentada", um
-- MÓDULO por-tenant (tenancy.erp_connections.modulos) — não vem do Sankhya,
-- é conceito do WMS e só um cliente usa. Mesmo padrão de buscar_codigo_barra_por
-- (V7) / qtdamaior (V22) / obter_qtd_balanca (V23): resolvido na abertura da
-- sessão e congelado aqui, pra ligar/desligar o módulo não mudar a regra de
-- uma conferência já aberta.
--
-- default false = comportamento atual (conferência normal) pra todo tenant que
-- não tem o módulo habilitado.
-- ============================================================================

alter table app.separacao_sessoes
  add column conferencia_segmentada boolean not null default false;
