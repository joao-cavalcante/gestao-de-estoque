-- ============================================================================
-- V29 — conferência por ETAPA (tipo de separação do produto).
--
-- Quando o módulo por-tenant `conferencia_segmentada` está ligado (V25), a
-- conferência de uma nota é quebrada em até 3 etapas pelo tipo de separação
-- do produto (TGFPRO.AD_TIPOSEPARACAO): 1 = Secos, 2 = Resfriados, 3 =
-- Congelados. Cada etapa é conferida separadamente (zonas/operadores
-- diferentes) e a nota só é finalizada no Sankhya quando todas as etapas que
-- têm itens estão concluídas.
--
-- Porta a mecânica de `sessaoEtapa` do fila-de-conferencia (que lá é quebrada
-- por pesável / não-pesável), trocando a chave de agrupamento.
--
-- Item sem AD_TIPOSEPARACAO (null / 0 / fora de 1-3) cai em Secos (1) — por
-- isso o default da coluna e a ausência de etapa "sem classificação".
-- ============================================================================

-- Snapshot do tipo de separação por item (resolvido na abertura da sessão).
alter table app.separacao_itens add column tipo_separacao smallint not null default 1;

-- Etapas da sessão — uma linha por tipo_separacao que REALMENTE tem item na
-- nota. Segue a receita de RLS do V7.
create table app.separacao_etapas (
  id             uuid primary key default gen_random_uuid(),
  tenant_id      uuid not null references tenancy.tenants(id) on delete cascade,
  sessao_id      uuid not null references app.separacao_sessoes(id) on delete cascade,

  tipo_separacao smallint not null,             -- 1 Secos | 2 Resfriados | 3 Congelados
  status         text not null default 'P',     -- 'P' pendente | 'C' concluída
  concluida_por  text,                           -- login do operador que concluiu
  concluida_em   timestamptz,

  criado_em      timestamptz not null default now(),

  unique (tenant_id, sessao_id, tipo_separacao)
);

create index idx_separacao_etapas_tenant_sessao on app.separacao_etapas (tenant_id, sessao_id);

alter table app.separacao_etapas enable row level security;
alter table app.separacao_etapas force row level security;

create policy tenant_isolation on app.separacao_etapas
  using       (tenant_id = tenancy.current_tenant_id())
  with check  (tenant_id = tenancy.current_tenant_id());

grant select, insert, update, delete on app.separacao_etapas to wms_app;
