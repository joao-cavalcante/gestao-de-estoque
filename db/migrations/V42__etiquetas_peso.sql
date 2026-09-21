-- ============================================================================
-- V42 — etiquetas de produto pesável (impressão térmica).
--
-- Uma etiqueta = um item pesável conferido de uma sessão de conferência, com o
-- peso efetivamente pesado (separacao_itens.qtd_conferida_local, já em KG). O
-- `numero` é o identificador único da etiqueta (gerado pelo banco no momento
-- da primeira impressão) e permite relacionar depois etiqueta ↔ produto ↔
-- pedido ↔ cliente ↔ conferência.
--
-- Reimprimir não cria linha nova: só incrementa `impressoes`. Uma etiqueta
-- nova pro mesmo item só nasce se o operador pedir explicitamente OU se o peso
-- do item mudou desde a última (a antiga vira `ativa = false`, fica no
-- histórico). O índice parcial garante no máximo uma etiqueta ATIVA por item.
-- ============================================================================

create table app.etiquetas_peso (
  id                    uuid primary key,
  tenant_id             uuid not null references tenancy.tenants(id) on delete cascade,
  numero                bigint generated always as identity,
  sessao_id             uuid not null,
  nunota                integer not null,
  nuconf                integer,
  codprod               integer not null,
  controle              text not null,
  produto               text not null,
  peso                  numeric(20, 3) not null,
  cliente               text not null default '',
  ativa                 boolean not null default true,
  impressoes            integer not null default 1,
  criado_em             timestamptz not null,
  ultima_impressao_em   timestamptz not null
);

create unique index uq_etiquetas_peso_numero on app.etiquetas_peso (numero);
create unique index uq_etiquetas_peso_ativa
  on app.etiquetas_peso (tenant_id, sessao_id, codprod, controle) where ativa;
create index idx_etiquetas_peso_nunota on app.etiquetas_peso (tenant_id, nunota);

alter table app.etiquetas_peso enable row level security;
alter table app.etiquetas_peso force row level security;

create policy tenant_isolation on app.etiquetas_peso
  using       (tenant_id = tenancy.current_tenant_id())
  with check  (tenant_id = tenancy.current_tenant_id());

grant select, insert, update on app.etiquetas_peso to wms_app;
