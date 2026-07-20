package wms.backend.erp

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import wms.backend.tenancy.TenantRepository
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse

/**
 * Chama rotinas Java nativas do Sankhya (ex: `ConferenciaSP.salvarCabecalhoConferencia`)
 * — contrato DIFERENTE do DatasetSP.loadRecords: gateway `/mgecom/service.sbr`
 * (não `/mge/service.sbr`), corpo `{serviceName, requestBody: {params}}` (não
 * entityName/fields/criteria), confirmado lendo o código-fonte real do
 * projeto base (`GatewayClient`/`chamarConferenciaSP`), não adivinhado.
 *
 * Isto GRAVA no Sankhya (cria/atualiza o cabeçalho de conferência de
 * produção) — por isso só existe pra rotinas com contrato já confirmado
 * contra código funcionando de verdade, nunca uma chamada de escrita
 * inventada.
 */
object SankhyaSpClient {
    private val http = HttpClient.newHttpClient()
    private const val TENTATIVAS_MAXIMAS = 3
    private const val DELAY_INICIAL_MS = 2000L

    class SankhyaSpException(message: String) : Exception(message)

    suspend fun chamar(tenantSlug: String, serviceName: String, params: Map<String, JsonElement>) {
        var delayMs = DELAY_INICIAL_MS
        var ultimoErro: Exception? = null

        repeat(TENTATIVAS_MAXIMAS) { tentativa ->
            try {
                chamarUmaVez(tenantSlug, serviceName, params)
                return
            } catch (e: Exception) {
                ultimoErro = e
                if (tentativa < TENTATIVAS_MAXIMAS - 1) delay(delayMs).also { delayMs *= 2 }
            }
        }
        throw ultimoErro ?: SankhyaSpException("Falha desconhecida chamando $serviceName")
    }

    private suspend fun chamarUmaVez(tenantSlug: String, serviceName: String, params: Map<String, JsonElement>) {
        val credenciais = withContext(Dispatchers.IO) {
            TenantRepository.obterCredenciaisErp(tenantSlug, "sankhya")
        } ?: throw SankhyaSpException("Tenant '$tenantSlug' não tem conexão Sankhya configurada")

        val token = SankhyaAuthService.obterTokenValido(tenantSlug)
        val url = "${credenciais.baseUrl}/${credenciais.gatewayPath}" +
            "/mgecom/service.sbr?serviceName=$serviceName&outputType=json"

        val corpo = buildJsonObject {
            put("serviceName", serviceName)
            putJsonObject("requestBody") {
                putJsonObject("params") {
                    params.forEach { (k, v) -> put(k, v) }
                }
            }
        }

        val httpReq = HttpRequest.newBuilder()
            .uri(URI.create(url))
            .header("Authorization", "Bearer $token")
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(corpo.toString()))
            .build()

        val response = withContext(Dispatchers.IO) {
            http.send(httpReq, HttpResponse.BodyHandlers.ofString())
        }

        if (response.statusCode() !in 200..299) {
            throw SankhyaSpException("Sankhya $serviceName respondeu ${response.statusCode()} para tenant '$tenantSlug': ${response.body()}")
        }

        val json = Json.parseToJsonElement(response.body()).jsonObject
        val status = json["status"]?.jsonPrimitive?.contentOrNull
        if (status != "1") {
            val msg = json["statusMessage"]?.jsonPrimitive?.contentOrNull ?: "Falha em $serviceName"
            throw SankhyaSpException("Sankhya $serviceName falhou para tenant '$tenantSlug': $msg")
        }
    }
}
