-- ============================================================================
-- V26 — guarda FATAOCONCLUIR (CCO do NUCCO) na sessão local, mesmo padrão de
-- qtdamaior (V22) / obter_qtd_balanca (V23): resolvido na abertura da sessão e
-- congelado aqui. 'S' = após finalizar a conferência, o operador pode escolher
-- uma TOP de destino e faturar a nota (SelecaoDocumentoSP.faturar) — ver o
-- fluxo de faturamento portado do fila-de-conferencia. Ausente/qualquer outro
-- valor = sem passo de faturamento.
-- ============================================================================

alter table app.separacao_sessoes add column fat_ao_concluir text;
