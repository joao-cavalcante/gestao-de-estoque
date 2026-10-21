-- ============================================================================
-- V60 — Impressão da OC com nota pendente na SEFAZ.
--
-- Passada a carência, a OC sai com o romaneio + as notas autorizadas e cada nota que faltou vira um job
-- próprio (motivo 'nota_pendente') que só imprime aquele DANFE quando a SEFAZ autorizar.
--   nunotas: NUNOTAs (separados por vírgula) que o job imprime; null = OC inteira com romaneio.
-- ============================================================================

alter table app.impressao_oc add column nunotas text;
