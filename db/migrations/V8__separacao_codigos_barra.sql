-- ============================================================================
-- V8 — resolução de código de barras pra separação (VOA + BAR + EST).
--
-- Mesma lógica real do projeto base (ConferenciaHelper.carregarSessao):
-- um código de barras pode vir de 3 fontes do Sankhya, com prioridade
-- implícita na ordem de inserção — BAR (código genérico do produto),
-- VOA (código específico por unidade/lote, com fator de conversão),
-- EST (códigos vistos no estoque físico, sempre ao vivo, nunca cacheado
-- porque estoque muda o tempo todo).
--
-- Tabela por SESSÃO (não por tenant/produto) — mais simples pra essa
-- primeira versão: cada sessão de separação resolve e grava sua própria
-- cópia. Reaproveitar entre sessões da mesma nota é otimização futura.
-- ============================================================================

create table app.separacao_codigos_barra (
  id            uuid primary key default gen_random_uuid(),
  tenant_id     uuid not null references tenancy.tenants(id) on delete cascade,
  sessao_id     uuid not null references app.separacao_sessoes(id) on delete cascade,

  codigo_barra  text not null,
  codprod       integer not null,
  codvol        text,
  controle      text not null default ' ', -- ' ' = vale pra qualquer controle desse produto (BAR/EST genérico)
  origem        text not null              -- BAR | VOA | EST
);

create index idx_separacao_codigos_tenant on app.separacao_codigos_barra (tenant_id);
create index idx_separacao_codigos_sessao_codigo on app.separacao_codigos_barra (sessao_id, codigo_barra);

alter table app.separacao_codigos_barra enable row level security;
alter table app.separacao_codigos_barra force row level security;

create policy tenant_isolation on app.separacao_codigos_barra
  using       (tenant_id = tenancy.current_tenant_id())
  with check  (tenant_id = tenancy.current_tenant_id());

grant select, insert, update, delete on app.separacao_codigos_barra to wms_app;
