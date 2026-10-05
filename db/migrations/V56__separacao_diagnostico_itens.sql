-- ============================================================================
-- V56 — rastreio de quais linhas do pedido entraram na conferência.
--
-- Caso real (nota 61514, 05/10/2026): dois itens do pedido (REQUEIJÃO 100 e
-- FERMENTO 328) não vieram na abertura da conferência e só apareceram com o
-- "Atualizar com Sankhya" horas depois. O único filtro que esconde linha do
-- pedido é o de "já conferido" (QTDCONF da DetalhesConferencia >= QTDNEG), e
-- não havia registro do que ele escondeu — o log some a cada deploy.
--
-- Uma linha por carga de itens (abertura da sessão ou sincronização):
-- quantas linhas o pedido tem, quantas entraram e, em `ocultas`, cada linha
-- escondida com o motivo (e as que o filtro QUERIA esconder mas a checagem na
-- TGFCOI2 manteve). Ver SeparacaoService.buscarItensDaNota.
-- ============================================================================

create table app.separacao_diagnosticos (
  id                 uuid primary key,
  tenant_id          uuid not null references tenancy.tenants(id) on delete cascade,
  sessao_id          uuid not null,
  nunota             integer not null,
  nuconf             integer,
  origem             text not null,      -- 'abertura' | 'sincronizacao'
  recontagem         boolean not null default false,
  linhas_pedido      integer not null,
  linhas_carregadas  integer not null,
  ocultas            jsonb not null default '[]'::jsonb,
  criado_em          timestamptz not null default now()
);

create index idx_separacao_diagnosticos_nunota on app.separacao_diagnosticos (tenant_id, nunota, criado_em);

alter table app.separacao_diagnosticos enable row level security;
alter table app.separacao_diagnosticos force row level security;

create policy tenant_isolation on app.separacao_diagnosticos
  using       (tenant_id = tenancy.current_tenant_id())
  with check  (tenant_id = tenancy.current_tenant_id());

grant select, insert, update, delete on app.separacao_diagnosticos to wms_app;
