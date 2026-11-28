-- ============================================================================
-- V85 — Recebimento na doca, etapa D3 (28/11/2026): MD-e e recusa da carga. Os dois nascem DESLIGADOS
-- (módulos mde_confirmacao_recebimento e recusa_carga no Painel Master): MD-e é ato fiscal (evento na Sefaz em
-- nome do cliente, sem volta) e a recusa mexe com devolução — decisão do cliente.
-- Uma linha por compra: a recusa (total/parcial, motivo, itens) e o último evento de MD-e enviado pela Torre.
-- ============================================================================

create table app.recebimento_decisoes (
  tenant_id       uuid not null references tenancy.tenants(id) on delete cascade,
  nunota          integer not null,
  recusa_tipo     text check (recusa_tipo in ('TOTAL', 'PARCIAL')),
  recusa_motivo   text,
  recusa_itens    text,
  recusa_em       timestamptz,
  recusa_por      text,
  mde_evento      text,
  mde_em          timestamptz,
  mde_situacao    text check (mde_situacao in ('OK', 'ERRO')),
  mde_mensagem    text,
  primary key (tenant_id, nunota)
);

alter table app.recebimento_decisoes enable row level security;
alter table app.recebimento_decisoes force row level security;
create policy tenant_isolation on app.recebimento_decisoes
  using       (tenant_id = tenancy.current_tenant_id())
  with check  (tenant_id = tenancy.current_tenant_id());
grant select, insert, update, delete on app.recebimento_decisoes to wms_app;
