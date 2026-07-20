-- ============================================================================
-- V18 — Remove app.config_conferencia_cache.
--
-- Era um cache com TTL de 15min (só BUSCARCODBARRAPOR/FORMACAOVOLUMES),
-- usado por SeparacaoService.iniciar pra evitar bater no Sankhya toda vez
-- que uma conferência era aberta. Redundante desde a V15: app.config_conferencia
-- já é um mirror COMPLETO (55 campos) da TGFCCO, sincronizado no mesmo
-- intervalo (ConfigConferenciaSyncWorker, 15min) — SeparacaoService passou a
-- ler dali direto (ConfigConferenciaRepository.buscarPorNucco), sem manter
-- dois caches separados da mesma informação.
-- ============================================================================

drop table if exists app.config_conferencia_cache;
