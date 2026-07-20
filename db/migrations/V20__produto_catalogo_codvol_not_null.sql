-- ============================================================================
-- V20 — codvol/codbarra viram NOT NULL DEFAULT '' em app.codigos_barra_cache
-- e app.volumes_alternativos_cache.
--
-- Bug real: a unique key (tenant_id, codprod, codvol, codbarra) incluía
-- colunas nuláveis. Postgres NÃO trata NULL = NULL em unique constraint —
-- ON CONFLICT nunca detectava conflito pra produto sem unidade alternativa
-- (CODVOL nulo, o caso comum), então upsertBar/upsertVoa inseriam linha
-- duplicada a cada sync em vez de atualizar a existente. Trocando NULL por
-- '' (string vazia) elimina o problema — '' = '' bate normalmente no
-- unique index, então ON CONFLICT volta a funcionar.
-- ============================================================================

update app.codigos_barra_cache set codvol = '' where codvol is null;
alter table app.codigos_barra_cache alter column codvol set default '';
alter table app.codigos_barra_cache alter column codvol set not null;

update app.volumes_alternativos_cache set codvol = '' where codvol is null;
alter table app.volumes_alternativos_cache alter column codvol set default '';
alter table app.volumes_alternativos_cache alter column codvol set not null;

update app.volumes_alternativos_cache set codbarra = '' where codbarra is null;
alter table app.volumes_alternativos_cache alter column codbarra set default '';
alter table app.volumes_alternativos_cache alter column codbarra set not null;
