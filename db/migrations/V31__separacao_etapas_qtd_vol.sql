-- ============================================================================
-- V31 — volume POR ETAPA na conferência segmentada (V29).
--
-- Com 3 etapas (seca / refrigerada / congelada) conferidas por operadores
-- diferentes, possivelmente em paralelo, um contador único de volume na sessão
-- (V30) dá corrida (lost update) e mistura a contagem de todos.
--
-- Cada etapa passa a ter o SEU contador. O +/- da UI opera no contador da etapa
-- atual; a finalização soma todas as etapas e manda o consolidado no
-- ConferenciaSP.cortar. Conferência não segmentada continua usando
-- separacao_sessoes.qtd_vol (V30).
-- ============================================================================

alter table app.separacao_etapas add column qtd_vol integer not null default 0;
