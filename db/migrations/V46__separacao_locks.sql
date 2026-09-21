-- V46 - lock exclusivo por etapa da conferencia, com expiracao por inatividade.
--
-- Uma linha = "esta aba/tablet (token) esta trabalhando nesta etapa desta sessao". tipo_separacao = 0
-- vale pra sessao inteira (nao segmentada, e recontagem, que e sempre etapa unica). O token e gerado
-- pelo navegador (sessionStorage) e NAO e o usuario: em conta Stage varios tablets compartilham o
-- mesmo login, entao o usuario nao identifica quem esta com a etapa.
--
-- Exclusividade = chave primaria (tenant, sessao, tipo): a aquisicao e um UPDATE/INSERT atomico,
-- nunca "consulta -> cria". A expiracao e lazy: um lock com ultima_atividade mais velha que 10 minutos
-- e tratado como livre por quem tenta adquirir (nao precisa de job). O heartbeat renova ultima_atividade.
create table app.separacao_locks (
  tenant_id         uuid not null references tenancy.tenants(id) on delete cascade,
  sessao_id         uuid not null references app.separacao_sessoes(id) on delete cascade,
  tipo_separacao    smallint not null,
  token             uuid not null,
  user_id           uuid not null,
  operador_id       uuid,
  adquirido_em      timestamptz not null,
  ultima_atividade  timestamptz not null,
  primary key (tenant_id, sessao_id, tipo_separacao)
);

alter table app.separacao_locks enable row level security;
alter table app.separacao_locks force row level security;

create policy tenant_isolation on app.separacao_locks
  using       (tenant_id = tenancy.current_tenant_id())
  with check  (tenant_id = tenancy.current_tenant_id());

grant select, insert, update, delete on app.separacao_locks to wms_app;
