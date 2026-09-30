-- ============================================================================
-- V51 — usuários autorizados por TOP (N:N usuário ↔ TOP).
--
-- Mesma regra da app.balanca_usuarios (V23), agora POR RECURSO:
--   TOP sem nenhuma linha aqui = sem restrição (todos usam — comportamento de antes);
--   TOP com linhas = só esses usuários conferem notas dessa TOP.
-- Regra centralizada em wms.backend.permissoes.PermissoesRecurso.
--
-- Por codtop (e não FK pra app.tipos_operacao): o espelho de TOPs é regravado a
-- cada sincronização — a escolha do administrador não pode sumir com ele.
-- ============================================================================

create table app.tipo_operacao_usuarios (
  tenant_id   uuid not null references tenancy.tenants(id) on delete cascade,
  codtop      integer not null,
  usuario_id  uuid not null references app.users(id) on delete cascade,
  criado_em   timestamptz not null default now(),
  primary key (tenant_id, codtop, usuario_id)
);

create index idx_tipo_operacao_usuarios_usuario on app.tipo_operacao_usuarios (tenant_id, usuario_id);

alter table app.tipo_operacao_usuarios enable row level security;
alter table app.tipo_operacao_usuarios force row level security;

create policy tenant_isolation on app.tipo_operacao_usuarios
  using       (tenant_id = tenancy.current_tenant_id())
  with check  (tenant_id = tenancy.current_tenant_id());

grant select, insert, update, delete on app.tipo_operacao_usuarios to wms_app;
