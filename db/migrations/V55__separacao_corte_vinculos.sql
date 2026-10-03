-- ============================================================================
-- V55 — vínculo liberação de corte -> produto (TSILIB evento 64, TABELA TGFCOI2).
--
-- O Sankhya não guarda em lugar nenhum qual produto cada linha da TSILIB
-- representa: a liberação só tem NUCHAVE (NUCONF) + SEQUENCIA, e o produto
-- aparece apenas no texto da OBSERVACAO. Quando precisa saber, ele RECALCULA a
-- SEQUENCIA (ConferenciaHelper.ajustarDemanda) e reencontra a liberação pelo
-- número — mesma conta, mesmos dados, mesmo número.
--
-- O WMS faz a mesma conta logo depois do ConferenciaSP.cortar (único momento
-- em que TGFCOI2/TGFITE estão como o Sankhya viu ao numerar — depois o corte
-- muda QTDNEG/remove itens) e GRAVA o resultado aqui. Ver
-- wms.backend.liberacaocorte.VinculoCorte para a regra e as validações.
--
-- Uma linha por (tenant, nuconf, sequencia); a primeira gravação vale (o
-- recálculo posterior só acrescenta SEQUENCIAs novas, nunca reescreve).
-- ============================================================================

create table app.separacao_corte_vinculos (
  tenant_id     uuid not null references tenancy.tenants(id) on delete cascade,
  nuconf        integer not null,
  sequencia     integer not null,
  nunota        integer not null,
  codprod       integer not null,
  controle      text not null default ' ',
  calculado_em  timestamptz not null default now(),
  primary key (tenant_id, nuconf, sequencia)
);

create index idx_separacao_corte_vinculos_nunota on app.separacao_corte_vinculos (tenant_id, nunota);

alter table app.separacao_corte_vinculos enable row level security;
alter table app.separacao_corte_vinculos force row level security;

create policy tenant_isolation on app.separacao_corte_vinculos
  using       (tenant_id = tenancy.current_tenant_id())
  with check  (tenant_id = tenancy.current_tenant_id());

grant select, insert, update, delete on app.separacao_corte_vinculos to wms_app;
