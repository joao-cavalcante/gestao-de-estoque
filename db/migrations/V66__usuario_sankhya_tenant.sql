-- ============================================================================
-- V66 — criação de usuário no Sankhya configurada por tenant (Entrega 1, M24).
--
-- Antes: SankhyaUsuarioService tinha no código os valores do usuário-modelo da Negri (CODUSU 194), CODGRUPO 32 e o
-- domínio negri.local, usados em qualquer tenant. Agora parametros.usuarioSankhya: desligado por padrão; habilitado
-- só com usuário-modelo, grupo e domínio. Sem camposFixos, os valores são lidos do usuário-modelo na TSIUSU.
--
-- Negri: os MESMOS valores que saíam do código (cópia do CODUSU 194 de 10/2026) ficam congelados em camposFixos —
-- payload idêntico ao de antes. Nenhum usuário existente no Sankhya é alterado.
-- ============================================================================

update tenancy.erp_connections c
   set parametros = c.parametros || '{"usuarioSankhya": {"habilitado": true, "usuarioModelo": 194, "codGrupo": 32, "dominioEmail": "negri.local", "camposFixos": {"ABREGAVETA": "N", "ACESSAFORMULAREL": "N", "ACESSOVISUALCAB": "T", "ALTCTAFAT": "N", "ALTCTAIMPBOL": "N", "ALTORDCFECH": "N", "APENASCOMPLIB": "N", "APROVCOT": "M", "AVISAVARPRECO": "N", "CAIXA": "N", "CODCENCUSPAD": "0", "CODIDECONECT": "0", "CODPARC": "0", "CODVEND": "0", "DESCTOTALNOTAPDV": "N", "EXCLIBORC": "N", "EXIBIRVALANALRENT": "S", "IGNORAHORASCRUZ": "N", "IMP2SANSUPCAI": "S", "IMPNFCENTRAL": "S", "INSTALAPACOTESS": "N", "INTEGRAECONECT": "N", "LOCALE": "PT_BR", "MINUTOSFIN": "0", "NIVEL": "100", "PERMALTMOEDA": "N", "PERMEXPREL": "S", "PERMIMPRIMEREL": "N", "PERMREPERRO": "N", "PORTASMTP": "25", "RESTRINGECART": "N", "SEGURANCASMTP": "N", "SELECTWCAPO": "N", "SENHANUNCAEXPIRA": "S", "TEMECF": "N", "TIMBAIXAWORD": "S", "TIMBAIXTITRECABE": "N", "TIMVERTODASFACS": "N", "TIPOSMTP": "N", "TIPOUSU": "0", "VERCABPROPRIA": "N", "VISACESOUTUSU": "S"}}}'::jsonb,
       atualizado_em = now()
  from tenancy.tenants t
 where t.id = c.tenant_id and t.slug = 'negri' and c.erp_type = 'sankhya';
