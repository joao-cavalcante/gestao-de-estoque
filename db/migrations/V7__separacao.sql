-- ============================================================================
-- V7 — módulo de Separação.
--
-- Problema real que isto resolve (relato do usuário): no projeto base, abrir
-- a tela de separação dispara de 8 a 11 chamadas ao Sankhya, várias
-- encadeadas (uma espera a outra), o que leva vários segundos. Aqui:
--
-- 1. app.separacao_sessoes  — sessão local por (tenant_id, nunota), criada
--    na hora (sem esperar o Sankhya), com um snapshot do status da tarefa
--    no momento da criação — é o que permite detectar depois que a
--    conferência foi excluída/cancelada no Sankhya enquanto a sessão local
--    ainda estava aberta (a preocupação do usuário).
-- 2. app.separacao_itens    — itens carregados em BACKGROUND (coroutine
--    separada, não bloqueia a resposta do "iniciar").
-- 3. app.config_conferencia_cache — cache com TTL de ConfiguracaoConferencia
--    por NUCCO — evita reconsultar o Sankhya (BUSCARCODBARRAPOR,
--    FORMACAOVOLUMES) a cada nota, já que essa config quase nunca muda.
--
-- Segue a mesma receita de RLS do V2/V3.
-- ============================================================================

create table app.separacao_sessoes (
  id                      uuid primary key default gen_random_uuid(),
  tenant_id               uuid not null references tenancy.tenants(id) on delete cascade,

  nunota                  integer not null,
  tarefa_id               uuid references app.tarefas(id) on delete set null,

  status                  text not null default 'carregando', -- carregando | pronta | erro | invalidada | concluida
  erro                    text,

  -- Snapshot do status_operacional da tarefa (app.tarefas) no momento em que
  -- a sessão foi criada — a validação (ver SeparacaoRepository.revalidar)
  -- compara isto contra o valor ATUAL da tarefa: se virou 'cancelado' ou a
  -- tarefa sumiu da fila, a sessão é invalidada (conferência excluída no
  -- Sankhya enquanto o operador ainda via os itens antigos aqui).
  status_operacional_snapshot text not null,

  fingerprint_itens       text, -- hash dos itens carregados (detecta divergência sem re-baixar tudo)

  criado_em               timestamptz not null default now(),
  atualizado_em           timestamptz not null default now()
);

-- Só pode existir 1 sessão ATIVA (carregando/pronta) por nota — permite
-- reabrir livremente depois de invalidada/concluída.
create unique index idx_separacao_sessao_ativa_unica
  on app.separacao_sessoes (tenant_id, nunota)
  where status in ('carregando', 'pronta');

create index idx_separacao_sessoes_tenant on app.separacao_sessoes (tenant_id);

alter table app.separacao_sessoes enable row level security;
alter table app.separacao_sessoes force row level security;

create policy tenant_isolation on app.separacao_sessoes
  using       (tenant_id = tenancy.current_tenant_id())
  with check  (tenant_id = tenancy.current_tenant_id());

grant select, insert, update, delete on app.separacao_sessoes to wms_app;

-- ============================================================================

create table app.separacao_itens (
  id                    uuid primary key default gen_random_uuid(),
  tenant_id             uuid not null references tenancy.tenants(id) on delete cascade,
  sessao_id             uuid not null references app.separacao_sessoes(id) on delete cascade,

  sequencia             integer not null,
  codprod               integer not null,
  controle              text not null default ' ',
  codvol                text,
  qtd_neg               numeric(15,5) not null,
  qtd_entregue          numeric(15,5) not null default 0,
  qtd_conferida_local   numeric(15,5) not null default 0,

  dados                 jsonb not null default '{}', -- descrição, marca, referência etc. (ver TarefaTables.kt jsonb())

  unique (sessao_id, sequencia)
);

create index idx_separacao_itens_tenant on app.separacao_itens (tenant_id);
create index idx_separacao_itens_sessao on app.separacao_itens (sessao_id);

alter table app.separacao_itens enable row level security;
alter table app.separacao_itens force row level security;

create policy tenant_isolation on app.separacao_itens
  using       (tenant_id = tenancy.current_tenant_id())
  with check  (tenant_id = tenancy.current_tenant_id());

grant select, insert, update, delete on app.separacao_itens to wms_app;

-- ============================================================================
-- Cache de ConfiguracaoConferencia por NUCCO — TTL curto (ver
-- ConfigConferenciaCache.kt), não infinito: uma mudança na config do
-- Sankhya (ex: BUSCARCODBARRAPOR) demora no máximo TTL pra refletir aqui
-- sozinha, e pode ser forçada na hora via endpoint de invalidação manual.
-- ============================================================================

create table app.config_conferencia_cache (
  tenant_id             uuid not null references tenancy.tenants(id) on delete cascade,
  nucco                 integer not null,

  buscar_codigo_barra_por text not null default 'A',
  formacao_volumes        text,

  atualizado_em         timestamptz not null default now(),
  expira_em              timestamptz not null,

  primary key (tenant_id, nucco)
);

alter table app.config_conferencia_cache enable row level security;
alter table app.config_conferencia_cache force row level security;

create policy tenant_isolation on app.config_conferencia_cache
  using       (tenant_id = tenancy.current_tenant_id())
  with check  (tenant_id = tenancy.current_tenant_id());

grant select, insert, update, delete on app.config_conferencia_cache to wms_app;
