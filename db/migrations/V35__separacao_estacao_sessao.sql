-- ============================================================================
-- V35 — "em qual estação (login do PC) o crachá foi bipado".
--
-- Fluxo real: PCs de conferência logam com uma conta fixa por estação
-- (ex.: usuário "Stage1", "Stage2" — login normal, email/senha). Ao abrir
-- uma conferência, o operador de verdade bipa o crachá (V33,
-- operador_id) — mas pra filtrar depois "quem bipou no Stage1 vs Stage2"
-- também precisa registrar QUAL conta estava logada no navegador na hora
-- da bipagem. estacao_id é exatamente isso: claims.userId de quem chamou
-- POST /identificar-operador (a conta da estação), nunca a mesma pessoa
-- que operador_id quando é um PC de estação fixa.
-- ============================================================================

alter table app.separacao_sessoes
  add column estacao_id uuid references app.users(id);
