-- ============================================================================
-- V81 — Paletes do recebimento (27/11/2026, endereçamento etapa 4a: armazenagem por palete / LPN).
--
-- Decisões do usuário (27/11/2026): o palete é montado NA CONFERÊNCIA ("Fechar palete": tudo o que foi lido desde o
-- último fechamento vira um palete e a etiqueta sai na hora); palete NORMAL (um produto) e MISTO (vários produtos),
-- misto só com o parâmetro do cliente ligado (Negri: desligado); etiqueta no padrão das outras (100 x 100 mm).
-- Espelha o WMS nativo do Sankhya (TGWRPL registro de pálete, TGWRUMA código da etiqueta/UMA, TGWEPL estoque por
-- pálete): numero ~ IDPALETE, uma ~ TGWRUMA.UMA, seq ~ SEQPALETE, impresso ~ IMPRESSO.
--
-- Quantidade na unidade PADRÃO (a mesma das leituras e do Sankhya); qtd_comercial/unidade_comercial para a etiqueta.
-- cancelado_em: o palete deixa de valer quando a leitura de um produto dele é desfeita ou a conferência recomeça
-- (o que estava nele volta a ficar "sem palete").
-- ============================================================================

create sequence app.palete_numero_seq;
grant usage, select on sequence app.palete_numero_seq to wms_app;

create table app.recebimento_paletes (
  id                 uuid primary key,
  tenant_id          uuid not null references tenancy.tenants(id) on delete cascade,
  sessao_id          uuid not null references app.separacao_sessoes(id) on delete cascade,
  nunota             integer not null,
  numero             bigint not null default nextval('app.palete_numero_seq'),
  uma                text not null,
  seq                integer not null,
  tipo               text not null check (tipo in ('NORMAL', 'MISTO')),
  impresso           boolean not null default false,
  criado_por_nome    text,
  criado_em          timestamptz not null default now(),
  cancelado_em       timestamptz
);

create unique index recebimento_paletes_numero on app.recebimento_paletes (numero);
create unique index recebimento_paletes_uma on app.recebimento_paletes (uma);
create index recebimento_paletes_sessao on app.recebimento_paletes (tenant_id, sessao_id);
create index recebimento_paletes_nunota on app.recebimento_paletes (tenant_id, nunota);

create table app.recebimento_palete_itens (
  id                 uuid primary key,
  tenant_id          uuid not null references tenancy.tenants(id) on delete cascade,
  palete_id          uuid not null references app.recebimento_paletes(id) on delete cascade,
  codprod            integer not null,
  controle           text not null default ' ',
  descricao          text,
  qtd                numeric(15,5) not null,
  unidade            text,
  qtd_comercial      numeric(15,5),
  unidade_comercial  text
);

create index recebimento_palete_itens_palete on app.recebimento_palete_itens (tenant_id, palete_id);

alter table app.recebimento_paletes enable row level security;
alter table app.recebimento_paletes force row level security;
create policy tenant_isolation on app.recebimento_paletes
  using       (tenant_id = tenancy.current_tenant_id())
  with check  (tenant_id = tenancy.current_tenant_id());
grant select, insert, update, delete on app.recebimento_paletes to wms_app;

alter table app.recebimento_palete_itens enable row level security;
alter table app.recebimento_palete_itens force row level security;
create policy tenant_isolation on app.recebimento_palete_itens
  using       (tenant_id = tenancy.current_tenant_id())
  with check  (tenant_id = tenancy.current_tenant_id());
grant select, insert, update, delete on app.recebimento_palete_itens to wms_app;
