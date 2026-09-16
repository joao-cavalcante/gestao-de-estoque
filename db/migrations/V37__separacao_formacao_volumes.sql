-- ============================================================================
-- V37 — snapshot de CCO.FORMACAOVOLUMES na sessão, mesmo padrão dos outros
-- campos de CCO congelados na abertura (V26/V28).
--
-- Até aqui esse campo era lido e sincronizado (ConfigConferenciaSyncService)
-- mas nunca consumido em nenhuma lógica de negócio — o sistema deixava
-- finalizar a conferência mesmo com o contador de volumes zerado, mesmo em
-- CCO configurada pra exigir ('S'/'T'/'D'). Passa a gatear o botão de
-- confirmar/concluir etapa (frontend) e o endpoint de finalizar (backend).
-- ============================================================================

alter table app.separacao_sessoes
  add column formacao_volumes text;
