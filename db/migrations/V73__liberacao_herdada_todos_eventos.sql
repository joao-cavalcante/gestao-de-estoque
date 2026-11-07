-- ============================================================================
-- V73 — Liberação herdada: todos os eventos, menos o de corte (Negri, 06/11/2026).
--
-- Pedido do cliente: "com exceção dos eventos de corte, tudo que for liberado no pedido e pedir novamente na nota
-- precisa ser liberado automaticamente". ParametrosLiberacaoHerdada.todosEventos = true faz a regra herdar qualquer
-- evento já liberado na origem (TGFCAB/TGFITE); o corte (evento 64, TGFCOI2) nunca é herdado (EVENTOS_CORTE).
-- Valor continua sem comparar (compararValor false, V62). Instalação sem o tenant 'negri': nada muda.
-- ============================================================================

update tenancy.erp_connections c
   set parametros = jsonb_set(c.parametros, '{liberacaoHerdada,todosEventos}', 'true'::jsonb, true),
       atualizado_em = now()
  from tenancy.tenants t
 where t.id = c.tenant_id and t.slug = 'negri' and c.erp_type = 'sankhya'
   and c.parametros ? 'liberacaoHerdada';
