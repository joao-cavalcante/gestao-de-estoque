-- ============================================================================
-- V58 — "Aguardando nota": pedido conferido cuja CCO pede faturamento
-- (fat_ao_concluir = 'S') fica marcado até a nota sair FATURADA E CONFIRMADA.
--
--   nota_status = 'aguardando' -> aparece na tela Aguardando Nota e bloqueia o "✓ Carregado"
--               = 'ok'         -> nota gerada e confirmada (TGFVAR + TGFCAB.STATUSNOTA = 'L')
--               = NULL         -> não se aplica (CCO sem faturamento, ou conferência reaberta)
--   nota_erro   = último motivo de recusa do Sankhya (faturar ou confirmarNota), pra tela.
--
-- Quem decide de verdade é o Sankhya: a lista revalida cada pedido (nota faturada por fora
-- sai da lista sozinha). Backfill: sessões concluídas nos últimos 3 dias com faturamento na
-- CCO entram como 'aguardando' — a primeira revalidação tira as que já foram faturadas.
-- ============================================================================

alter table app.separacao_sessoes add column nota_status text;
alter table app.separacao_sessoes add column nota_erro text;
alter table app.separacao_sessoes add column nota_atualizado_em timestamptz;

create index separacao_sessoes_nota_aguardando_idx
  on app.separacao_sessoes (tenant_id, nunota)
  where nota_status = 'aguardando';

update app.separacao_sessoes
   set nota_status = 'aguardando', nota_atualizado_em = now()
 where status = 'concluida'
   and fat_ao_concluir = 'S'
   and atualizado_em >= now() - interval '3 days';
