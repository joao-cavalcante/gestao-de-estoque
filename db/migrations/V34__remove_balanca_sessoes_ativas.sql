-- ============================================================================
-- V34 — remove app.balanca_sessoes_ativas (V32), órfã depois do redesenho
-- do login por crachá (V33): o crachá não troca mais sessão/balança, virou
-- um gate na entrada da tela de conferência (app.separacao_sessoes.operador_id).
-- Nada no código referencia esta tabela mais.
-- ============================================================================

drop table if exists app.balanca_sessoes_ativas;
