-- ============================================================================
-- V75 — Planta do Galpão, alertas (AD_PL3D_ALERTA do Negri Planta Log 3D).
--
-- Gerados a cada nova fotografia analítica da planta (Sankhya só leitura +
-- saldos dos endereços). Deduplicados por (planta, chave = código + objeto):
-- enquanto o alerta não está RESOLVIDO, a mesma ocorrência não cria outro;
-- resolvido e a condição continua, ele volta a ABERTO (igual ao original).
-- Tratamento: ABERTO → RECONHECIDO → RESOLVIDO, com responsável, SLA em horas
-- e versão (quem salva sobre uma versão velha recebe conflito).
-- ============================================================================

create table app.galpao_alertas (
  id              uuid primary key default gen_random_uuid(),
  tenant_id       uuid not null references tenancy.tenants(id) on delete cascade,
  planta_id       uuid not null references app.galpao_plantas(id) on delete cascade,

  chave           text not null,
  codigo          text not null,
  severidade      text not null check (severidade in ('CRITICO', 'ATENCAO', 'INFO')),
  entidade        text not null check (entidade in ('PRODUTO', 'ESTOQUE', 'ENDERECO', 'CONFERENCIA')),
  mensagem        text not null,
  evidencia       jsonb not null default '{}'::jsonb,
  codprod         integer,
  controle        text,
  endereco_id     uuid references app.galpao_enderecos(id) on delete set null,

  status          text not null default 'ABERTO' check (status in ('ABERTO', 'RECONHECIDO', 'RESOLVIDO')),
  atribuido       text,
  sla_horas       integer not null default 24,
  versao          integer not null default 1,

  criado_em       timestamptz not null default now(),
  atualizado_em   timestamptz not null default now(),
  -- Última vez que a geração encontrou a condição (some da fotografia = ocorrência não se repete mais).
  visto_em        timestamptz not null default now(),
  reconhecido_em  timestamptz,
  resolvido_em    timestamptz,
  tratado_por     text,

  unique (planta_id, chave)
);

create index idx_galpao_alertas_tenant on app.galpao_alertas (tenant_id);
create index idx_galpao_alertas_planta_status on app.galpao_alertas (planta_id, status);
create index idx_galpao_alertas_produto on app.galpao_alertas (planta_id, codprod);

alter table app.galpao_alertas enable row level security;
alter table app.galpao_alertas force row level security;

create policy tenant_isolation on app.galpao_alertas
  using       (tenant_id = tenancy.current_tenant_id())
  with check  (tenant_id = tenancy.current_tenant_id());

grant select, insert, update on app.galpao_alertas to wms_app;
