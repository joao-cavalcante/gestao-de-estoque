-- ============================================================================
-- V27 — paridade com a conferência antiga: unidades alternativas (TGFVOA) +
-- pesagem.
--
-- Unidades alternativas: o Sankhya guarda TGFITE.QTDNEG SEMPRE na unidade
-- PADRÃO. A conversão DIVIDEMULTIPLICA × QUANTIDADE (TGFVOA) é só DISPLAY
-- ("Pedido: 10 CX" ao lado da qtd padrão). O match do VOA é por LINHA
-- (TGFITE.CODVOL = TGFVOA.CODVOL, + fallback lote-livre), nunca por produto.
-- Snapshot na abertura da sessão, congelado (mesmo padrão dos campos de CCO).
--
-- separacao_leituras.codvol (já existe, nullable) passa a ser gravado com a
-- unidade escanada; codigo_barra guarda a string bipada de fato — os dois vão
-- pro Sankhya no CODVOL/CODBARRA do item conferido.
-- ============================================================================

alter table app.separacao_itens
  add column unidade_comercial text,
  add column unidade_padrao    text,
  add column divide_multiplica text,
  add column fator_conversao   numeric(15,5);

alter table app.separacao_leituras
  add column codigo_barra text;
