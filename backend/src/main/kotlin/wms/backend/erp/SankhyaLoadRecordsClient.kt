package wms.backend.erp

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import wms.backend.tenancy.TenantRepository
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse

class SankhyaApiException(message: String, cause: Throwable? = null) : Exception(message, cause)

data class LoadRecordsRequest(
    val entityName: String,
    val fields: List<String>,
    val criteriaExpression: String? = null,
    val orderByExpression: String? = null,
    /** Só usado com usarCrudServiceProvider — esse serviço pagina por padrão (ver hasMoreResult). */
    val offsetPage: Int = 0,
    /**
     * Algumas entidades (ex.: TipoOperacao, Produto, CodigoBarras em varredura
     * completa) não são catalogadas como "business entity" do DatasetSP — só
     * respondem pelo CRUDServiceProvider.loadRecords genérico, o mesmo serviço
     * que o sistema antigo (fila-de-conferencia) usava pra tudo. Diferente do
     * DatasetSP, ele pagina de verdade (offsetPage/hasMoreResult) — necessário
     * pra varreduras completas de catálogo (16k+ produtos), onde
     * useDefaultRowsLimit do DatasetSP corta sem avisar.
     */
    val usarCrudServiceProvider: Boolean = false,
)

/**
 * Cliente do DatasetSP.loadRecords do Sankhya — mesmo contrato usado pela
 * própria tela nativa "Fila de Conferência" (capturado via devtools do app
 * real): entityName + fields (array de nomes/paths de relacionamento tipo
 * "TipoOperacao->ConfiguracaoConferencia->X") + criteria.expression usando
 * essa mesma sintaxe de relacionamento, em vez do CRUDServiceProvider mais
 * genérico usado no sistema antigo (mge/service.sbr com joins separados).
 *
 * outputType=json é obrigatório na URL — sem isso o Sankhya responde XML.
 */
object SankhyaLoadRecordsClient {
    private val http = HttpClient.newHttpClient()

    suspend fun loadRecords(tenantSlug: String, req: LoadRecordsRequest): JsonObject {
        val credenciais = withContext(Dispatchers.IO) {
            TenantRepository.obterCredenciaisErp(tenantSlug, "sankhya")
        } ?: throw SankhyaApiException("Tenant '$tenantSlug' não tem conexão Sankhya configurada")

        val token = SankhyaAuthService.obterTokenValido(tenantSlug)
        val serviceName = if (req.usarCrudServiceProvider) "CRUDServiceProvider.loadRecords" else "DatasetSP.loadRecords"
        val url = "${credenciais.baseUrl}/${credenciais.gatewayPath}" +
            "/mge/service.sbr?serviceName=$serviceName&outputType=json"

        val body = if (req.usarCrudServiceProvider) buildBodyCrud(req) else buildBody(req)
        val httpReq = HttpRequest.newBuilder()
            .uri(URI.create(url))
            .header("Authorization", "Bearer $token")
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
            .build()

        val response = withContext(Dispatchers.IO) {
            http.send(httpReq, HttpResponse.BodyHandlers.ofString())
        }

        if (response.statusCode() !in 200..299) {
            throw SankhyaApiException(
                "Sankhya loadRecords respondeu ${response.statusCode()} para tenant '$tenantSlug': ${response.body()}",
            )
        }

        return Json.parseToJsonElement(response.body()).jsonObject
    }

    private fun buildBody(req: LoadRecordsRequest): JsonObject = buildJsonObject {
        put("serviceName", "DatasetSP.loadRecords")
        putJsonObject("requestBody") {
            put("entityName", req.entityName)
            put("standAlone", true)
            req.orderByExpression?.let { put("orderByExpression", it) }
            put("tryJoinedFields", true)
            put("useDefaultRowsLimit", true)

            if (req.criteriaExpression != null) {
                putJsonObject("criteria") {
                    put("expression", req.criteriaExpression)
                    putJsonArray("parameters") {}
                }
            }

            putJsonArray("fields") { req.fields.forEach { add(it) } }
        }
    }

    /** Mesmo formato usado pelo fila-de-conferencia (sistema antigo) — dataSet/rootEntity/entity[{path,fieldset}]. */
    private fun buildBodyCrud(req: LoadRecordsRequest): JsonObject = buildJsonObject {
        put("serviceName", "CRUDServiceProvider.loadRecords")
        putJsonObject("requestBody") {
            putJsonObject("dataSet") {
                put("rootEntity", req.entityName)
                put("ignoreCalculatedFields", "true")
                put("useFileBasedPagination", "true")
                put("includePresentationFields", "N")
                put("tryJoinedFields", "true")
                put("offsetPage", req.offsetPage.toString())

                if (req.criteriaExpression != null) {
                    putJsonObject("criteria") {
                        putJsonObject("expression") { put("$", req.criteriaExpression) }
                    }
                }

                putJsonArray("entity") {
                    addJsonObject {
                        put("path", "")
                        putJsonObject("fieldset") { put("list", req.fields.joinToString(",")) }
                    }
                }
            }
        }
    }

    /**
     * DatasetSP.loadRecords não usa o formato f0/f1/f2 do CRUDServiceProvider —
     * devolve as linhas já como array de valores na MESMA ORDEM de `fields`
     * do request. Aceita ambos os formatos só por robustez (caso varie por
     * versão do gateway).
     */
    fun parseRows(raw: JsonObject, fields: List<String>): List<Map<String, String?>> {
        val responseBody = raw["responseBody"] as? JsonObject ?: return emptyList()

        // Confirmado ao vivo contra o Sankhya real: a chave é "result", não "rows".
        val rows = (responseBody["result"] ?: responseBody["rows"]) as? JsonArray
        if (rows != null) {
            return rows.map { row ->
                val arr = row as JsonArray
                fields.mapIndexed { i, name -> name to (arr.getOrNull(i) as? JsonPrimitive)?.contentOrNull }.toMap()
            }
        }

        // Fallback: formato entities/metadata (CRUDServiceProvider) — caso o
        // gateway devolva nesse shape pra este serviço em algum ambiente.
        val entities = responseBody["entities"] as? JsonObject ?: return emptyList()
        val fieldsElement = (entities["metadata"] as? JsonObject)?.get("fields")?.let { it as? JsonObject }?.get("field")
            ?: return emptyList()
        val fieldNames: List<String> = when (fieldsElement) {
            is JsonArray -> fieldsElement.map { (it as JsonObject)["name"]!!.jsonPrimitive.content }
            is JsonObject -> listOf(fieldsElement["name"]!!.jsonPrimitive.content)
            else -> emptyList()
        }
        val entityElement = entities["entity"]
        val entityRows: List<JsonObject> = when (entityElement) {
            is JsonArray -> entityElement.map { it as JsonObject }
            is JsonObject -> listOf(entityElement)
            else -> emptyList()
        }
        return entityRows.map { row ->
            fieldNames.mapIndexed { i, name ->
                val cell = row["f$i"] as? JsonObject
                name to cell?.get("$")?.jsonPrimitive?.contentOrNull
            }.toMap()
        }
    }

    /** Só relevante pra usarCrudServiceProvider — DatasetSP.loadRecords devolve tudo numa página só. */
    fun hasMoreResult(raw: JsonObject): Boolean {
        val entities = (raw["responseBody"] as? JsonObject)?.get("entities") as? JsonObject ?: return false
        return entities["hasMoreResult"]?.jsonPrimitive?.contentOrNull == "true"
    }
}
