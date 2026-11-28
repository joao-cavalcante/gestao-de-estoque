-- ============================================================================
-- V84 — Recebimento na doca, etapa D2b (27/11/2026). Desenho: sankhya-hub docs/projetos/torre-recebimento-doca.md.
--
-- Vários conferentes na mesma COMPRA (TIPMOV C): cada aparelho entra como participante (a saída continua com o
-- lock exclusivo de V46). Cada leitura e cada palete guardam o conferente: "Fechar palete" pega só o que ele leu.
-- Leituras antigas (sem conferente) ficam num bolso comum, visível a todos.
-- Fila de pesagem: o conferente conta as caixas do pesável no coletor e manda para a balança; o pesador pesa no
-- computador da balança, a leitura entra no nome do conferente e o palete sai fechado com a etiqueta.
-- ============================================================================

alter table app.separacao_leituras
  add column conferente_user_id uuid,
  add column conferente_nome    text;

alter table app.recebimento_paletes
  add column criado_por_user uuid;

create table app.conferencia_participantes (
  tenant_id         uuid not null references tenancy.tenants(id) on delete cascade,
  sessao_id         uuid not null,
  token             uuid not null,
  user_id           uuid not null,
  nome              text,
  entrou_em         timestamptz not null default now(),
  ultima_atividade  timestamptz not null default now(),
  primary key (tenant_id, sessao_id, token)
);

alter table app.conferencia_participantes enable row level security;
alter table app.conferencia_participantes force row level security;
create policy tenant_isolation on app.conferencia_participantes
  using       (tenant_id = tenancy.current_tenant_id())
  with check  (tenant_id = tenancy.current_tenant_id());
grant select, insert, update, delete on app.conferencia_participantes to wms_app;

create table app.pesagem_fila (
  id                  uuid primary key,
  tenant_id           uuid not null references tenancy.tenants(id) on delete cascade,
  sessao_id           uuid not null,
  nunota              integer not null,
  codprod             integer not null,
  controle            text not null,
  descricao           text,
  caixas              numeric(15,5) not null check (caixas > 0),
  unidade_comercial   text,
  codvol              text,
  codigo_barra        text,
  conferente_user_id  uuid not null,
  conferente_nome     text,
  criado_em           timestamptz not null default now(),
  status              text not null default 'PENDENTE' check (status in ('PENDENTE', 'PESANDO', 'PESADO', 'CANCELADO')),
  peso_bruto          numeric(15,5),
  tara                numeric(15,5),
  peso_liquido        numeric(15,5),
  origem_peso         text,
  pesado_por          text,
  pesado_em           timestamptz,
  palete_id           uuid
);
create index pesagem_fila_status on app.pesagem_fila (tenant_id, status, criado_em);
create index pesagem_fila_sessao on app.pesagem_fila (tenant_id, sessao_id);

alter table app.pesagem_fila enable row level security;
alter table app.pesagem_fila force row level security;
create policy tenant_isolation on app.pesagem_fila
  using       (tenant_id = tenancy.current_tenant_id())
  with check  (tenant_id = tenancy.current_tenant_id());
grant select, insert, update, delete on app.pesagem_fila to wms_app;
