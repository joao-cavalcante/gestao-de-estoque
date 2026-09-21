-- V45 - historico de quem assumiu cada conferencia.
-- separacao_sessoes.operador_id guarda so o operador ATUAL: quando outro operador bipa o cracha na
-- mesma sessao (ex.: A deixou aberta, B assume no mesmo Stage) o vinculo anterior era sobrescrito
-- sem rastro. Cada identificacao vira uma linha aqui, com o operador que estava antes.
create table app.separacao_operador_historico (
  id                    uuid primary key,
  tenant_id             uuid not null references tenancy.tenants(id) on delete cascade,
  sessao_id             uuid not null,
  operador_id           uuid not null references app.users(id),
  operador_anterior_id  uuid references app.users(id),
  estacao_id            uuid references app.users(id),
  identificado_em       timestamptz not null
);

create index idx_sep_operador_hist_sessao on app.separacao_operador_historico (tenant_id, sessao_id, identificado_em);

alter table app.separacao_operador_historico enable row level security;
alter table app.separacao_operador_historico force row level security;

create policy tenant_isolation on app.separacao_operador_historico
  using       (tenant_id = tenancy.current_tenant_id())
  with check  (tenant_id = tenancy.current_tenant_id());

grant select, insert on app.separacao_operador_historico to wms_app;
