-- ============================================================================
-- V49 — por TOP: "usar conferência por etapa" + TIPMOV no espelho de TOPs.
--
-- Conferência de ENTRADA (compra) não usa conferência por etapa (Secos /
-- Refrigerado / Congelado) — só a de saída. O módulo conferencia_segmentada é
-- do tenant inteiro; esta configuração desliga as etapas por Tipo de Operação.
--
-- Tabela PRÓPRIA (e não coluna em app.tipos_operacao) de propósito: o espelho de
-- TOPs é apagado e regravado a cada sincronização (TipoOperacaoRepository
-- .substituirDerivado) — a escolha do usuário sumiria no ciclo seguinte.
-- Sem linha = true (comportamento de antes desta migração: tudo por etapa).
--
-- tipmov no espelho: derivado de TGFCAB.TIPMOV das notas da fila (mesma fonte
-- de CODTIPOPER/DESCROPER/NUCCO) — filtro Compras (C, O) / Vendas (V, P) na tela.
-- ============================================================================

alter table app.tipos_operacao add column tipmov text;

create table app.tipo_operacao_config (
  tenant_id              uuid not null references tenancy.tenants(id) on delete cascade,
  codtop                 integer not null,
  conferencia_por_etapa  boolean not null default true,
  atualizado_em          timestamptz not null default now(),
  primary key (tenant_id, codtop)
);

alter table app.tipo_operacao_config enable row level security;
alter table app.tipo_operacao_config force row level security;

create policy tenant_isolation on app.tipo_operacao_config
  using       (tenant_id = tenancy.current_tenant_id())
  with check  (tenant_id = tenancy.current_tenant_id());

grant select, insert, update, delete on app.tipo_operacao_config to wms_app;
