-- V86 (28/11/2026) — D3 opção A: a compra cujo item foi dividido por lote na nota pela Torre (addon
-- RecebimentoSP.dividirLotes). Com a marca, a finalização não confunde a própria divisão com "pedido alterado no Sankhya".
alter table app.recebimento_decisoes add column lotes_divididos_em timestamptz;
