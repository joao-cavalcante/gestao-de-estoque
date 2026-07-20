package wms.backend.erp

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import wms.backend.tenancy.ErpCredentials
import wms.backend.tenancy.TenantRepository
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.net.http.HttpTimeoutException
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

/**
 * Autenticação OAuth2 (client_credentials) contra a API do Sankhya —
 * contrato do PRÓPRIO Sankhya (endpoint /authenticate, form-urlencoded,
 * header X-Token extra), não algo inventado aqui: qualquer integração com
 * a API deles precisa seguir esse mesmo formato.
 *
 * Um token por tenant, cacheado em memória com margem de expiração — evita
 * autenticar de novo a cada chamada ao ERP. Um Mutex por tenant garante que,
 * se o token expirar com várias requisições concorrentes em voo, só uma de
 * fato chama o Sankhya (as outras esperam e reaproveitam o resultado) —
 * sem lock global, então tenants diferentes autenticam em paralelo.
 */
object SankhyaAuthService {
    private const val MARGEM_RENOVACAO_MS = 5 * 60 * 1000L
    private const val TENTATIVAS_MAXIMAS = 3
    private const val DELAY_INICIAL_MS = 1000L

    private data class TokenCacheado(val token: String, val expiraEm: Instant)

    private val cache = ConcurrentHashMap<String, TokenCacheado>()
    private val locksPortenant = ConcurrentHashMap<String, Mutex>()

    private val http = HttpClient.newHttpClient()
    private val json = Json { ignoreUnknownKeys = true }

    @Serializable
    private data class RespostaAutenticacao(
        val access_token: String? = null,
        val expires_in: Long? = null,
    )

    suspend fun obterTokenValido(tenantSlug: String): String {
        cache[tenantSlug]?.let { cached ->
            if (Instant.now().isBefore(cached.expiraEm.minusMillis(MARGEM_RENOVACAO_MS))) {
                return cached.token
            }
        }

        val mutex = locksPortenant.computeIfAbsent(tenantSlug) { Mutex() }
        return mutex.withLock {
            // Outra corrotina pode ter renovado enquanto esperávamos o lock.
            cache[tenantSlug]?.let { cached ->
                if (Instant.now().isBefore(cached.expiraEm.minusMillis(MARGEM_RENOVACAO_MS))) {
                    return@withLock cached.token
                }
            }
            autenticarComRetry(tenantSlug)
        }
    }

    private suspend fun autenticarComRetry(tenantSlug: String): String {
        var delayMs = DELAY_INICIAL_MS
        var ultimoErro: Exception? = null

        repeat(TENTATIVAS_MAXIMAS) { tentativa ->
            try {
                return autenticar(tenantSlug)
            } catch (e: Exception) {
                ultimoErro = e
                if (tentativa < TENTATIVAS_MAXIMAS - 1) {
                    delay(delayMs)
                    delayMs *= 2
                }
            }
        }
        throw SankhyaAuthException(
            "Falha ao autenticar no Sankhya para tenant '$tenantSlug' após $TENTATIVAS_MAXIMAS tentativas",
            ultimoErro,
        )
    }

    private suspend fun autenticar(tenantSlug: String): String {
        val credenciais: ErpCredentials = withContext(Dispatchers.IO) {
            TenantRepository.obterCredenciaisErp(tenantSlug, "sankhya")
        } ?: throw SankhyaAuthException("Tenant '$tenantSlug' não tem conexão Sankhya configurada")

        if (credenciais.clientId.isNullOrBlank() || credenciais.clientSecret.isNullOrBlank()) {
            throw SankhyaAuthException("Tenant '$tenantSlug' não tem clientId/clientSecret configurados")
        }

        val form = "grant_type=client_credentials" +
            "&client_id=${enc(credenciais.clientId)}" +
            "&client_secret=${enc(credenciais.clientSecret)}"

        val requestBuilder = HttpRequest.newBuilder()
            .uri(URI.create("${credenciais.baseUrl}/authenticate"))
            .header("Content-Type", "application/x-www-form-urlencoded")
            .header("Accept", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(form))
        credenciais.xToken?.let { requestBuilder.header("X-Token", it) }

        val response = try {
            withContext(Dispatchers.IO) {
                http.send(requestBuilder.build(), HttpResponse.BodyHandlers.ofString())
            }
        } catch (e: HttpTimeoutException) {
            throw SankhyaAuthException("Timeout autenticando no Sankhya para tenant '$tenantSlug'", e)
        }

        if (response.statusCode() !in 200..299) {
            throw SankhyaAuthException("Sankhya respondeu ${response.statusCode()} para tenant '$tenantSlug': ${response.body()}")
        }

        val corpo = json.decodeFromString(RespostaAutenticacao.serializer(), response.body())
        val token = corpo.access_token
            ?: throw SankhyaAuthException("Sankhya não retornou access_token para tenant '$tenantSlug'")

        val expiraEm = Instant.now().plusSeconds(corpo.expires_in ?: 3600)
        cache[tenantSlug] = TokenCacheado(token, expiraEm)
        return token
    }

    private fun enc(valor: String): String =
        java.net.URLEncoder.encode(valor, Charsets.UTF_8)
}
