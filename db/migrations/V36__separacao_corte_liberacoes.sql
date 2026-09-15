-- ============================================================================
-- V36 — registra localmente a decisão (liberado/negado) de cada item numa
-- rodada de liberação de corte/divergência.
--
-- Necessário porque nenhum campo do TGFITE reflete essa decisão: QTDENTREGUE
-- fica 0 tanto pro item liberado quanto pro negado (confirmado ao vivo,
-- nota 57394 — queijo liberado no corte silencioso, QTDENTREGUE seguiu 0), e
-- PENDENTE também continua 'S' nos dois casos até a nota fechar de vez. Sem
-- isso, a tela de Conferência não tem como saber, ao montar a lista de itens
-- de uma recontagem, que um item já foi aceito e não precisa voltar — ele
-- reaparece do mesmo jeito que o item que foi negado de verdade.
--
-- Uma linha por (tenant, nunota, codprod) — upsert, guarda só a decisão MAIS
-- RECENTE (uma nota pode passar por várias rodadas de corte/recontagem;
-- decisão antiga não importa mais).
-- ============================================================================

create table app.separacao_corte_liberacoes (
  id            uuid primary key,
  tenant_id     uuid not null references tenancy.tenants(id) on delete cascade,
  nunota        integer not null,
  codprod       integer not null,
  liberado      boolean not null,
  nuconf        integer not null,
  decidido_em   timestamptz not null,
  unique (tenant_id, nunota, codprod)
);

create index idx_separacao_corte_liberacoes_nunota on app.separacao_corte_liberacoes (tenant_id, nunota);

alter table app.separacao_corte_liberacoes enable row level security;
alter table app.separacao_corte_liberacoes force row level security;

create policy tenant_isolation on app.separacao_corte_liberacoes
  using       (tenant_id = tenancy.current_tenant_id())
  with check  (tenant_id = tenancy.current_tenant_id());

grant select, insert, update, delete on app.separacao_corte_liberacoes to wms_app;
