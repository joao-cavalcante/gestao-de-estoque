-- ============================================================================
-- V23 — rotina de peso/balança portada do projeto base (fila-de-conferencia):
--
-- - UMA (Unidade de Movimentação e Armazenagem) não é catálogo persistente
--   no Sankhya nem no legado — é lida ao vivo por sessão (produto pode ganhar/
--   perder UMA a qualquer momento) e só cacheada durante a conferência,
--   mesmo espírito de app.separacao_codigos_barra. Espelha
--   model SessaoItemUma do legado (Prisma).
-- - separacao_itens.usa_conf_peso — TGFVOL.UTILICONFPESO do CODVOL do
--   produto, resolvido uma vez no carregamento (mesma técnica de
--   buscar_codigo_barra_por).
-- - separacao_sessoes.obter_qtd_balanca — CCO.OBTERQTDBALANCA cru
--   ('N'=não obter / outro=obter via balança), mesmo padrão de qtdamaior.
-- - separacao_leituras.peso — peso capturado na bipagem, informativo
--   (nunca substitui a quantidade conferida, que sempre vem de qtd).
-- ============================================================================

alter table app.separacao_itens add column usa_conf_peso boolean not null default false;
alter table app.separacao_sessoes add column obter_qtd_balanca text;
alter table app.separacao_leituras add column peso numeric(15, 5);

create table app.separacao_uma (
  id            uuid primary key default gen_random_uuid(),
  tenant_id     uuid not null references tenancy.tenants(id) on delete cascade,
  sessao_id     uuid not null references app.separacao_sessoes(id) on delete cascade,
  codprod       integer not null,
  coduma        integer not null,
  descricao     text,
  peso          numeric(15, 5),
  codvol        text,
  codbarra      text,
  padrao        boolean not null default false,

  unique (sessao_id, codprod, coduma)
);

create index idx_separacao_uma_tenant_sessao on app.separacao_uma (tenant_id, sessao_id);

alter table app.separacao_uma enable row level security;
alter table app.separacao_uma force row level security;

create policy tenant_isolation on app.separacao_uma
  using (tenant_id = tenancy.current_tenant_id())
  with check (tenant_id = tenancy.current_tenant_id());

grant select, insert, update, delete on app.separacao_uma to wms_app;

-- ─── Vínculo balança↔usuário (portado do legado — operador só vê as suas) ──
create table app.balanca_usuarios (
  id          uuid primary key default gen_random_uuid(),
  tenant_id   uuid not null references tenancy.tenants(id) on delete cascade,
  balanca_id  uuid not null references app.balancas(id) on delete cascade,
  usuario_id  uuid not null references app.users(id) on delete cascade,

  unique (balanca_id, usuario_id)
);

create index idx_balanca_usuarios_tenant on app.balanca_usuarios (tenant_id);

alter table app.balanca_usuarios enable row level security;
alter table app.balanca_usuarios force row level security;

create policy tenant_isolation on app.balanca_usuarios
  using (tenant_id = tenancy.current_tenant_id())
  with check (tenant_id = tenancy.current_tenant_id());

grant select, insert, update, delete on app.balanca_usuarios to wms_app;
