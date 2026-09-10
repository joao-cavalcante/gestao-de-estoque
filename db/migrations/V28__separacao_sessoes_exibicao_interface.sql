-- ============================================================================
-- V28 — snapshot na sessão de separação das flags de "Comportamento da
-- interface" da CCO (Configuração de Conferência), mesmo padrão de qtdamaior
-- (V22) / obter_qtd_balanca (V23) / fat_ao_concluir (V26): resolvido na
-- abertura da sessão a partir do mirror local (app.config_conferencia) e
-- congelado aqui.
--
-- No fila-de-conferencia legado só EXIBIRPROD e EXIBIRIMGPROD chegavam a
-- gatear a tela (carregarDadosGerais). Aqui portamos o conjunto que mapeia
-- pra UI que o WMS tem:
--
--   exibir_prod       EXIBIRPROD      — painel de pendentes
--   exibir_qtd        EXIBIRQTD       — qtd negociada na lista de pendentes
--   exibir_prod_conf  EXIBIRPRODCONF  — painel de conferidos
--   exibir_qtd_conf   EXIBIRQTDCONF   — qtd conferida (lista de conferidos + última leitura)
--   exibir_img_prod   EXIBIRIMGPROD   — painel de imagem / última leitura
--
-- Semântica (fail-safe = mostrar): NULL / ausente / 'S' / qualquer valor
-- diferente de 'N' = mostra; só 'N' explícito esconde. Difere do legado, que
-- para a imagem usava `=== 'S'` (default esconder) por insegurança de
-- dicionário — o WMS espelha o campo pra todos os tenants via
-- ConfigConferenciaSyncService, então o default seguro aqui é mostrar.
-- ============================================================================

alter table app.separacao_sessoes add column exibir_prod      text;
alter table app.separacao_sessoes add column exibir_qtd       text;
alter table app.separacao_sessoes add column exibir_prod_conf text;
alter table app.separacao_sessoes add column exibir_qtd_conf  text;
alter table app.separacao_sessoes add column exibir_img_prod  text;
