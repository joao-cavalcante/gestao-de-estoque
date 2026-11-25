-- ============================================================================
-- V80 — Acesso por empresa (25/11/2026): transferência entre filiais na mesma Torre; "o usuário vai poder ver apenas
-- as coisas da empresa dele" (filtro geral).
--
-- app.usuario_empresas: empresas (TGFEMP.CODEMP) que cada usuário vê. Usuário SEM linha = todas (comportamento de
-- antes); ADMINISTRADOR vê todas sempre. O filtro é aplicado no backend em todo ponto que entrega nota/OC/estoque.
--
-- app.recebimento_auditoria ganha o CODEMP (a coluna empresa guarda o nome, para a tela).
-- ============================================================================

create table app.usuario_empresas (
  tenant_id   uuid not null references tenancy.tenants(id) on delete cascade,
  usuario_id  uuid not null references app.users(id) on delete cascade,
  codemp      integer not null,
  criado_em   timestamptz not null default now(),
  primary key (tenant_id, usuario_id, codemp)
);

alter table app.usuario_empresas enable row level security;
alter table app.usuario_empresas force row level security;
create policy tenant_isolation on app.usuario_empresas
  using       (tenant_id = tenancy.current_tenant_id())
  with check  (tenant_id = tenancy.current_tenant_id());
grant select, insert, update, delete on app.usuario_empresas to wms_app;

alter table app.recebimento_auditoria add column codemp integer;
