-- V43 - item ja enviado ao Sankhya (ConferenciaSP.salvarItemConferido).
-- Na conferencia por etapas os itens de cada etapa sao enviados ao CONCLUIR a etapa
-- (em vez de tudo no finalizar); o finalizar so envia o que ainda nao foi.
alter table app.separacao_itens add column enviado_sankhya boolean not null default false;
