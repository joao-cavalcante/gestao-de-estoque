package wms.backend.tenancy

/**
 * Credenciais JÁ DECIFRADAS de uma conexão de ERP — só deve existir na
 * memória do processo pelo tempo mínimo necessário (uso imediato numa
 * chamada HTTP ao ERP). Nunca serializar isto de volta numa resposta de
 * API, nunca logar.
 */
data class ErpCredentials(
    val baseUrl: String,
    val gatewayPath: String?,
    val dialect: String?,
    val clientId: String?,
    val clientSecret: String?,
    val xToken: String?,
)
