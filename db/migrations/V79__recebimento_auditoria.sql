-- ============================================================================
-- V79 — Auditoria de Recebimento (25/11/2026).
--
-- Histórico próprio das compras conferidas pela Torre: a lista de tarefas perde a compra quando ela sai da fila e a
-- sessão de conferência não guarda o tipo da nota. Uma linha por compra (a conferência mais recente), gravada pelo
-- ciclo de sincronização a partir das compras conferidas da Entrada; o mesmo ciclo relê no Sankhya só as que ainda
-- não fecharam (confirmação e quem confirmou — TORREC do addon —, devolução, complemento) e marca [encerrado] quando
-- não há mais nada pendente. A tela lê só daqui: qualquer data, sem consultar o Sankhya.
-- ============================================================================

create table app.recebimento_auditoria (
  tenant_id               uuid not null references tenancy.tenants(id) on delete cascade,
  nunota                  integer not null,
  nuconf                  integer,
  numero_nota             bigint,
  fornecedor              text,
  empresa                 text,
  tipo_operacao           text,
  data_movimento          text,
  conferente              text,
  conferencia_inicio      text,
  conferencia_fim         text,
  status_nota             text,
  confirmado_codusu       integer,
  confirmado_nome         text,
  confirmado_origem       text,          -- T = Aprovar da Torre; S = direto no Sankhya
  confirmado_em           text,
  registrado_addon        boolean not null default false,
  devolucao_nunota        bigint,
  devolucao_numero        bigint,
  devolucao_confirmada    boolean,
  complemento_nunota      bigint,
  complemento_numero      bigint,
  complemento_confirmado  boolean,
  encerrado               boolean not null default false,
  lido_em                 timestamptz,
  criado_em               timestamptz not null default now(),
  atualizado_em           timestamptz not null default now(),
  primary key (tenant_id, nunota)
);

create index recebimento_auditoria_abertos on app.recebimento_auditoria (tenant_id) where not encerrado;

alter table app.recebimento_auditoria enable row level security;
alter table app.recebimento_auditoria force row level security;
create policy tenant_isolation on app.recebimento_auditoria
  using       (tenant_id = tenancy.current_tenant_id())
  with check  (tenant_id = tenancy.current_tenant_id());
grant select, insert, update, delete on app.recebimento_auditoria to wms_app;
