-- ============================================================================
-- V19 — Espelho local de Produto (TGFPRO) e Código de Barras (TGFBAR), mais
-- cache sob demanda de Volume Alternativo (TGFVOA).
--
-- TGFPRO tem DTALTER e TGFBAR tem DHALTER — permitem sync incremental de
-- verdade (mesma técnica da V16/V17 pra Tipo de Operação): campo de
-- auditoria guardado como TEXT cru (sem parse de data, mesmo motivo já
-- documentado na V16), comparação de string já resolve "mudou ou não mudou".
--
-- TGFVOA não tem campo de auditoria — não existe sync periódico pra ela
-- (full refresh sem saber o que mudou pesaria à toa). app.volumes_alternativos_cache
-- é só um cache "pra sempre, sem TTL", populado sob demanda pela primeira
-- consulta que precisar daquele produto (mesmo espírito de
-- app.produto_imagem_cache).
--
-- Mesma receita de RLS do V12/V14/V15/V16.
-- ============================================================================

create table app.produtos_cache (
  id                     uuid primary key default gen_random_uuid(),
  tenant_id              uuid not null references tenancy.tenants(id) on delete cascade,

  codprod                integer not null,
  descrprod              text not null,
  compldesc              text,
  marca                  text,
  referencia             text,
  tipcontest             text,
  liscontest             text,

  dtalter_sankhya        text,
  local_atualizado_em    timestamptz not null default now(),

  unique (tenant_id, codprod)
);

create index idx_produtos_cache_tenant on app.produtos_cache (tenant_id);

alter table app.produtos_cache enable row level security;
alter table app.produtos_cache force row level security;

create policy tenant_isolation on app.produtos_cache
  using       (tenant_id = tenancy.current_tenant_id())
  with check  (tenant_id = tenancy.current_tenant_id());

grant select, insert, update, delete on app.produtos_cache to wms_app;


create table app.codigos_barra_cache (
  id                     uuid primary key default gen_random_uuid(),
  tenant_id              uuid not null references tenancy.tenants(id) on delete cascade,

  codprod                integer not null,
  codvol                 text,
  codbarra               text not null,

  dhalter_sankhya        text,
  local_atualizado_em    timestamptz not null default now(),

  unique (tenant_id, codprod, codvol, codbarra)
);

create index idx_codigos_barra_cache_tenant on app.codigos_barra_cache (tenant_id);
create index idx_codigos_barra_cache_codprod on app.codigos_barra_cache (tenant_id, codprod);

alter table app.codigos_barra_cache enable row level security;
alter table app.codigos_barra_cache force row level security;

create policy tenant_isolation on app.codigos_barra_cache
  using       (tenant_id = tenancy.current_tenant_id())
  with check  (tenant_id = tenancy.current_tenant_id());

grant select, insert, update, delete on app.codigos_barra_cache to wms_app;


create table app.volumes_alternativos_cache (
  id                     uuid primary key default gen_random_uuid(),
  tenant_id              uuid not null references tenancy.tenants(id) on delete cascade,

  codprod                integer not null,
  codvol                 text,
  controle               text,
  divide_multiplica      text,
  quantidade             text,
  codbarra               text,

  local_atualizado_em    timestamptz not null default now(),

  unique (tenant_id, codprod, codvol, codbarra)
);

create index idx_volumes_alternativos_cache_tenant on app.volumes_alternativos_cache (tenant_id);
create index idx_volumes_alternativos_cache_codprod on app.volumes_alternativos_cache (tenant_id, codprod);

alter table app.volumes_alternativos_cache enable row level security;
alter table app.volumes_alternativos_cache force row level security;

create policy tenant_isolation on app.volumes_alternativos_cache
  using       (tenant_id = tenancy.current_tenant_id())
  with check  (tenant_id = tenancy.current_tenant_id());

grant select, insert, update, delete on app.volumes_alternativos_cache to wms_app;
