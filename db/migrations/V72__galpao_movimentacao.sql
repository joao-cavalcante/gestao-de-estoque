-- ============================================================================
-- V72 — Planta do Galpão, fase 4: movimentação por endereço.
--
-- 1. app.galpao_movimentos — histórico (ledger) de toda mudança de saldo físico
--    ou de bloqueio de endereço. Só INSERT: trigger recusa UPDATE/DELETE.
--    comando_id = UUID gerado pela tela; reenviar o mesmo comando não repete o
--    movimento (unique por tenant).
-- 2. app.galpao_contagens  — contagem informada no endereço. Não ajusta saldo
--    sozinha: fica PENDENTE até um ADMINISTRADOR aprovar (vira movimento
--    AJUSTE_CONTAGEM) ou descartar.
--
-- O saldo físico continua em app.galpao_saldos_endereco (V69): versao começa
-- em 1 na primeira gravação e sobe a cada mudança (linha ausente = versão 0).
-- Nada aqui grava no Sankhya: TGFEST é só lido pra limitar o endereçamento e
-- pra conciliação.
-- ============================================================================

create table app.galpao_movimentos (
  id                   uuid primary key default gen_random_uuid(),
  tenant_id            uuid not null references tenancy.tenants(id) on delete cascade,
  planta_id            uuid not null references app.galpao_plantas(id) on delete cascade,
  comando_id           uuid not null,

  tipo                 text not null check (tipo in ('ENDERECAR', 'TRANSFERIR', 'RETIRAR', 'AJUSTE_CONTAGEM', 'BLOQUEAR', 'DESBLOQUEAR')),
  endereco_origem_id   uuid references app.galpao_enderecos(id),
  endereco_destino_id  uuid references app.galpao_enderecos(id),
  codprod              integer,
  controle             text not null default '',
  quantidade           numeric(15, 5) not null default 0,
  motivo               text,
  contagem_id          uuid,

  usuario_id           uuid not null,
  usuario_nome         text not null,
  criado_em            timestamptz not null default now(),

  unique (tenant_id, comando_id)
);

create index idx_galpao_mov_tenant on app.galpao_movimentos (tenant_id);
create index idx_galpao_mov_planta_data on app.galpao_movimentos (planta_id, criado_em desc);
create index idx_galpao_mov_produto on app.galpao_movimentos (planta_id, codprod);

create or replace function app.galpao_movimentos_imutavel() returns trigger language plpgsql as $$
begin
  raise exception 'app.galpao_movimentos é somente inserção (histórico de movimentação)';
end;
$$;

create trigger trg_galpao_movimentos_imutavel
  before update or delete on app.galpao_movimentos
  for each row execute function app.galpao_movimentos_imutavel();

alter table app.galpao_movimentos enable row level security;
alter table app.galpao_movimentos force row level security;

create policy tenant_isolation on app.galpao_movimentos
  using       (tenant_id = tenancy.current_tenant_id())
  with check  (tenant_id = tenancy.current_tenant_id());

grant select, insert on app.galpao_movimentos to wms_app;

-- ============================================================================

create table app.galpao_contagens (
  id                   uuid primary key default gen_random_uuid(),
  tenant_id            uuid not null references tenancy.tenants(id) on delete cascade,
  planta_id            uuid not null references app.galpao_plantas(id) on delete cascade,
  endereco_id          uuid not null references app.galpao_enderecos(id),
  comando_id           uuid not null,

  codprod              integer not null,
  controle             text not null default '',
  quantidade_contada   numeric(15, 5) not null check (quantidade_contada >= 0),
  -- Saldo físico e versão no momento da contagem: aprovar exige que não tenham mudado.
  quantidade_sistema   numeric(15, 5) not null,
  versao_saldo         integer not null,
  motivo               text,

  status               text not null default 'PENDENTE' check (status in ('PENDENTE', 'APROVADA', 'DESCARTADA')),
  contado_por_nome     text not null,
  contado_em           timestamptz not null default now(),
  decidido_por_nome    text,
  decidido_em          timestamptz,
  observacao_decisao   text,

  unique (tenant_id, comando_id)
);

create index idx_galpao_contagens_tenant on app.galpao_contagens (tenant_id);
create index idx_galpao_contagens_planta on app.galpao_contagens (planta_id, status);

alter table app.galpao_contagens enable row level security;
alter table app.galpao_contagens force row level security;

create policy tenant_isolation on app.galpao_contagens
  using       (tenant_id = tenancy.current_tenant_id())
  with check  (tenant_id = tenancy.current_tenant_id());

grant select, insert, update on app.galpao_contagens to wms_app;
