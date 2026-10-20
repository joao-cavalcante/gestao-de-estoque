-- ============================================================================
-- V59 — Impressão automática da OC fechada: romaneio + DANFE de cada nota.
--
-- Um job por impressão (o Fechar OC cria um; "reimprimir" cria outro). Ciclo:
--   aguardando_sefaz -> o worker espera todas as notas da OC autorizadas (STATUSNFE 'A'),
--                       monta o PDF (XML de TGFNFE.XMLENVCLI) e grava em `pdf`
--   pronto           -> o agente local (o mesmo da balança) pega o job
--   imprimindo       -> entregue a um agente; volta a 'pronto' se ele não confirmar em 5 min
--   impresso / erro  -> fim (erro: SEFAZ não autorizou a tempo, falha ao montar ou na impressora)
-- ============================================================================

create table app.impressao_oc (
  id              uuid primary key default gen_random_uuid(),
  tenant_id       uuid not null references tenancy.tenants(id) on delete cascade,
  ordem_carga     bigint not null,
  status          text not null default 'aguardando_sefaz',
  motivo          text not null default 'fechamento',
  notas           integer not null default 0,
  pdf             bytea,
  erro            text,
  tentativas      integer not null default 0,
  agente          text,
  solicitado_por  text,
  criado_em       timestamptz not null default now(),
  atualizado_em   timestamptz not null default now(),
  entregue_em     timestamptz,
  impresso_em     timestamptz,
  constraint impressao_oc_status_ck check (status in ('aguardando_sefaz', 'pronto', 'imprimindo', 'impresso', 'erro'))
);

create index idx_impressao_oc_fila on app.impressao_oc (tenant_id, status, criado_em);
create index idx_impressao_oc_oc on app.impressao_oc (tenant_id, ordem_carga, criado_em desc);

alter table app.impressao_oc enable row level security;
alter table app.impressao_oc force row level security;
create policy tenant_isolation on app.impressao_oc
  using       (tenant_id = tenancy.current_tenant_id())
  with check  (tenant_id = tenancy.current_tenant_id());

grant select, insert, update, delete on app.impressao_oc to wms_app;
