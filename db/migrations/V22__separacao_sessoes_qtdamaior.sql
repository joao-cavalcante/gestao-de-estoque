-- ============================================================================
-- V22 — guarda QTDAMAIOR (CCO do NUCCO) na sessão local, mesmo padrão de
-- buscar_codigo_barra_por (V7). 'D' = permite bipar quantidade maior que a
-- negociada (fica divergente); qualquer outro valor = bloqueia o excesso na
-- hora da bipagem — comportamento confirmado na documentação oficial do
-- Sankhya (aba Corte/Divergência, campo "Quantidade a maior").
-- ============================================================================

alter table app.separacao_sessoes add column qtdamaior text;
