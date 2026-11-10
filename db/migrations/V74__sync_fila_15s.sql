-- ============================================================================
-- V74 — ciclo do sync da fila (Sankhya -> Torre) de 60 s para 15 s.
--
-- 10/11/2026, reclamação de lentidão nos syncs: a mudança no Sankhya levava até
-- ~2 min pra aparecer (60 s do worker + 60 s do contador da tela). O ciclo em si
-- custa ~0,5 s de Sankhya; o contador da tela (sync-tick.service.ts) também
-- passou pra 15 s. Só mexe em quem está no padrão antigo (60) — intervalo
-- ajustado à mão fica como está.
-- ============================================================================

update tenancy.sync_estado set intervalo_segundos = 15 where intervalo_segundos = 60;
