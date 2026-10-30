-- ============================================================================
-- V64 — política de auto-liberação de pesável quando o NUCCO não tem tolerância (Entrega 1, M23).
--
-- Antes: NUCCO sem linha na V50 copiava pra sessão "acima sem limite, abaixo 5%" e a auto-liberação de corte usava
-- isso — qualquer excesso de peso era liberado pelo liberador de serviço. Agora a sessão grava DE ONDE veio a
-- tolerância (tol_peso_origem: 'nucco' | 'padrao'); com 'padrao', a auto-liberação segue
-- parametros.toleranciaPesavel do tenant, e o padrão do produto é NÃO liberar sozinho (vai pra liberação manual).
-- Tela, divergência, ajuste de peso de compra e corte silencioso continuam usando a cópia de sempre.
--
-- Sessões já abertas (tol_peso_origem NULL) seguem a cópia, como antes. Negri: a regra de antes fica explícita.
-- ============================================================================

alter table app.separacao_sessoes add column tol_peso_origem text
  check (tol_peso_origem is null or tol_peso_origem in ('nucco', 'padrao'));

update tenancy.erp_connections c
   set parametros = c.parametros
                    || '{"toleranciaPesavel": {"semConfiguracaoNucco": {"acimaSemLimite": true, "abaixoPct": 5}}}'::jsonb,
       atualizado_em = now()
  from tenancy.tenants t
 where t.id = c.tenant_id and t.slug = 'negri' and c.erp_type = 'sankhya';
