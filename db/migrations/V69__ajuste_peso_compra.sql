-- ============================================================================
-- V69 — Ajuste de peso na compra (nota de ajuste de entrada/saída pela Torre de Operação).
--
-- Conferência de compra com parametros.ajustePesoCompra.habilitado: pesável com diferença dentro da tolerância do
-- NUCCO vai pro Sankhya com o PESO DA NOTA (a nota do fornecedor fica igual ao XML) e a diferença da balança é
-- gravada aqui. Depois que a nota do fornecedor é confirmada, o ciclo de sincronização gera UMA nota de ajuste por
-- sentido (entrada = a maior, saída = a menor) e confirma.
--
-- 1. app.compra_ajuste_peso_itens — diferença por produto+lote da sessão (sinal: + a maior, - a menor).
-- 2. app.compra_ajuste_peso_notas — documento de ajuste por sessão e sentido (máquina de estados; unique impede
--    gerar duas vezes o mesmo ajuste).
--
-- Negri: TOPs 1495 (ENTRADA - AJUSTE DE ESTOQUE) e 1195 (SAÍDA - AJUSTE DE ESTOQUE) — as mesmas do
-- ajuste manual de estoque, sem NF-e nem financeiro. Nasce DESLIGADO: liga pelo Painel Master depois do teste.
-- ============================================================================

create table app.compra_ajuste_peso_itens (
  tenant_id     uuid not null references tenancy.tenants(id) on delete cascade,
  sessao_id     uuid not null,
  nunota        integer not null,
  nuconf        integer not null,
  codprod       integer not null,
  controle      text not null default '',
  qtd_nota      numeric(20, 10) not null,
  qtd_balanca   numeric(20, 10) not null,
  diferenca     numeric(20, 10) not null,
  criado_em     timestamptz not null default now(),
  primary key (tenant_id, sessao_id, codprod, controle)
);

create index idx_compra_ajuste_itens_nunota on app.compra_ajuste_peso_itens (tenant_id, nunota);

create table app.compra_ajuste_peso_notas (
  id              uuid primary key default gen_random_uuid(),
  tenant_id       uuid not null references tenancy.tenants(id) on delete cascade,
  sessao_id       uuid not null,
  nunota          integer not null,
  nuconf          integer not null,
  sentido         text not null check (sentido in ('E', 'S')),
  -- pendente → criando (cabeçalho enviado) → cabecalho (NUNOTA do ajuste conhecido) → confirmado
  -- cancelado = a conferência foi refeita/excluída; erro = parou depois de 5 tentativas (ver ultimo_erro)
  status          text not null default 'pendente'
                    check (status in ('pendente', 'criando', 'cabecalho', 'confirmado', 'cancelado', 'erro')),
  nunota_ajuste   integer,
  tentativas      integer not null default 0,
  ultimo_erro     text,
  criado_em       timestamptz not null default now(),
  atualizado_em   timestamptz not null default now(),
  unique (tenant_id, sessao_id, sentido)
);

create index idx_compra_ajuste_notas_status on app.compra_ajuste_peso_notas (tenant_id, status);
create index idx_compra_ajuste_notas_nunota on app.compra_ajuste_peso_notas (tenant_id, nunota);

alter table app.compra_ajuste_peso_itens enable row level security;
alter table app.compra_ajuste_peso_itens force row level security;
create policy tenant_isolation on app.compra_ajuste_peso_itens
  using       (tenant_id = tenancy.current_tenant_id())
  with check  (tenant_id = tenancy.current_tenant_id());
grant select, insert, update, delete on app.compra_ajuste_peso_itens to wms_app;

alter table app.compra_ajuste_peso_notas enable row level security;
alter table app.compra_ajuste_peso_notas force row level security;
create policy tenant_isolation on app.compra_ajuste_peso_notas
  using       (tenant_id = tenancy.current_tenant_id())
  with check  (tenant_id = tenancy.current_tenant_id());
grant select, insert, update, delete on app.compra_ajuste_peso_notas to wms_app;

update tenancy.erp_connections c
   set parametros = c.parametros
                    || '{"ajustePesoCompra": {"habilitado": false, "ajusteEntrada": true, "ajusteSaida": true, "topEntrada": 1495, "topSaida": 1195, "serieEntrada": "0", "serieSaida": "0"}}'::jsonb,
       atualizado_em = now()
  from tenancy.tenants t
 where t.id = c.tenant_id and t.slug = 'negri' and c.erp_type = 'sankhya';
