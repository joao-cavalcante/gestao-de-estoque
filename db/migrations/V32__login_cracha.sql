-- ============================================================================
-- V32 — login por crachá (código de barras) nas estações de pesagem.
--
-- 1) app.users ganha crachao_codigo (nullable — nem todo usuário tem
--    crachá). Único POR TENANT (índice parcial, ignora null) — diferente do
--    e-mail (globalmente único, V5), o código do crachá não precisa ser
--    único entre tenants diferentes, só dentro de quem o emitiu.
--
--    Por isso o login por crachá NÃO usa um lookup central tipo
--    tenancy.user_login_lookup: o código sozinho não identifica o tenant
--    sem risco de colisão entre clientes. A estação já sabe o tenant (slug
--    fica salvo no navegador desde o primeiro login normal feito ali —
--    mesma config local que fixa a balança da estação, ver
--    frontend/estacao.service.ts) e manda junto, igual ao padrão já usado
--    em /api/separacao/* (?tenant=slug — ver SeparacaoRoutes.kt); o backend
--    resolve o tenant pelo slug e só então consulta app.users com
--    tenant_id + crachao_codigo, dentro do contexto de RLS normal.
--
-- 2) app.balanca_sessoes_ativas: "quem está logado agora nesta estação"
--    (1 balança = no máximo 1 operador ativo por vez, sobrescrito a cada
--    novo crachá lido). Tabela NOVA e separada de app.balanca_usuarios de
--    propósito — balanca_usuarios é a lista de autorização (N:N, mantida
--    pelo admin na tela de Balanças) e não deve ser mexida por um login de
--    operador, ou a autorização configurada se perderia a cada troca de
--    crachá.
-- ============================================================================

alter table app.users add column crachao_codigo text;

create unique index idx_users_tenant_cracha
  on app.users (tenant_id, crachao_codigo)
  where crachao_codigo is not null;

create table app.balanca_sessoes_ativas (
  balanca_id    uuid primary key references app.balancas(id) on delete cascade,
  tenant_id     uuid not null references tenancy.tenants(id) on delete cascade,
  usuario_id    uuid not null references app.users(id) on delete cascade,
  atualizado_em timestamptz not null default now()
);

create index idx_balanca_sessoes_ativas_tenant on app.balanca_sessoes_ativas (tenant_id);

alter table app.balanca_sessoes_ativas enable row level security;
alter table app.balanca_sessoes_ativas force row level security;

create policy tenant_isolation on app.balanca_sessoes_ativas
  using       (tenant_id = tenancy.current_tenant_id())
  with check  (tenant_id = tenancy.current_tenant_id());

grant select, insert, update, delete on app.balanca_sessoes_ativas to wms_app;
