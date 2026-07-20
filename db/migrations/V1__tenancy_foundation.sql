-- ============================================================================
-- V1 — Fundação de multi-tenancy: schemas, roles, extensões e tabelas de
-- controle (tenants + conexões de ERP por tenant).
--
-- Modelo: schema compartilhado (um Postgres só) + Row-Level Security (RLS)
-- por tenant_id. Tenants "dedicated" (tier enterprise) apontam pra uma
-- dedicated_db_url separada — o roteamento pra lá é feito na aplicação,
-- não neste schema.
-- ============================================================================

-- ─── Extensões ───────────────────────────────────────────────────────────
create extension if not exists pgcrypto; -- gen_random_uuid()

-- ─── Schemas ─────────────────────────────────────────────────────────────
-- "tenancy": plano de controle — registro de tenants, conexões de ERP,
--            usuários master (sem tenant_id, não passam por RLS).
-- "app":     plano de dados de negócio — tudo que é por-tenant e tem RLS.
create schema if not exists tenancy;
create schema if not exists app;

-- ─── Roles ───────────────────────────────────────────────────────────────
-- wms_owner: dono das tabelas, roda migrações. NUNCA é o role de conexão
--            da aplicação em runtime (dono de tabela ignora RLS por padrão,
--            mesmo com a política criada — só a cláusula FORCE resolve
--            isso, e ainda assim é mais seguro a app nunca ser o dono).
-- wms_app:   role de runtime da aplicação (Ktor/HikariCP conecta com este).
--            Recebe apenas os privilégios de linha necessários; a RLS
--            se aplica integralmente a ele.
do $$
begin
  if not exists (select 1 from pg_roles where rolname = 'wms_owner') then
    create role wms_owner noinherit;
  end if;
  if not exists (select 1 from pg_roles where rolname = 'wms_app') then
    create role wms_app login password 'CHANGE_ME_EM_PRODUCAO' noinherit;
  end if;
end
$$;

grant usage on schema tenancy, app to wms_app;

-- ─── Helper: tenant atual da transação ──────────────────────────────────
-- Convenção: a aplicação faz, no início de CADA transação,
--   SET LOCAL app.tenant_id = '<uuid-do-tenant>';
-- (SET LOCAL, não SET de sessão — é o que permite usar PgBouncer em modo
-- transaction pooling sem vazar tenant_id entre transações de conexões
-- reaproveitadas.)
--
-- current_tenant_id() centraliza a leitura dessa variável de sessão pra
-- ser usada em toda política de RLS — evita repetir
-- current_setting('app.tenant_id')::uuid cru em cada CREATE POLICY, e o
-- `true` no segundo argumento faz retornar NULL (em vez de erro) quando a
-- variável não foi setada, o que é importante: sem tenant setado, toda
-- política de RLS abaixo nega tudo (NULL = qualquer coisa é sempre falso).
create or replace function tenancy.current_tenant_id() returns uuid as $$
  select nullif(current_setting('app.tenant_id', true), '')::uuid;
$$ language sql stable;

-- ============================================================================
-- tenancy.tenants — registro central de tenants
-- ============================================================================
-- Nota: text + check em vez de tipo ENUM nativo do Postgres, de propósito.
-- ENUM nativo exige cast explícito (?::tenancy.tenant_tier) em toda query
-- via JDBC/ORM e migração pra adicionar um valor novo (ALTER TYPE ... ADD
-- VALUE tem restrições dentro de transação) — fricção que não compensa
-- aqui. CHECK dá a mesma garantia de valores válidos com zero fricção de
-- driver/ORM.
create table tenancy.tenants (
  id                uuid primary key default gen_random_uuid(),
  slug              text not null unique,
  nome              text not null,
  tier              text not null default 'shared'
                      check (tier in ('shared', 'dedicated')),
  status            text not null default 'trial'
                      check (status in ('trial', 'active', 'suspended', 'cancelled')),

  -- Só preenchido quando tier = 'dedicated'. A aplicação decide, no momento
  -- de abrir a conexão, se usa o pool compartilhado (tier='shared') ou essa
  -- URL dedicada (tier='dedicated'). Mesma base de código nos dois casos.
  dedicated_db_url  text,

  criado_em         timestamptz not null default now(),
  atualizado_em     timestamptz not null default now(),

  constraint dedicated_precisa_de_url
    check (tier <> 'dedicated' or dedicated_db_url is not null)
);

create index idx_tenants_slug on tenancy.tenants (slug) where status <> 'cancelled';

-- A própria API de administração de tenants (tenant-manager) roda como
-- wms_app e precisa criar/editar tenants — não é só leitura.
grant select, insert, update, delete on tenancy.tenants to wms_app;

-- ============================================================================
-- tenancy.erp_connections — credenciais de integração de ERP por tenant
-- ============================================================================
-- Desacoplado de "tenants" de propósito: um tenant pode não ter ERP
-- nenhum (WMS standalone) ou, no futuro, ter mais de uma integração.
-- erp_type é texto livre (não enum) pra não exigir migração de schema
-- toda vez que um novo ERP for suportado (hoje: 'sankhya'; futuro: outros).
create table tenancy.erp_connections (
  id            uuid primary key default gen_random_uuid(),
  tenant_id     uuid not null references tenancy.tenants(id) on delete cascade,
  erp_type      text not null,               -- ex: 'sankhya'
  base_url      text not null,
  gateway_path  text,
  dialect       text                         -- dialeto do banco DO ERP (não do nosso)
                  check (dialect in ('SQLSERVER', 'ORACLE', 'POSTGRES', 'MYSQL')),
  modulos       text[] not null default '{}', -- feature flags habilitadas pro tenant

  -- Credenciais sensíveis: armazenadas como texto JSON opaco (não jsonb
  -- nativo, de propósito — evita fricção de tipo do driver JDBC/Exposed
  -- em troca de indexação/query dentro do JSON que não precisamos agora;
  -- a aplicação decide o shape por erp_type: client_id/secret/token pro
  -- Sankhya, outro formato pra outro ERP). Em produção isso deve ser
  -- criptografado em repouso (pgcrypto pgp_sym_encrypt, ou um KMS externo)
  -- — deixado em texto plano aqui só pra fase de fundação.
  credenciais   text not null default '{}',

  ativo         boolean not null default true,
  criado_em     timestamptz not null default now(),
  atualizado_em timestamptz not null default now(),

  unique (tenant_id, erp_type)
);

grant select, insert, update, delete on tenancy.erp_connections to wms_app;

-- ============================================================================
-- tenancy.master_users — administradores da plataforma (sem tenant_id)
-- ============================================================================
create table tenancy.master_users (
  id            uuid primary key default gen_random_uuid(),
  email         text not null unique,
  nome          text not null,
  senha_hash    text not null,
  ativo         boolean not null default true,
  criado_em     timestamptz not null default now()
);

grant select, update (senha_hash, ativo) on tenancy.master_users to wms_app;
