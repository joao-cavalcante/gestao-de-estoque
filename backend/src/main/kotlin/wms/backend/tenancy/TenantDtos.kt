package wms.backend.tenancy

import kotlinx.serialization.Serializable

/**
 * Representação de LEITURA de uma conexão de ERP — nunca carrega segredo em
 * texto plano. `credenciaisConfiguradas` só informa se há algo salvo; pra
 * saber o valor, é preciso decifrar no próprio backend (uso interno, nunca
 * exposto por rota).
 */
@Serializable
data class ErpConnectionDto(
    val erpType: String,
    val baseUrl: String,
    val gatewayPath: String? = null,
    val dialect: String? = null,
    val ativo: Boolean = true,
    val credenciaisConfiguradas: Boolean = false,
    /** Módulos (feature flags) habilitados pra este tenant — ver wms.backend.tenancy.Modulos. */
    val modulos: List<String> = emptyList(),
)

/**
 * Representação de ESCRITA — o cliente (tela de admin) manda o JSON em
 * texto plano aqui; o repositório cifra antes de gravar. Nunca é retornado
 * por nenhuma rota, só recebido.
 */
@Serializable
data class ErpConnectionInput(
    val erpType: String,
    val baseUrl: String,
    val gatewayPath: String? = null,
    val dialect: String? = null,
    val ativo: Boolean = true,
    /**
     * JSON cru (client_id/secret/token etc.) — shape depende do erpType.
     * Cifrado antes de salvar. null = "não mexe no segredo já salvo" (tela
     * de edição, que nunca recebe o segredo de volta pra reenviar).
     */
    val credenciais: String? = null,
    /** Módulos (feature flags) habilitados pra este tenant — ver wms.backend.tenancy.Modulos. Sempre reenviado inteiro (substitui a lista). */
    val modulos: List<String> = emptyList(),
)

@Serializable
data class TenantDto(
    val id: String? = null,
    val slug: String,
    val nome: String,
    val tier: String = "shared",
    val status: String = "trial",
    val dedicatedDbUrl: String? = null,
    val erpConnections: List<ErpConnectionDto> = emptyList(),
)

@Serializable
data class CriarTenantRequest(
    val slug: String,
    val nome: String,
    val tier: String = "shared",
    val dedicatedDbUrl: String? = null,
    val erpConnections: List<ErpConnectionInput> = emptyList(),
)

@Serializable
data class TesteAutenticacaoResponse(
    val autenticado: Boolean,
    val erro: String? = null,
)

@Serializable
data class AtualizarTenantRequest(
    val nome: String? = null,
    val status: String? = null,
    val tier: String? = null,
    val dedicatedDbUrl: String? = null,
)
