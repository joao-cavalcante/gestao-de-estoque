-- ============================================================================
-- V71 — Planta do Galpão (módulo planta_galpao, ver wms.backend.tenancy.Modulos).
--
-- Funções do galpão trazidas do Negri Planta Log 3D V1 pra dentro do WMS, no
-- banco do WMS (nada de tabela AD_ no Sankhya — o Sankhya continua só leitura).
--
-- 1. app.galpao_plantas            — a planta (galpão) com medidas externas.
-- 2. app.galpao_geometria_versoes  — desenho do editor (jsonb), versionado:
--    RASCUNHO -> PUBLICADA -> ARQUIVADA. Só uma PUBLICADA por planta.
-- 3. app.galpao_enderecos          — endereços PROJETADOS do desenho publicado
--    (gerados das estruturas). Endereço que sai do desenho fica ativo=false,
--    nunca apagado (saldo/histórico apontam pra ele).
-- 4. app.galpao_saldos_endereco    — saldo FÍSICO por endereço (distribuição).
--    Não é o saldo contábil (TGFEST): divergência é mostrada, não corrigida.
--    Começa vazio; preenchido pela movimentação (fase 4).
--
-- Mesma receita de RLS do V2/V7/V12.
-- ============================================================================

create table app.galpao_plantas (
  id                    uuid primary key default gen_random_uuid(),
  tenant_id             uuid not null references tenancy.tenants(id) on delete cascade,

  nome                  text not null,
  codemp                integer,
  codlocal              integer,
  largura               numeric(10, 2) not null,
  profundidade          numeric(10, 2) not null,
  versao_publicada_id   uuid,
  ativo                 boolean not null default true,

  criado_em             timestamptz not null default now(),
  atualizado_em         timestamptz not null default now(),

  unique (tenant_id, nome)
);

create index idx_galpao_plantas_tenant on app.galpao_plantas (tenant_id);

alter table app.galpao_plantas enable row level security;
alter table app.galpao_plantas force row level security;

create policy tenant_isolation on app.galpao_plantas
  using       (tenant_id = tenancy.current_tenant_id())
  with check  (tenant_id = tenancy.current_tenant_id());

grant select, insert, update, delete on app.galpao_plantas to wms_app;

-- ============================================================================

create table app.galpao_geometria_versoes (
  id                  uuid primary key default gen_random_uuid(),
  tenant_id           uuid not null references tenancy.tenants(id) on delete cascade,
  planta_id           uuid not null references app.galpao_plantas(id) on delete cascade,

  numero              integer not null,
  status              text not null check (status in ('RASCUNHO', 'PUBLICADA', 'ARQUIVADA')),
  geometria           jsonb not null,
  hash                text not null,
  observacao          text,
  criado_por_nome     text not null,
  criado_em           timestamptz not null default now(),
  publicado_por_nome  text,
  publicado_em        timestamptz,

  unique (planta_id, numero)
);

create index idx_galpao_versoes_tenant on app.galpao_geometria_versoes (tenant_id);
create index idx_galpao_versoes_planta on app.galpao_geometria_versoes (planta_id, numero desc);
create unique index uq_galpao_versao_publicada on app.galpao_geometria_versoes (planta_id) where status = 'PUBLICADA';

alter table app.galpao_geometria_versoes enable row level security;
alter table app.galpao_geometria_versoes force row level security;

create policy tenant_isolation on app.galpao_geometria_versoes
  using       (tenant_id = tenancy.current_tenant_id())
  with check  (tenant_id = tenancy.current_tenant_id());

grant select, insert, update, delete on app.galpao_geometria_versoes to wms_app;

alter table app.galpao_plantas
  add constraint fk_galpao_plantas_versao foreign key (versao_publicada_id)
  references app.galpao_geometria_versoes(id) on delete set null;

-- ============================================================================

create table app.galpao_enderecos (
  id             uuid primary key default gen_random_uuid(),
  tenant_id      uuid not null references tenancy.tenants(id) on delete cascade,
  planta_id      uuid not null references app.galpao_plantas(id) on delete cascade,

  codigo         text not null,
  setor          text not null,
  estrutura      text not null,
  modulo         integer not null,
  nivel          integer not null,
  tipo           text not null check (tipo in ('PICKING', 'PULMAO')),
  x              numeric(10, 3) not null,
  y              numeric(10, 3) not null,
  z              numeric(10, 3) not null,
  rotacao        numeric(7, 2) not null default 0,
  largura        numeric(10, 3) not null,
  comprimento    numeric(10, 3) not null,
  altura         numeric(10, 3) not null,
  cap_qtd        numeric(15, 5),
  cap_peso       numeric(15, 5),
  cap_volume     numeric(15, 5),
  bloqueado      boolean not null default false,
  ativo          boolean not null default true,

  atualizado_em  timestamptz not null default now(),

  unique (planta_id, codigo)
);

create index idx_galpao_enderecos_tenant on app.galpao_enderecos (tenant_id);
create index idx_galpao_enderecos_planta on app.galpao_enderecos (planta_id, ativo);

alter table app.galpao_enderecos enable row level security;
alter table app.galpao_enderecos force row level security;

create policy tenant_isolation on app.galpao_enderecos
  using       (tenant_id = tenancy.current_tenant_id())
  with check  (tenant_id = tenancy.current_tenant_id());

grant select, insert, update, delete on app.galpao_enderecos to wms_app;

-- ============================================================================

create table app.galpao_saldos_endereco (
  id             uuid primary key default gen_random_uuid(),
  tenant_id      uuid not null references tenancy.tenants(id) on delete cascade,
  endereco_id    uuid not null references app.galpao_enderecos(id),

  codprod        integer not null,
  controle       text not null default '',
  quantidade     numeric(15, 5) not null check (quantidade >= 0),
  versao         integer not null default 0,

  atualizado_em  timestamptz not null default now(),

  unique (endereco_id, codprod, controle)
);

create index idx_galpao_saldos_tenant on app.galpao_saldos_endereco (tenant_id);
create index idx_galpao_saldos_produto on app.galpao_saldos_endereco (tenant_id, codprod);

alter table app.galpao_saldos_endereco enable row level security;
alter table app.galpao_saldos_endereco force row level security;

create policy tenant_isolation on app.galpao_saldos_endereco
  using       (tenant_id = tenancy.current_tenant_id())
  with check  (tenant_id = tenancy.current_tenant_id());

grant select, insert, update, delete on app.galpao_saldos_endereco to wms_app;
