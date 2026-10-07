-- ============================================================================
-- V57 — TOPs de destino do faturamento por TOP de origem.
--
-- Espelho das RESTRIÇÕES de destino do Sankhya (instância RestricaoTop, TGFREP com TIPREST 'D' e
-- RESTRICAO 'S': CODTIPOPER = TOP de origem, CODCOLREST = TOP de destino, SERIE opcional). Vem junto
-- com a sincronização de Tipos de Operação e alimenta a lista de TOPs do faturamento após a conferência.
--
-- Por codtop (sem FK pra app.tipos_operacao): o espelho é regravado a cada sincronização.
-- ============================================================================

create table app.tipo_operacao_destinos (
  tenant_id           uuid not null references tenancy.tenants(id) on delete cascade,
  codtop              integer not null,
  codtop_destino      integer not null,
  descricao_destino   text not null,
  serie               text,
  local_atualizado_em timestamptz not null default now(),
  primary key (tenant_id, codtop, codtop_destino)
);

alter table app.tipo_operacao_destinos enable row level security;
alter table app.tipo_operacao_destinos force row level security;

create policy tenant_isolation on app.tipo_operacao_destinos
  using       (tenant_id = tenancy.current_tenant_id())
  with check  (tenant_id = tenancy.current_tenant_id());

grant select, insert, update, delete on app.tipo_operacao_destinos to wms_app;
