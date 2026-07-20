-- ============================================================================
-- V9 — resolução de código de barras de verdade (5 modos reais do Sankhya,
-- portados de SessaoService.resolverCodigoBarras do projeto base):
-- A (Automático), C (Código do produto), R (Referência), U (Unidade
-- alternativa), E (Estoque). Antes só existia um casamento simplificado.
--
-- Precisa de 2 colunas que faltavam:
-- - separacao_codigos_barra.quantidade/divide_multiplica: fator de
--   conversão de unidade (só existe pra origem VOA) — sem isso não dá pra
--   resolver o modo "U" nem o fallback de unidade alternativa do "A".
-- - separacao_sessoes.buscar_codigo_barra_por: snapshot da regra no
--   momento em que a sessão foi carregada — resolve código de barras não
--   deveria depender de reconsultar o cache de config a cada bipe.
-- ============================================================================

alter table app.separacao_codigos_barra
  add column quantidade       numeric(15,5),
  add column divide_multiplica text;

alter table app.separacao_sessoes
  add column buscar_codigo_barra_por text not null default 'A';
