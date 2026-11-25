-- ============================================================================
-- V78 — Ajuste de peso da COMPRA por Configuração de Conferência (NUCCO).
--
-- Até aqui o ajuste (lançar nota de ajuste dentro da tolerância × enviar para liberação) era um parâmetro do cliente
-- inteiro (tenancy parametros.ajustePesoCompra). Decisão do usuário em 25/11/2026: passa a ser por configuração de
-- conferência, como a tolerância ("caso eu não queira usar em uma configuração específica").
--
-- NULL = herda o valor do cliente (parametros.ajustePesoCompra.habilitado / ajusteEntrada / ajusteSaida): nenhuma
-- conferência muda de comportamento com esta migração. A tela grava o valor explícito ao salvar.
-- TOP, série, parceiro e tipo de negociação das notas de ajuste continuam no Sankhya (TORCFG do addon, por empresa).
-- ============================================================================

alter table app.conferencia_opcoes
  add column ajuste_peso_habilitado boolean,
  add column ajuste_peso_entrada    boolean,
  add column ajuste_peso_saida      boolean;
