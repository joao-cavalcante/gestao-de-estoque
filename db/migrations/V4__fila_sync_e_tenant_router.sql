-- ============================================================================
-- V4 — correção de arquitetura pós-diagnóstico de escala (100 tenants
-- reais, execução simultânea): duas mudanças de fundo.
--
-- 1) app.sync_estado estava no lugar ERRADO. RLS nela é fail-closed sem
--    SET LOCAL app.tenant_id — mas um worker pool de sync PRECISA varrer o
--    estado de TODOS os tenants pra decidir qual reivindicar (não dá pra
--    "logar como um tenant" antes de saber qual é o próximo). sync_estado é
--    dado de ORQUESTRAÇÃO (plano de controle, cross-tenant por natureza),
--    não dado de negócio de um tenant — mesma categoria de
--    tenancy.tenants/erp_connections, não de app.tarefas. Move pra
--    tenancy.sync_estado, sem RLS.
--
-- 2) Vira de fato uma fila: proximo_run_em (quando reivindicar de novo),
--    falhas_consecutivas + bloqueado_ate (circuit breaker com backoff
--    exponencial por tenant) — substituem o modelo de "1 corrotina por
--    tenant vivendo pra sempre na memória do processo" (SyncWorkerPool
--    reivindica via SELECT ... FOR UPDATE SKIP LOCKED).
-- ============================================================================

drop policy if exists tenant_isolation on app.sync_estado;
alter table app.sync_estado set schema tenancy;
alter table tenancy.sync_estado disable row level security;

alter table tenancy.sync_estado
  add column proximo_run_em      timestamptz not null default now(),
  add column falhas_consecutivas integer     not null default 0,
  add column bloqueado_ate       timestamptz;

-- Índice líder pra query de reivindicação do worker pool (WHERE proximo_run_em <= now() ORDER BY proximo_run_em).
create index idx_sync_estado_proximo_run on tenancy.sync_estado (proximo_run_em);

-- app.sync_estado (dono wms_owner) não é mais usado — grant explícito pro
-- schema/tabela novos, já que grants não migram automaticamente com SET SCHEMA.
grant select, insert, update on tenancy.sync_estado to wms_app;
