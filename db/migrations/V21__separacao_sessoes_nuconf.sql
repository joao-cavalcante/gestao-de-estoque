-- ============================================================================
-- V21 — guarda o NUCONF (número da conferência no Sankhya) na sessão local.
--
-- Necessário pra chamar rotinas nativas de conferência que exigem esse
-- número (ex.: ConferenciaSP.salvarVolumeSimplificado,
-- ConferenciaSP.salvarItemConferido) sem precisar reconsultar o Sankhya
-- toda vez — confirmado ao vivo nesta sessão que hoje esse número é lido
-- (TarefaSyncService.buscarStatusConferenciaAtiva) e descartado, nunca
-- persistido.
-- ============================================================================

alter table app.separacao_sessoes add column nuconf integer;
