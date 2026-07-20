-- ============================================================================
-- V12 — Transferência entre Locais (coletor + desktop).
--
-- 1. app.modelos_nota          — TOP/natureza/empresa configurados por tenant
--    e tipo de atividade (hoje só 'transferencia_locais'). Sem uma linha
--    ativa aqui, NENHUMA interface pode iniciar uma transferência — ver
--    GET /api/modelos-nota na rota.
-- 2. app.locais_estoque        — STUB de cadastro de locais. Não existe
--    ainda integração real com "local de estoque" do Sankhya (confirmado:
--    zero referência a depósito/endereço no restante do backend) — isto
--    valida contra um cadastro próprio, no mesmo espírito de
--    SankhyaWriteBackService já ser stub hoje. Troca por integração real
--    quando o contrato Sankhya for definido.
-- 3. app.produtos_estoque + app.saldos_produto_local — mesmo raciocínio,
--    stub de cadastro/saldo por produto+local.
-- 4. app.transferencias + app.transferencia_itens — o registro de negócio
--    em si. canal_origem distingue lançamento via coletor (bipagem física
--    garantida) de desktop (sem essa garantia) — rastreável em qualquer
--    auditoria futura.
--
-- Segue a mesma receita de RLS do V2/V7 (tenant_id líder, ENABLE+FORCE RLS,
-- policy tenant_isolation, grant só pra wms_app).
-- ============================================================================

create table app.modelos_nota (
  id            uuid primary key default gen_random_uuid(),
  tenant_id     uuid not null references tenancy.tenants(id) on delete cascade,

  tipo          text not null, -- ex: 'transferencia_locais'
  codtop        integer not null,
  codemp        integer not null,
  codnat        integer not null,
  ativo         boolean not null default true,

  criado_em     timestamptz not null default now(),
  atualizado_em timestamptz not null default now(),

  unique (tenant_id, tipo)
);

create index idx_modelos_nota_tenant on app.modelos_nota (tenant_id);

alter table app.modelos_nota enable row level security;
alter table app.modelos_nota force row level security;

create policy tenant_isolation on app.modelos_nota
  using       (tenant_id = tenancy.current_tenant_id())
  with check  (tenant_id = tenancy.current_tenant_id());

grant select, insert, update, delete on app.modelos_nota to wms_app;

-- ============================================================================

create table app.locais_estoque (
  id            uuid primary key default gen_random_uuid(),
  tenant_id     uuid not null references tenancy.tenants(id) on delete cascade,

  codigo        text not null,
  ativo         boolean not null default true,

  criado_em     timestamptz not null default now(),

  unique (tenant_id, codigo)
);

create index idx_locais_estoque_tenant on app.locais_estoque (tenant_id);

alter table app.locais_estoque enable row level security;
alter table app.locais_estoque force row level security;

create policy tenant_isolation on app.locais_estoque
  using       (tenant_id = tenancy.current_tenant_id())
  with check  (tenant_id = tenancy.current_tenant_id());

grant select, insert, update, delete on app.locais_estoque to wms_app;

-- ============================================================================

create table app.produtos_estoque (
  id             uuid primary key default gen_random_uuid(),
  tenant_id      uuid not null references tenancy.tenants(id) on delete cascade,

  codigo         text not null,
  nome           text not null,
  ctrl           text not null default '',
  modo           text not null default 'unit' check (modo in ('unit', 'labelqty', 'bulk')),
  qtd_etiqueta   numeric(15, 5),
  unidade        text not null default 'un',

  criado_em      timestamptz not null default now(),

  unique (tenant_id, codigo)
);

create index idx_produtos_estoque_tenant on app.produtos_estoque (tenant_id);

alter table app.produtos_estoque enable row level security;
alter table app.produtos_estoque force row level security;

create policy tenant_isolation on app.produtos_estoque
  using       (tenant_id = tenancy.current_tenant_id())
  with check  (tenant_id = tenancy.current_tenant_id());

grant select, insert, update, delete on app.produtos_estoque to wms_app;

-- ============================================================================

create table app.saldos_produto_local (
  id             uuid primary key default gen_random_uuid(),
  tenant_id      uuid not null references tenancy.tenants(id) on delete cascade,

  codigo_produto text not null,
  codigo_local   text not null,
  saldo          numeric(15, 5) not null default 0,

  unique (tenant_id, codigo_produto, codigo_local)
);

create index idx_saldos_produto_local_tenant on app.saldos_produto_local (tenant_id);

alter table app.saldos_produto_local enable row level security;
alter table app.saldos_produto_local force row level security;

create policy tenant_isolation on app.saldos_produto_local
  using       (tenant_id = tenancy.current_tenant_id())
  with check  (tenant_id = tenancy.current_tenant_id());

grant select, insert, update, delete on app.saldos_produto_local to wms_app;

-- ============================================================================

create table app.transferencias (
  id                  uuid primary key default gen_random_uuid(),
  tenant_id           uuid not null references tenancy.tenants(id) on delete cascade,

  origem              text not null,
  destino             text not null,
  canal_origem        text not null check (canal_origem in ('coletor', 'desktop')),
  status              text not null default 'aberta' check (status in ('aberta', 'confirmada', 'cancelada')),

  operador_user_id    uuid not null,
  operador_nome       text not null,

  pendente_write_back boolean not null default false,

  criado_em           timestamptz not null default now(),
  atualizado_em       timestamptz not null default now(),
  confirmado_em       timestamptz
);

create index idx_transferencias_tenant on app.transferencias (tenant_id);
create index idx_transferencias_tenant_criado on app.transferencias (tenant_id, criado_em desc);
create index idx_transferencias_pendente_wb on app.transferencias (tenant_id) where pendente_write_back;

alter table app.transferencias enable row level security;
alter table app.transferencias force row level security;

create policy tenant_isolation on app.transferencias
  using       (tenant_id = tenancy.current_tenant_id())
  with check  (tenant_id = tenancy.current_tenant_id());

grant select, insert, update, delete on app.transferencias to wms_app;

-- ============================================================================

create table app.transferencia_itens (
  id                uuid primary key default gen_random_uuid(),
  tenant_id         uuid not null references tenancy.tenants(id) on delete cascade,
  transferencia_id  uuid not null references app.transferencias(id) on delete cascade,

  codigo_produto    text not null,
  nome_produto      text not null,
  controle          text not null default '',
  quantidade        numeric(15, 5) not null,
  unidade           text not null default 'un',

  criado_em         timestamptz not null default now()
);

create index idx_transferencia_itens_tenant on app.transferencia_itens (tenant_id);
create index idx_transferencia_itens_transferencia on app.transferencia_itens (tenant_id, transferencia_id);

alter table app.transferencia_itens enable row level security;
alter table app.transferencia_itens force row level security;

create policy tenant_isolation on app.transferencia_itens
  using       (tenant_id = tenancy.current_tenant_id())
  with check  (tenant_id = tenancy.current_tenant_id());

grant select, insert, update, delete on app.transferencia_itens to wms_app;
