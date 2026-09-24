-- ============================================================================
-- V48 — etapa concluída COM divergência.
--
-- Na conferência por etapa (V29), o operador pode concluir uma etapa com item
-- pendente/divergente (confirma no pop-up de divergência — concluir-etapa com
-- manterPendente=true). O card da Fila de Tarefas precisa mostrar o chip dessa
-- etapa em VERMELHO em vez do verde de "concluída sem problema".
--
-- Default false: etapas já concluídas antes desta migração ficam como estavam
-- (não há como saber retroativamente se tiveram divergência).
-- ============================================================================

alter table app.separacao_etapas add column divergente boolean not null default false;
