-- ============================================================================
-- V14 — Auditoria de Estoque (Inventário).
--
-- Diferente de Transferência (ação direta bipa→confirma→grava), Inventário
-- tem uma etapa de decisão humana no meio: contagem não grava nada sozinha,
-- alimenta uma comparação que precisa ser revisada e aprovada antes de
-- qualquer ajuste real de saldo (ver app.inventarios.status).
--
-- Mesma receita de RLS do V2/V12 (tenant_id líder, ENABLE+FORCE, policy
-- tenant_isolation, grant só pra wms_app).
--
-- app.modelos_nota (V12) não muda de schema — recebe uma linha nova por
-- tenant com tipo='ajuste_inventario', mesma trava já usada em Transferência.
-- ============================================================================

create table app.inventarios (
  id                    uuid primary key default gen_random_uuid(),
  tenant_id             uuid not null references tenancy.tenants(id) on delete cascade,

  descricao             text not null,
  escopo_tipo           text not null check (escopo_tipo in ('local', 'grupo_produto', 'produtos_especificos')),
  escopo_valores        jsonb not null,

  status                text not null default 'aberto'
                          check (status in ('aberto', 'em_contagem', 'finalizado', 'ajustado', 'cancelado')),

  aberto_por_user_id    uuid not null,
  aberto_por_nome       text not null,
  aberto_em             timestamptz not null default now(),
  fechado_em            timestamptz,

  ajustado_por_user_id  uuid,
  ajustado_por_nome     text,
  ajustado_em           timestamptz,

  pendente_write_back   boolean not null default false,
  observacoes           text
);

create index idx_inventarios_tenant on app.inventarios (tenant_id);
create index idx_inventarios_tenant_aberto on app.inventarios (tenant_id, aberto_em desc);
create index idx_inventarios_pendente_wb on app.inventarios (tenant_id) where pendente_write_back;

alter table app.inventarios enable row level security;
alter table app.inventarios force row level security;

create policy tenant_isolation on app.inventarios
  using       (tenant_id = tenancy.current_tenant_id())
  with check  (tenant_id = tenancy.current_tenant_id());

grant select, insert, update, delete on app.inventarios to wms_app;

-- ============================================================================

create table app.inventario_itens (
  id                        uuid primary key default gen_random_uuid(),
  tenant_id                 uuid not null references tenancy.tenants(id) on delete cascade,
  inventario_id             uuid not null references app.inventarios(id) on delete cascade,

  produto_codigo            text not null,
  controle                  text not null default '',
  local                     text not null,

  -- Capturado do "TGFEST" (saldo) uma única vez, no momento da abertura —
  -- não é recalculado em tempo real durante a contagem. Só é atualizado de
  -- novo no momento da aprovação, se o saldo tiver mudado (ver regra de
  -- recontagem no repositório).
  quantidade_sistema        numeric(15, 5) not null,
  quantidade_contada        numeric(15, 5) not null default 0,

  status_item               text not null default 'pendente'
                              check (status_item in ('pendente', 'contado', 'nao_previsto')),

  operador_contagem_user_id uuid,
  operador_contagem_nome    text,
  contado_em                timestamptz,
  canal_origem              text check (canal_origem in ('coletor', 'desktop')),

  unique (inventario_id, produto_codigo, controle, local)
);

create index idx_inventario_itens_tenant on app.inventario_itens (tenant_id);
create index idx_inventario_itens_inventario on app.inventario_itens (tenant_id, inventario_id);

alter table app.inventario_itens enable row level security;
alter table app.inventario_itens force row level security;

create policy tenant_isolation on app.inventario_itens
  using       (tenant_id = tenancy.current_tenant_id())
  with check  (tenant_id = tenancy.current_tenant_id());

grant select, insert, update, delete on app.inventario_itens to wms_app;
