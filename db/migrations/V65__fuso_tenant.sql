-- ============================================================================
-- V65 — fuso da operação por tenant (Entrega 1, M17).
--
-- A data de faturamento (dtFaturamento do SelecaoDocumentoSP.faturar) vinha do fuso do processo — UTC no container.
-- Confirmado em produção (consulta somente leitura, 30/10/2026): 33 notas faturadas pelo WMS entre 21h e 24h de
-- Brasília (19 a 29/10) com DTFATUR = dia seguinte (DTNEG/DTMOV corretos). Nenhuma nota emitida é alterada.
--
-- Agora vale parametros.fuso (FusoInstalacao); ausente = America/Sao_Paulo. Negri: explícito.
-- ============================================================================

update tenancy.erp_connections c
   set parametros = c.parametros || '{"fuso": "America/Sao_Paulo"}'::jsonb,
       atualizado_em = now()
  from tenancy.tenants t
 where t.id = c.tenant_id and t.slug = 'negri' and c.erp_type = 'sankhya';
