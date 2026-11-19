-- ============================================================================
-- V76 — snapshot na sessão de separação do CCO.EXIBIRALERTASONORO
-- ("Emitir alerta sonoro em mensagens de divergência"), mesmo padrão do V28:
-- resolvido na abertura da sessão a partir do mirror local
-- (app.config_conferencia) e congelado aqui.
--
-- No Sankhya o servidor só repassa a flag para a tela
-- (ConferenciaSPBean.setConfiguracaoConferencia → atributo
-- "mostrarAlertaSonoroDivergencia"); quem toca o som é o cliente.
--
-- Semântica no WMS (fail-safe = com som, como já era): só 'N' explícito
-- silencia os sons de DIVERGÊNCIA (quantidade/peso divergente, produto
-- incorreto ou não encontrado, finalização divergente). Sons de leitura,
-- de item conferido e de erro de sistema continuam. A vibração do coletor
-- não depende desta flag.
-- ============================================================================

alter table app.separacao_sessoes add column exibir_alerta_sonoro text;
