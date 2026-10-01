-- ============================================================================
-- V53 — reconferência: check manual item a item do que foi conferido numa
-- sessão (durante a conferência, no pop-up "Ver conferidos", ou depois de
-- finalizada, na tela Reconferência). Só registro visual — não altera a
-- conferência nem o Sankhya.
-- ============================================================================

create table app.reconferencia_checks (
  tenant_id    uuid not null references tenancy.tenants(id) on delete cascade,
  sessao_id    uuid not null references app.separacao_sessoes(id) on delete cascade,
  codprod      integer not null,
  controle     text not null default '',
  checado_por  text,
  checado_em   timestamptz not null default now(),
  primary key (tenant_id, sessao_id, codprod, controle)
);

alter table app.reconferencia_checks enable row level security;
alter table app.reconferencia_checks force row level security;

create policy tenant_isolation on app.reconferencia_checks
  using       (tenant_id = tenancy.current_tenant_id())
  with check  (tenant_id = tenancy.current_tenant_id());

grant select, insert, update, delete on app.reconferencia_checks to wms_app;
