-- ============================================================================
-- V16 — Espelho local da TGFTOP (Tipo de Operação) do Sankhya.
--
-- Traz localmente só as informações de TOP que já usamos em algum lugar do
-- sistema (CODTOP, descrição, e o NUCCO — o vínculo real com a Configuração
-- de Conferência, já confirmado no critério nativo da Fila de Tarefas:
-- TipoOperacao->ConfiguracaoConferencia). Diferente da TGFCCO (V15), aqui
-- não é um catálogo de 55 campos — é só o que conectamos a outras telas.
--
-- `dhalter_sankhya` é o próprio campo de auditoria de alteração do Sankhya
-- na TOP — permite sincronização incremental de verdade (só atualiza o que
-- mudou desde o último ciclo, ao contrário do full-refresh da TGFCCO, que
-- não tem esse campo na entidade usada lá). Guardado como TEXT (não
-- timestamptz) de propósito: não existe em nenhum lugar do backend um
-- parser confirmado do formato de data que o Sankhya devolve nesse campo
-- (DTNEG, o único outro campo de data já visto, também é mantido como
-- string crua — ver TarefasRepository) — comparação de string já resolve
-- "mudou ou não mudou" sem precisar adivinhar o formato.
--
-- Mesma receita de RLS do V12/V14/V15.
-- ============================================================================

create table app.tipos_operacao (
  id                     uuid primary key default gen_random_uuid(),
  tenant_id              uuid not null references tenancy.tenants(id) on delete cascade,

  codtop                 integer not null,
  descricao              text not null,
  nucco                  integer,   -- nullable: nem todo TOP tem Configuração de Conferência associada

  dhalter_sankhya        text,
  local_atualizado_em    timestamptz not null default now(),

  unique (tenant_id, codtop)
);

create index idx_tipos_operacao_tenant on app.tipos_operacao (tenant_id);
create index idx_tipos_operacao_nucco on app.tipos_operacao (tenant_id, nucco);

alter table app.tipos_operacao enable row level security;
alter table app.tipos_operacao force row level security;

create policy tenant_isolation on app.tipos_operacao
  using       (tenant_id = tenancy.current_tenant_id())
  with check  (tenant_id = tenancy.current_tenant_id());

grant select, insert, update, delete on app.tipos_operacao to wms_app;
