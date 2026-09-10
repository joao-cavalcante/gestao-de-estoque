-- ============================================================================
-- V30 — quantidade de volumes da conferência, LOCAL na sessão.
--
-- O modo simplificado de volume (sem dimensão) do fila-de-conferencia guarda o
-- total numa coluna local (sessaoConferencia.qtdVol), incrementada na hora pelo
-- +/- da UI, e só é empurrado pro Sankhya no `cortar` da finalização
-- (ConferenciaSP.cortar recebe { nuNota, peso, qtdVol }).
--
-- O WMS estava tentando gravar/ler via ConferenciaSP.salvarVolumeSimplificado +
-- CabecalhoConferencia.QTDVOL, que não persiste onde a gente relê -> o contador
-- ficava sempre em 0. Passa a usar esta coluna como fonte da verdade durante a
-- conferência (a etiqueta lê daqui), e a finalização manda no cortar.
-- ============================================================================

alter table app.separacao_sessoes add column qtd_vol integer not null default 0;
