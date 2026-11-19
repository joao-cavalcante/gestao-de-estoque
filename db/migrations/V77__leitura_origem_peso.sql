-- ============================================================================
-- V77 — origem do peso de cada pesagem: BALANCA (capturado da balança — HTTP,
-- serial/TCP ao vivo ou captura automática) ou MANUAL (digitado no pop-up).
--
-- Pedido do usuário (19/11/2026): a Auditoria de Pedidos mostra a hora de cada
-- conferência/pesagem do item e se o peso veio da balança ou foi digitado.
-- A tela de conferência já sabia (oq-scan-bar `pesoDaBalanca`), só não gravava.
--
-- Nula = leitura sem peso (não pesável) ou pesagem anterior a esta migração
-- (a auditoria mostra "origem não registrada").
-- ============================================================================

alter table app.separacao_leituras
  add column if not exists origem_peso text
    constraint ck_separacao_leituras_origem_peso check (origem_peso in ('BALANCA', 'MANUAL'));
