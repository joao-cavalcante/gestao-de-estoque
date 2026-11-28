-- ============================================================================
-- V82 — Recebimento na doca, etapa D1 (27/11/2026). Desenho: sankhya-hub docs/projetos/torre-recebimento-doca.md.
--
-- app.recebimento_chegadas: chegada do caminhão por compra (hora, placa) + checklist OPCIONAL (decisão do usuário:
-- "lacre implementar, mas não obrigatório"): lacre, baú, temperatura do baú. liberado_em = motorista liberado
-- (comprovante). Base dos indicadores (chegada → liberação → armazenado).
--
-- Paletes (V81) ganham lote/validade por item (Tempo 2 — "lote pendente" até preencher), origem do lote (de onde
-- veio: GS1 lido, XML da nota, digitado, automático) e avaria por item (quantidade + motivo). Fica só na Torre nesta
-- etapa; o lote vai ao Sankhya na D3 (conferência com EXPLODIRLOTE + CACSP.salvarInformacoesLote).
-- ============================================================================

create table app.recebimento_chegadas (
  tenant_id          uuid not null references tenancy.tenants(id) on delete cascade,
  nunota             integer not null,
  chegada_em         timestamptz not null default now(),
  placa              text,
  lacre              text check (lacre in ('OK', 'VIOLADO', 'SEM_LACRE')),
  bau                text check (bau in ('OK', 'SUJO', 'AVARIADO')),
  temperatura_bau    numeric(6,2),
  observacao         text,
  registrado_por     text,
  liberado_em        timestamptz,
  liberado_por       text,
  primary key (tenant_id, nunota)
);

alter table app.recebimento_chegadas enable row level security;
alter table app.recebimento_chegadas force row level security;
create policy tenant_isolation on app.recebimento_chegadas
  using       (tenant_id = tenancy.current_tenant_id())
  with check  (tenant_id = tenancy.current_tenant_id());
grant select, insert, update, delete on app.recebimento_chegadas to wms_app;

alter table app.recebimento_palete_itens
  add column lote            text,
  add column validade        date,
  add column fabricacao      date,
  add column origem_lote     text check (origem_lote in ('GS1', 'XML', 'DIGITADO', 'AUTOMATICO', 'ANTERIOR')),
  add column qtd_avaria      numeric(15,5),
  add column motivo_avaria   text;

alter table app.recebimento_paletes
  add column temperatura     numeric(6,2);
