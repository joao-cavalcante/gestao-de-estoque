-- ============================================================================
-- V50 — tolerância de peso configurável por Configuração de Conferência (NUCCO).
--
-- Antes era fixa no código: item pesável A MENOR só divergia além de 5% (e era
-- liberado sozinho até 5%); A MAIOR nunca divergia (sempre liberado). Conferência
-- de COMPRA (entrada) não pode ter essa folga — qualquer diferença de peso tem que
-- aparecer. Agora cada NUCCO define os dois limites:
--   tol_acima_pct  — % a mais aceito sem divergência (NULL = sem limite)
--   tol_abaixo_pct — % a menos aceito sem divergência (NULL = sem limite)
-- Sem linha pro NUCCO = regra de antes (acima sem limite, abaixo 5%).
--
-- Tabela PRÓPRIA do WMS (não campo da TGFCCO espelhada — o Sankhya não tem esse
-- conceito e a sync regravaria o espelho).
--
-- Cópia na sessão (separacao_sessoes): a conferência usa a tolerância do momento
-- em que abriu, igual aos outros campos da CCO. Sessões já existentes recebem a
-- regra de antes (abaixo 5, acima NULL) pelo DEFAULT.
-- ============================================================================

create table app.conferencia_tolerancia (
  tenant_id       uuid not null references tenancy.tenants(id) on delete cascade,
  nucco           integer not null,
  tol_acima_pct   numeric(6,2),
  tol_abaixo_pct  numeric(6,2),
  atualizado_em   timestamptz not null default now(),
  primary key (tenant_id, nucco),
  check (tol_acima_pct is null or tol_acima_pct >= 0),
  check (tol_abaixo_pct is null or tol_abaixo_pct >= 0)
);

alter table app.conferencia_tolerancia enable row level security;
alter table app.conferencia_tolerancia force row level security;

create policy tenant_isolation on app.conferencia_tolerancia
  using       (tenant_id = tenancy.current_tenant_id())
  with check  (tenant_id = tenancy.current_tenant_id());

grant select, insert, update, delete on app.conferencia_tolerancia to wms_app;

alter table app.separacao_sessoes add column tol_peso_acima_pct numeric(6,2);
alter table app.separacao_sessoes add column tol_peso_abaixo_pct numeric(6,2) default 5;
