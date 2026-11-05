-- ============================================================================
-- V70 — Opção da Torre por Configuração de Conferência (NUCCO): pedir a quantidade na unidade do pedido
-- (CX, UN, PC) no pop-up de pesagem.
--
-- Desde a V61 o pop-up de pesagem exige "Quantidade conferida" na unidade do pedido quando o pedido do pesável
-- não é em KG (registro/etiqueta — o Sankhya recebe só o peso). Na COMPRA o cliente não quer esse campo
-- (05/11/2026): vira opção por NUCCO, padrão = pede (regra de antes).
--
-- Tabela PRÓPRIA do WMS (o Sankhya não tem esse conceito e a sync regravaria o espelho da TGFCCO) e separada de
-- app.conferencia_tolerancia: lá a existência da linha significa "tolerância configurada".
--
-- Cópia na sessão (separacao_sessoes.pedir_qtd_comercial), como a tolerância: vale o que estava configurado
-- quando a conferência abriu. Sessões existentes = pede (DEFAULT true).
--
-- Negri: NUCCO 6 (CONFERÊNCIA DE ENTRADA — compra) não pede.
-- ============================================================================

create table app.conferencia_opcoes (
  tenant_id            uuid not null references tenancy.tenants(id) on delete cascade,
  nucco                integer not null,
  pedir_qtd_comercial  boolean not null default true,
  atualizado_em        timestamptz not null default now(),
  primary key (tenant_id, nucco)
);

alter table app.conferencia_opcoes enable row level security;
alter table app.conferencia_opcoes force row level security;
create policy tenant_isolation on app.conferencia_opcoes
  using       (tenant_id = tenancy.current_tenant_id())
  with check  (tenant_id = tenancy.current_tenant_id());
grant select, insert, update, delete on app.conferencia_opcoes to wms_app;

alter table app.separacao_sessoes add column pedir_qtd_comercial boolean not null default true;

insert into app.conferencia_opcoes (tenant_id, nucco, pedir_qtd_comercial)
select t.id, 6, false from tenancy.tenants t where t.slug = 'negri'
on conflict (tenant_id, nucco) do nothing;
