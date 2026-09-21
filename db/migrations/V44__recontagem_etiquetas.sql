-- V44 - etiquetas na recontagem.
-- recontagem/volume_base: a sessao de recontagem continua a numeracao de volumes da conferencia
-- anterior (7 volumes antes + 1 novo = etiqueta 8), mesmo que a recontagem suba 0 volumes.
alter table app.separacao_sessoes add column recontagem boolean not null default false;
alter table app.separacao_sessoes add column volume_base integer not null default 0;
-- correcao/substitui_numero: etiqueta de peso emitida na recontagem substitui a da conferencia original.
alter table app.etiquetas_peso add column correcao boolean not null default false;
alter table app.etiquetas_peso add column substitui_numero bigint;
