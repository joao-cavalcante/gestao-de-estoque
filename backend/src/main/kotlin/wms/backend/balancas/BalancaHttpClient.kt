package wms.backend.balancas

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonPrimitive
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

class BalancaHttpException(message: String) : Exception(message)

/**
 * Driver HTTP genérico — mesma lógica do sistema atual (http.driver.ts):
 * GET em `http://{ip}:{porta}{rota}`, extrai o peso de número puro, string
 * numérica, ou campo `peso`/`value`/`weight`/`data` de um JSON. Único tipo
 * de balança lido no SERVIDOR — os demais (serial) são client-side via o
 * agente local (inalterado).
 */
object BalancaHttpClient {
    private val http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()

    suspend fun lerPeso(balanca: BalancaDto): Double {
        val ip = balanca.ip ?: throw BalancaHttpException("balança sem IP configurado")
        val porta = balanca.porta ?: throw BalancaHttpException("balança sem porta configurada")
        val rota = balanca.rota ?: ""
        val url = "http://$ip:$porta$rota"

        val request = HttpRequest.newBuilder()
            .uri(URI.create(url))
            .timeout(Duration.ofSeconds(5))
            .GET()
            .build()

        val response = withContext(Dispatchers.IO) {
            http.send(request, HttpResponse.BodyHandlers.ofString())
        }

        if (response.statusCode() !in 200..299) {
            throw BalancaHttpException("balança respondeu ${response.statusCode()}")
        }

        return extrairPeso(response.body())
            ?: throw BalancaHttpException("não foi possível extrair peso da resposta: ${response.body().take(200)}")
    }

    private fun extrairPeso(corpo: String): Double? {
        val texto = corpo.trim()

        // número puro ou string numérica (com vírgula decimal, como o driver antigo aceitava)
        texto.replace(',', '.').toDoubleOrNull()?.let { return it }

        // JSON: objeto com peso/value/weight/data, ou número/string dentro de aspas
        return runCatching {
            when (val elemento = Json.parseToJsonElement(texto)) {
                is JsonObject -> {
                    val campo = elemento["peso"] ?: elemento["value"] ?: elemento["weight"] ?: elemento["data"]
                    campo?.jsonPrimitive?.let { it.doubleOrNull ?: it.contentOrNull?.replace(',', '.')?.toDoubleOrNull() }
                }
                is JsonPrimitive -> elemento.doubleOrNull ?: elemento.contentOrNull?.replace(',', '.')?.toDoubleOrNull()
                else -> null
            }
        }.getOrNull()
    }
}
