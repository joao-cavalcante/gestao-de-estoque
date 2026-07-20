-- ============================================================================
-- V3 — camada de sincronização Sankhya -> base local.
--
-- Sankhya é fonte de verdade para OS DADOS DO PEDIDO E SEU STATUS DE
-- NEGÓCIO (status_sankhya, dados). Nossa base é fonte de verdade para
-- EXECUÇÃO OPERACIONAL (status_operacional, operador/timestamps de
-- execução) — as duas coisas podem divergir e são reconciliadas pelo job
-- de sync (ver TarefaSyncService.kt), nunca direto por SQL manual.
--
-- Segue a receita de RLS do V2 (app.users): tenant_id líder, RLS+FORCE,
-- policy via tenancy.current_tenant_id(), grant só pro wms_app.
-- ============================================================================

create table app.tarefas (
  id                    uuid primary key default gen_random_uuid(),
  tenant_id             uuid not null references tenancy.tenants(id) on delete cascade,

  nunota                integer not null,
  tipo                  text not null default 'conferencia', -- conferencia | separacao_mp | alocacao | ... (módulos futuros)

  status_sankhya        text not null,        -- status cru como veio do Sankhya na última sincronização (''/AC, A, F, D, ...)
  status_operacional    text not null,        -- aguardando | andamento | concluido | cancelado — estado da EXECUÇÃO local

  dados                 jsonb not null,       -- payload bruto do loadRecords daquele ciclo (campos de negócio: parceiro, nf, data, ...)

  -- Execução local — limpos quando o Sankhya reabre a tarefa (recontagem),
  -- preservados (não apagados) quando é só um cancelamento — ver auditoria
  -- pra reconstruir o histórico em ambos os casos.
  operador_execucao     text,
  iniciado_em           timestamptz,
  concluido_em          timestamptz,

  sankhya_atualizado_em timestamptz not null, -- timestamp de alteração (DHALTER) que veio do Sankhya nessa linha
  local_atualizado_em   timestamptz not null default now(),

  pendente_write_back   boolean not null default false, -- true enquanto há ação local ainda não confirmada no Sankhya

  unique (tenant_id, nunota)
);

create index idx_tarefas_tenant on app.tarefas (tenant_id);
create index idx_tarefas_tenant_status on app.tarefas (tenant_id, status_operacional);
create index idx_tarefas_tenant_pendente on app.tarefas (tenant_id, pendente_write_back) where pendente_write_back;

alter table app.tarefas enable row level security;
alter table app.tarefas force row level security;

create policy tenant_isolation on app.tarefas
  using       (tenant_id = tenancy.current_tenant_id())
  with check  (tenant_id = tenancy.current_tenant_id());

grant select, insert, update, delete on app.tarefas to wms_app;

-- ============================================================================
-- Auditoria de reconciliação — todo status_operacional trocado PELO SYNC
-- (não por ação do operador na tela) grava uma linha aqui. É o que permite
-- investigar depois "por que essa tarefa sumiu/voltou sem eu mexer".
-- ============================================================================

create table app.tarefas_auditoria (
  id              uuid primary key default gen_random_uuid(),
  tenant_id       uuid not null references tenancy.tenants(id) on delete cascade,
  nunota          integer not null,

  status_anterior text not null,
  status_novo     text not null,
  origem          text not null,   -- sync_sankhya | operador
  motivo          text not null,

  criado_em       timestamptz not null default now()
);

create index idx_tarefas_auditoria_tenant_nunota on app.tarefas_auditoria (tenant_id, nunota, criado_em desc);

alter table app.tarefas_auditoria enable row level security;
alter table app.tarefas_auditoria force row level security;

create policy tenant_isolation on app.tarefas_auditoria
  using       (tenant_id = tenancy.current_tenant_id())
  with check  (tenant_id = tenancy.current_tenant_id());

grant select, insert on app.tarefas_auditoria to wms_app;

-- ============================================================================
-- Estado do job de sync por tenant — watermark incremental (DHALTER) +
-- intervalo configurável + último erro (pra resiliência: ciclo falho não
-- avança o watermark, e não derruba a API, só fica registrado aqui).
-- ============================================================================

create table app.sync_estado (
  tenant_id               uuid primary key references tenancy.tenants(id) on delete cascade,

  intervalo_segundos      integer not null default 60,
  ultimo_sync_em          timestamptz,        -- toda tentativa (sucesso ou não)
  ultimo_sync_sucesso_em  timestamptz,        -- só sucesso — é o watermark usado no filtro DHALTER >= X do próximo ciclo
  ultimo_erro             text
);

alter table app.sync_estado enable row level security;
alter table app.sync_estado force row level security;

create policy tenant_isolation on app.sync_estado
  using       (tenant_id = tenancy.current_tenant_id())
  with check  (tenant_id = tenancy.current_tenant_id());

grant select, insert, update on app.sync_estado to wms_app;
