-- ============================================================================
-- V5 — módulos básicos: login/usuários (JWT) e balanças (config).
--
-- 1) app.users (já existe desde V2) ganha colunas de reset de senha.
-- 2) tenancy.user_login_lookup: e-mail -> tenant_id, SEM RLS (mesmo
--    raciocínio de tenancy.sync_estado — login por e-mail precisa resolver
--    o tenant ANTES de existir qualquer contexto de RLS; login não pode
--    "escanear todos os tenants" com RLS ligada). E-mail é GLOBALMENTE
--    único por decisão de produto (confirmado com o usuário) — por isso
--    é PK aqui, não composta com tenant_id.
-- 3) app.balancas: mesma receita de RLS do V2 — cadastro de balança é só
--    CONFIGURAÇÃO (porta serial, protocolo etc.); a leitura de peso
--    acontece no navegador do operador via WebSocket com o agente local
--    (Electron, porta 3099), não tem driver server-side.
-- ============================================================================

alter table app.users
  add column reset_token       text,
  add column reset_token_expira timestamptz;

create table tenancy.user_login_lookup (
  email     text primary key,
  tenant_id uuid not null references tenancy.tenants(id) on delete cascade,
  user_id   uuid not null
);

grant select, insert, update, delete on tenancy.user_login_lookup to wms_app;

create table app.balancas (
  id                 uuid primary key default gen_random_uuid(),
  tenant_id          uuid not null references tenancy.tenants(id) on delete cascade,

  nome               text not null,
  fabricante         text,
  tipo_comunicacao   text not null default 'SERIAL_USB', -- SERIAL_RS232 | SERIAL_USB | HTTP | TOLEDO_TCP
  porta_com          text,
  baud_rate          integer,
  data_bits          integer,
  paridade           text,
  stop_bits          integer,
  protocolo_serial   text,   -- P05 | PRT1 | PRT2 | CONTINUO | SOB_REQUISICAO
  ip                 text,
  porta              integer,
  ativo              boolean not null default true,

  criado_em          timestamptz not null default now(),
  atualizado_em      timestamptz not null default now()
);

create index idx_balancas_tenant on app.balancas (tenant_id);
create index idx_balancas_tenant_ativo on app.balancas (tenant_id, ativo);

alter table app.balancas enable row level security;
alter table app.balancas force row level security;

create policy tenant_isolation on app.balancas
  using       (tenant_id = tenancy.current_tenant_id())
  with check  (tenant_id = tenancy.current_tenant_id());

grant select, insert, update, delete on app.balancas to wms_app;
