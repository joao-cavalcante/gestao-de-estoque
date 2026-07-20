package wms.backend.erp

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import wms.backend.tenancy.TenantRepository
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse

/**
 * `DbExplorerSP.executeQuery` — roda SQL cru contra o banco do Sankhya
 * (gateway `/mge/service.sbr`, contrato confirmado lendo o cliente real do
 * projeto base: `{serviceName, requestBody:{sql}}`, resposta em
 * `responseBody.fieldsMetadata` (nomes das colunas, na ordem) +
 * `responseBody.rows` (arrays de valores nessa mesma ordem)).
 *
 * Usado só pra estoque (TGFEST) — dado que muda o tempo todo, sem
 * equivalente estruturado em loadRecords com o mesmo desempenho de query
 * direta. SQL deve ser ANSI puro (sem TOP/LIMIT, sem função específica de
 * dialeto) — tenants podem estar em Oracle ou SQL Server (ver
 * project_db_dialect).
 */
object SankhyaDbExplorerClient {
    private val http = HttpClient.newHttpClient()

    class SankhyaDbExplorerException(message: String) : Exception(message)

    suspend fun executarQuery(tenantSlug: String, sql: String): List<Map<String, String?>> {
        val credenciais = withContext(Dispatchers.IO) {
            TenantRepository.obterCredenciaisErp(tenantSlug, "sankhya")
        } ?: throw SankhyaDbExplorerException("Tenant '$tenantSlug' não tem conexão Sankhya configurada")

        val token = SankhyaAuthService.obterTokenValido(tenantSlug)
        val url = "${credenciais.baseUrl}/${credenciais.gatewayPath}" +
            "/mge/service.sbr?serviceName=DbExplorerSP.executeQuery&outputType=json"

        val corpo = buildJsonObject {
            put("serviceName", "DbExplorerSP.executeQuery")
            putJsonObject("requestBody") { put("sql", sql) }
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
            throw SankhyaDbExplorerException("Sankhya DbExplorerSP respondeu ${response.statusCode()} para tenant '$tenantSlug': ${response.body()}")
        }

        val json = Json.parseToJsonElement(response.body()).jsonObject
        val status = json["status"]?.jsonPrimitive?.contentOrNull
        val responseBody = json["responseBody"] as? JsonObject
        if (status != "1" || responseBody == null) {
            val msg = json["statusMessage"]?.jsonPrimitive?.contentOrNull ?: "Falha ao executar query"
            throw SankhyaDbExplorerException("Sankhya DbExplorerSP falhou para tenant '$tenantSlug': $msg")
        }

        val nomesColunas = (responseBody["fieldsMetadata"] as? JsonArray)
            ?.map { (it as JsonObject)["name"]!!.jsonPrimitive.content }
            ?: return emptyList()
        val linhas = responseBody["rows"] as? JsonArray ?: return emptyList()

        return linhas.map { linha ->
            val arr = linha as JsonArray
            nomesColunas.mapIndexed { i, nome -> nome to (arr.getOrNull(i) as? JsonPrimitive)?.contentOrNull }.toMap()
        }
    }
}
