-- ============================================================================
-- V47 — turno do usuário (MANHA / NOITE), exibido no header global (era
-- placeholder fixo "CD-SP-03 / TURNO A" desde sempre — TODO antigo em
-- SessaoContextoService, nunca ligado a dado real).
--
-- Nullable de propósito: nem todo perfil tem turno fixo (ex.: ADMINISTRADOR).
-- Sem constraint de domínio no banco (mesmo padrão de app.users.perfil, que
-- também é text livre) — validado na camada de aplicação.
-- ============================================================================

alter table app.users add column turno text;
