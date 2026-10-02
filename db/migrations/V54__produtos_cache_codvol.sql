-- ============================================================================
-- V54 — unidade padrão (TGFPRO.CODVOL) no espelho local de produtos.
--
-- A Consulta de Produtos lista o catálogo inteiro com o saldo da instância
-- Estoque, que é sempre na unidade padrão — sem ela no espelho, produto sem
-- linha de estoque ficava sem unidade. Linhas antigas ficam null até o
-- ProdutoCatalogoSyncService regravar (ele trata codvol null como alterado).
-- ============================================================================

alter table app.produtos_cache add column codvol text;
