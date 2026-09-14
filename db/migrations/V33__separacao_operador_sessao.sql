-- ============================================================================
-- V33 — "quem está conferindo esta nota agora": bipagem de crachá na
-- ENTRADA da tela de conferência, separada do login do navegador.
--
-- Contexto (correção do modelo original de V32): o crachá não troca a
-- sessão/JWT do navegador — o login normal (email/senha) continua sendo o
-- único jeito de entrar no sistema. O que muda é que, ao ABRIR uma
-- conferência (app.separacao_sessoes), a tela agora bloqueia até alguém
-- bipar o crachá, e esse operador vira o dono daquela sessão específica —
-- útil quando o PC/navegador fica logado com uma conta genérica/supervisor
-- e o rodízio de quem realmente confere é maior que o de quem loga.
--
-- operador_id é o que separacao_etapas.concluida_por passa a refletir
-- (StoRoutes.concluir-etapa agora resolve o nome daqui, não mais de
-- claims.userId do JWT do navegador).
-- ============================================================================

alter table app.separacao_sessoes
  add column operador_id uuid references app.users(id);
