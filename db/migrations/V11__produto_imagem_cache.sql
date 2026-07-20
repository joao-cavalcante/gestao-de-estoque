-- ============================================================================
-- V11 — cache de imagens de produto (TGFPRO.IMAGEM é BLOB, caro de buscar
-- toda hora — mesma ideia do `produtoCache` do projeto base, só que aqui é
-- LAZY: busca do Sankhya na primeira vez que o produto é identificado numa
-- separação, não um cron pré-sincronizando o catálogo inteiro).
--
-- Presença da linha = "já verificamos esse produto". `imagem IS NULL` =
-- verificamos e ele não tem imagem cadastrada (não tenta de novo à toa).
-- ============================================================================

create table app.produto_imagem_cache (
  tenant_id     uuid not null references tenancy.tenants(id) on delete cascade,
  codprod       integer not null,
  imagem        text, -- data URI (data:image/png;base64,...) ou null = sem imagem
  atualizado_em timestamptz not null default now(),

  primary key (tenant_id, codprod)
);

alter table app.produto_imagem_cache enable row level security;
alter table app.produto_imagem_cache force row level security;

create policy tenant_isolation on app.produto_imagem_cache
  using       (tenant_id = tenancy.current_tenant_id())
  with check  (tenant_id = tenancy.current_tenant_id());

grant select, insert, update, delete on app.produto_imagem_cache to wms_app;
