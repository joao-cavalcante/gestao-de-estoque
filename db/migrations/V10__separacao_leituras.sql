-- ============================================================================
-- V10 — persistência real da conferência (antes só existia otimista no
-- navegador, nada era gravado). Mesma ideia de `SessaoService.registrarLeitura`
-- do projeto base: cada bipe confirmado vira uma linha crua aqui (auditoria
-- completa de quem bipou o quê), e `app.separacao_itens.qtd_conferida_local`
-- é recalculado a partir da SOMA dessas linhas — nunca um "+=" direto no
-- item, pra não perder histórico nem ficar vulnerável a race condition.
-- ============================================================================

create table app.separacao_leituras (
  id            uuid primary key default gen_random_uuid(),
  tenant_id     uuid not null references tenancy.tenants(id) on delete cascade,
  sessao_id     uuid not null references app.separacao_sessoes(id) on delete cascade,

  codprod       integer not null,
  controle      text not null default ' ',
  codvol        text,
  qtd           numeric(15,5) not null,

  criado_em     timestamptz not null default now()
);

create index idx_separacao_leituras_tenant on app.separacao_leituras (tenant_id);
create index idx_separacao_leituras_sessao_produto on app.separacao_leituras (sessao_id, codprod, controle);

alter table app.separacao_leituras enable row level security;
alter table app.separacao_leituras force row level security;

create policy tenant_isolation on app.separacao_leituras
  using       (tenant_id = tenancy.current_tenant_id())
  with check  (tenant_id = tenancy.current_tenant_id());

-- delete: precisa pro "devolver item" (desfaz tudo que foi conferido pra
-- um produto+controle, apagando as leituras em vez de deixar lixo de
-- auditoria de algo que nunca deveria ter contado).
grant select, insert, delete on app.separacao_leituras to wms_app;
