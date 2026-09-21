package wms.backend.erp

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import wms.backend.tenancy.TenantRepository
import java.io.IOException
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

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
    // Timeouts explícitos: sem eles uma chamada pendurada segurava a operação inteira até o
    // upstream (Traefik/Sankhya) cortar. 60s cobre cortar/finalizarConferencia lentos (já vistos ~25s).
    private val http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()
    private val TIMEOUT_REQUISICAO = Duration.ofSeconds(60)
    private const val TENTATIVAS_MAXIMAS = 3
    private const val DELAY_INICIAL_MS = 2000L

    open class SankhyaSpException(message: String) : Exception(message)

    /** Falha transitória (5xx/408/429 do gateway) — a única de resposta HTTP que vale repetir. */
    class SankhyaSpErroTransitorio(message: String) : SankhyaSpException(message)

    /**
     * Repete SÓ falha transitória: erro de rede/timeout (IOException, inclui HttpTimeoutException)
     * e 5xx/408/429. Recusa de regra de negócio do Sankhya (`status != "1"`, ex.: "já está aguardando
     * recontagem") e 4xx NÃO se repetem — antes eram repetidas 3x com espera de 2s+4s, então uma recusa
     * que respondia em ~0,8s demorava ~10s (medido: negar corte, nota 57529).
     */
    private suspend fun <T> comRetry(serviceName: String, tentativa: suspend () -> T): T {
        var delayMs = DELAY_INICIAL_MS
        var ultimoErro: Exception? = null

        repeat(TENTATIVAS_MAXIMAS) { n ->
            try {
                return SankhyaMetricas.medir(serviceName) { tentativa() }
            } catch (e: Exception) {
                ultimoErro = e
                val transitorio = e is IOException || e is SankhyaSpErroTransitorio
                if (!transitorio) throw e
                if (n < TENTATIVAS_MAXIMAS - 1) delay(delayMs).also { delayMs *= 2 }
            }
        }
        throw ultimoErro ?: SankhyaSpException("Falha desconhecida chamando $serviceName")
    }

    suspend fun chamar(tenantSlug: String, serviceName: String, params: Map<String, JsonElement>) {
        chamarComRetorno(tenantSlug, serviceName, params)
    }

    /**
     * Variante "corpo livre": envia `{ serviceName, requestBody: <requestBody> }`
     * verbatim e deixa escolher o módulo do gateway. Necessária pras rotinas
     * cujo contrato NÃO é `{ params: {...} }` sob `/mgecom` — ex.:
     *  - `LiberacaoLimitesSP.*` e `DatasetSP.loadRecords` da ViewLiberacaoLimite
     *    rodam em `/mge` e levam `clientEventList` ao lado de `params`;
     *  - `SelecaoDocumentoSP.faturar` leva `requestBody.notas` (não `params`).
     * Mesmo retry/backoff/auth/checagem de `status == "1"` do [chamar].
     */
    suspend fun chamarRaw(
        tenantSlug: String,
        serviceName: String,
        modulo: String,
        requestBody: JsonObject,
    ): JsonObject = comRetry(serviceName) { chamarRawUmaVez(tenantSlug, serviceName, modulo, requestBody) }

    private suspend fun chamarRawUmaVez(
        tenantSlug: String,
        serviceName: String,
        modulo: String,
        requestBody: JsonObject,
    ): JsonObject {
        val credenciais = withContext(Dispatchers.IO) {
            TenantRepository.obterCredenciaisErp(tenantSlug, "sankhya")
        } ?: throw SankhyaSpException("Tenant '$tenantSlug' não tem conexão Sankhya configurada")

        val token = SankhyaAuthService.obterTokenValido(tenantSlug)
        val url = "${credenciais.baseUrl}/${credenciais.gatewayPath}" +
            "/$modulo/service.sbr?serviceName=$serviceName&outputType=json"

        val corpo = buildJsonObject {
            put("serviceName", serviceName)
            put("requestBody", requestBody)
        }

        return enviar(tenantSlug, serviceName, url, token, corpo)
    }

    /**
     * Igual a [chamar], mas devolve o `responseBody` inteiro — necessário
     * pras rotinas de CONSULTA (ex.: futuras `ConferenciaSP.buscarVolumes`),
     * já que [chamar] existia só pra rotinas de escrita onde só o
     * status/statusMessage importava.
     */
    suspend fun chamarComRetorno(tenantSlug: String, serviceName: String, params: Map<String, JsonElement>): JsonObject =
        comRetry(serviceName) { chamarUmaVez(tenantSlug, serviceName, params) }

    private suspend fun chamarUmaVez(tenantSlug: String, serviceName: String, params: Map<String, JsonElement>): JsonObject {
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

        return enviar(tenantSlug, serviceName, url, token, corpo)
    }

    /** POST + validação de resposta, comum às duas variantes. */
    private suspend fun enviar(tenantSlug: String, serviceName: String, url: String, token: String, corpo: JsonObject): JsonObject {
        val httpReq = HttpRequest.newBuilder()
            .uri(URI.create(url))
            .timeout(TIMEOUT_REQUISICAO)
            .header("Authorization", "Bearer $token")
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(corpo.toString()))
            .build()

        val response = withContext(Dispatchers.IO) {
            http.send(httpReq, HttpResponse.BodyHandlers.ofString())
        }

        val codigo = response.statusCode()
        if (codigo !in 200..299) {
            val msg = "Sankhya $serviceName respondeu $codigo para tenant '$tenantSlug': ${response.body()}"
            throw if (codigo >= 500 || codigo == 408 || codigo == 429) SankhyaSpErroTransitorio(msg) else SankhyaSpException(msg)
        }

        val json = Json.parseToJsonElement(response.body()).jsonObject
        val status = json["status"]?.jsonPrimitive?.contentOrNull
        if (status != "1") {
            val msg = json["statusMessage"]?.jsonPrimitive?.contentOrNull ?: "Falha em $serviceName"
            throw SankhyaSpException("Sankhya $serviceName falhou para tenant '$tenantSlug': $msg")
        }
        return json["responseBody"] as? JsonObject ?: buildJsonObject {}
    }
}
