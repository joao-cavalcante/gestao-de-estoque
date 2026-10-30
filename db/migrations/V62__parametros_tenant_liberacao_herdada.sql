-- ============================================================================
-- V62 — parâmetros de operação por tenant + módulo liberacao_herdada (Entrega 1, M21).
--
-- tenancy.erp_connections.parametros: JSON tipado (wms.backend.tenancy.ParametrosTenant), sem segredo. '{}' =
-- os padrões seguros do produto (nada automático). Complementa `modulos`: o módulo liga a regra, o parâmetro diz
-- como ela roda.
--
-- A liberação herdada (LiberacaoHerdada.kt) passa a exigir o módulo `liberacao_herdada` + eventos configurados.
-- Instalação nova: desligada. Negri (único tenant que usa, regra do evento Java negri-liberacao-herdada): o
-- comportamento de antes fica EXPLÍCITO aqui — módulo ligado, evento 3, valor não comparado (decisão do cliente,
-- 16/10/2026). Em instalação sem o tenant 'negri' o UPDATE não altera nada.
-- ============================================================================

alter table tenancy.erp_connections add column parametros jsonb not null default '{}'::jsonb;

update tenancy.erp_connections c
   set modulos = case when 'liberacao_herdada' = any(c.modulos) then c.modulos
                      else array_append(c.modulos, 'liberacao_herdada') end,
       parametros = c.parametros || '{"liberacaoHerdada": {"eventos": [3], "compararValor": false}}'::jsonb,
       atualizado_em = now()
  from tenancy.tenants t
 where t.id = c.tenant_id and t.slug = 'negri' and c.erp_type = 'sankhya';
