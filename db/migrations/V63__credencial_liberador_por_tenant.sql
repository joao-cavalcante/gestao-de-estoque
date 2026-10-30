-- ============================================================================
-- V63 — credencial do liberador de serviço POR TENANT (Entrega 1, M22).
--
-- Antes: LIBERADOR_USUARIO / LIBERADOR_SENHA do processo valiam pra qualquer tenant (auto-liberação de pesável e
-- liberação herdada). Agora: erp_connections.credencial_liberador = {"usuario","senha"} cifrado (CredentialsCipher,
-- o mesmo das credenciais do ERP); gravada por PUT /api/tenants/{slug}/liberador, nunca devolvida. NULL = não
-- configurada = nenhuma liberação automática.
--
-- Transição da instalação legada: a Negri continua usando a variável de ambiente até ter a credencial gravada —
-- parametros.liberador.fallbackVariavelAmbiente = true SÓ pra ela. Outro tenant nunca usa a variável; cada uso da
-- reserva deixa "INFO: liberador de serviço pela variável de ambiente (transição legada...)" no log. Pra encerrar a
-- transição: gravar a credencial da Negri, conferir que o INFO sumiu numa auto-liberação, mudar o parâmetro pra
-- false (PUT /api/tenants/negri/parametros) e tirar LIBERADOR_USUARIO/LIBERADOR_SENHA do .env.
-- ============================================================================

alter table tenancy.erp_connections add column credencial_liberador text;

update tenancy.erp_connections c
   set parametros = c.parametros || '{"liberador": {"fallbackVariavelAmbiente": true}}'::jsonb,
       atualizado_em = now()
  from tenancy.tenants t
 where t.id = c.tenant_id and t.slug = 'negri' and c.erp_type = 'sankhya';
