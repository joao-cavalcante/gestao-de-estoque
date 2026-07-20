-- ============================================================================
-- V15 — Espelho local da TGFCCO (Configuração de Conferência) do Sankhya.
--
-- Hoje toda conferência aberta consulta a TGFCCO ao vivo (ver
-- ConfigConferenciaCache, que já cacheia 2 dos 55 campos nativos por 15min).
-- Esta tabela guarda os 55 campos completos, sincronizados em background
-- (ConfigConferenciaSyncWorker), pra uma tela de administração/radar de gap
-- e, no futuro (Fase 2, fora desta migration), permitir editar e escrever
-- de volta no Sankhya.
--
-- `campos` é jsonb (não uma coluna por campo) DE PROPÓSITO — a Sankhya pode
-- adicionar/remover campos na TGFCCO entre versões, e um schema rígido
-- quebraria a cada atualização. O catálogo de quais chaves existem dentro
-- de `campos` (rótulo, aba, regras de dependência) vive no frontend, não
-- aqui — ver comentário em ConfigConferenciaSyncService.kt.
--
-- Mesma receita de RLS do V12/V14.
-- ============================================================================

create table app.config_conferencia (
  id                     uuid primary key default gen_random_uuid(),
  tenant_id              uuid not null references tenancy.tenants(id) on delete cascade,

  nucco                  integer not null,
  descricao              text not null,
  campos                 jsonb not null,

  sankhya_atualizado_em  timestamptz,
  local_atualizado_em    timestamptz not null default now(),

  unique (tenant_id, nucco)
);

create index idx_config_conferencia_tenant on app.config_conferencia (tenant_id);

alter table app.config_conferencia enable row level security;
alter table app.config_conferencia force row level security;

create policy tenant_isolation on app.config_conferencia
  using       (tenant_id = tenancy.current_tenant_id())
  with check  (tenant_id = tenancy.current_tenant_id());

grant select, insert, update, delete on app.config_conferencia to wms_app;
