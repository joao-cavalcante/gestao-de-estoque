-- ============================================================================
-- V52 — registro de impressão do Mapa de Separação (por Ordem de Carga ou por
-- Número Único no mapa S/ OC). Alimenta o selo "IMPRESSO" e o filtro
-- Impressos / Não impressos da tela. Uma linha por impressão (histórico).
-- ============================================================================

create table app.mapa_impressoes (
  id            uuid primary key default gen_random_uuid(),
  tenant_id     uuid not null references tenancy.tenants(id) on delete cascade,
  ordem_carga   bigint,
  nunota        bigint,
  impresso_por  text,
  impresso_em   timestamptz not null default now(),
  check (ordem_carga is not null or nunota is not null)
);

create index idx_mapa_impressoes_oc on app.mapa_impressoes (tenant_id, ordem_carga);
create index idx_mapa_impressoes_nunota on app.mapa_impressoes (tenant_id, nunota);

alter table app.mapa_impressoes enable row level security;
alter table app.mapa_impressoes force row level security;

create policy tenant_isolation on app.mapa_impressoes
  using       (tenant_id = tenancy.current_tenant_id())
  with check  (tenant_id = tenancy.current_tenant_id());

grant select, insert, update, delete on app.mapa_impressoes to wms_app;
