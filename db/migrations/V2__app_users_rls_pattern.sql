-- ============================================================================
-- V2 — app.users: primeira tabela de negócio, servindo de PADRÃO DE
-- REFERÊNCIA para RLS. Toda tabela por-tenant criada daqui pra frente
-- (sessão, conferência, volume, balança, cache de produto, ...) deve seguir
-- exatamente esta receita:
--
--   1. coluna tenant_id uuid not null references tenancy.tenants(id)
--   2. índice com tenant_id como coluna líder (isolamento + performance)
--   3. ENABLE ROW LEVEL SECURITY
--   4. FORCE ROW LEVEL SECURITY  <- passo que a maioria esquece; sem ele,
--      o DONO da tabela (wms_owner) ignora a política. Como a aplicação
--      roda como wms_app (não-dono), isso já bastaria — mas FORCE é
--      defesa em profundidade caso um dia alguém rode uma query manual
--      como wms_owner por engano.
--   5. política USING/WITH CHECK comparando tenant_id com
--      tenancy.current_tenant_id() (lido de app.tenant_id via SET LOCAL)
--   6. GRANT apenas pro role wms_app, nunca PUBLIC
-- ============================================================================

create table app.users (
  id              uuid primary key default gen_random_uuid(),
  tenant_id       uuid not null references tenancy.tenants(id) on delete cascade,

  codigo_erp      integer,           -- código do usuário no ERP do tenant (se houver)
  nome            text not null,
  email           text not null,
  senha_hash      text,
  perfil          text not null default 'OPERADOR', -- ADMINISTRADOR | SEPARADOR | OPERADOR ...
  ativo           boolean not null default true,

  criado_em       timestamptz not null default now(),
  atualizado_em   timestamptz not null default now(),

  -- unicidade é sempre COMPOSTA com tenant_id — nunca UNIQUE(email) sozinho,
  -- isso é o que causou o bug de colisão de sessão que achamos no sistema
  -- atual (chave global reaproveitada entre tenants).
  unique (tenant_id, email)
);

-- tenant_id líder no índice: toda query de negócio filtra por tenant
-- primeiro, então isso também acelera os planos de execução, além de
-- reforçar isolamento.
create index idx_users_tenant on app.users (tenant_id);
create index idx_users_tenant_codigo_erp on app.users (tenant_id, codigo_erp);

alter table app.users enable row level security;
alter table app.users force row level security;

create policy tenant_isolation on app.users
  using       (tenant_id = tenancy.current_tenant_id())
  with check  (tenant_id = tenancy.current_tenant_id());

grant select, insert, update, delete on app.users to wms_app;
-- Sequências/uuid default não exigem grant extra (gen_random_uuid() roda
-- no lado do servidor, sem sequence dedicada).
