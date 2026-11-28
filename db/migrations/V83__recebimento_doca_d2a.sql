-- ============================================================================
-- V83 — Recebimento na doca, etapa D2a (27/11/2026). Desenho: sankhya-hub docs/projetos/torre-recebimento-doca.md.
--
-- app.recebimento_fotos: foto da avaria (e, depois, canhoto/lacre) tirada no coletor — JPEG reduzido no aparelho
-- (~100 KB). Sem armazenamento de arquivos na Torre até aqui; o volume é pequeno (só exceção).
-- app.produto_logistica: norma de paletização informada na 1ª entrada do produto (caixas por camada × camadas) —
-- o WMS nativo do Sankhya guarda em TGWLCE / TGFPRO.LASTRO/CAMADAS (vazios na Negri); o envio ao Sankhya é
-- etapa futura (com ok), por isso fica a data de envio.
-- Paletes: amostragem de embalagem (abre uma caixa: unidades contadas × esperado pelo cadastro).
-- ============================================================================

create table app.recebimento_fotos (
  id            uuid primary key,
  tenant_id     uuid not null references tenancy.tenants(id) on delete cascade,
  nunota        integer not null,
  palete_id     uuid references app.recebimento_paletes(id) on delete set null,
  codprod       integer,
  tipo          text not null check (tipo in ('AVARIA', 'CANHOTO', 'LACRE', 'OUTRO')),
  mime          text not null,
  conteudo      bytea not null,
  criado_por    text,
  criado_em     timestamptz not null default now()
);
create index recebimento_fotos_nunota on app.recebimento_fotos (tenant_id, nunota);

alter table app.recebimento_fotos enable row level security;
alter table app.recebimento_fotos force row level security;
create policy tenant_isolation on app.recebimento_fotos
  using       (tenant_id = tenancy.current_tenant_id())
  with check  (tenant_id = tenancy.current_tenant_id());
grant select, insert, update, delete on app.recebimento_fotos to wms_app;

create table app.produto_logistica (
  tenant_id          uuid not null references tenancy.tenants(id) on delete cascade,
  codprod            integer not null,
  lastro             integer not null check (lastro > 0),
  camadas            integer not null check (camadas > 0),
  informado_por      text,
  informado_em       timestamptz not null default now(),
  enviado_sankhya_em timestamptz,
  primary key (tenant_id, codprod)
);

alter table app.produto_logistica enable row level security;
alter table app.produto_logistica force row level security;
create policy tenant_isolation on app.produto_logistica
  using       (tenant_id = tenancy.current_tenant_id())
  with check  (tenant_id = tenancy.current_tenant_id());
grant select, insert, update, delete on app.produto_logistica to wms_app;

alter table app.recebimento_palete_itens
  add column amostra_unidades  numeric(15,5),
  add column amostra_esperado  numeric(15,5);
