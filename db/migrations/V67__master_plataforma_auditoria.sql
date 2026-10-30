-- ============================================================================
-- V67 — administração Master da plataforma (Entrega 1.1).
--
-- Identidade Master = tenancy.master_users (V1, até aqui sem uso): fora de qualquer tenant, nunca um `perfil` de
-- app.users (perfil é texto livre editado pelo admin do tenant — se Master fosse perfil, um admin de cliente se
-- promoveria). Login próprio (/api/master/auth/login), token com escopo "plataforma" e sem tenant_id.
--
-- Cadastro de Master: só pela ferramenta de linha de comando dentro do container (CriarMasterKt) — nunca pela API.
--
-- tenancy.auditoria_admin: trilha de TODA alteração administrativa da plataforma. Só INSERT/SELECT pro wms_app
-- (sem UPDATE/DELETE: a aplicação não apaga nem reescreve a própria trilha). `detalhe` nunca leva segredo.
-- Plano de controle (tenancy.*), sem RLS, como tenants/erp_connections: quem lê é só o Master.
-- ============================================================================

grant insert on tenancy.master_users to wms_app;

create table tenancy.auditoria_admin (
  id           bigint generated always as identity primary key,
  ocorrido_em  timestamptz not null default now(),
  ator_tipo    text not null check (ator_tipo in ('master', 'sistema')),
  ator_id      uuid,
  ator_email   text,
  tenant_id    uuid references tenancy.tenants(id) on delete set null,
  tenant_slug  text,
  acao         text not null,
  resultado    text not null default 'ok' check (resultado in ('ok', 'negado', 'erro')),
  detalhe      jsonb not null default '{}'::jsonb,
  ip           text
);

create index idx_auditoria_admin_tenant on tenancy.auditoria_admin (tenant_id, ocorrido_em desc);
create index idx_auditoria_admin_data on tenancy.auditoria_admin (ocorrido_em desc);

grant select, insert on tenancy.auditoria_admin to wms_app;
