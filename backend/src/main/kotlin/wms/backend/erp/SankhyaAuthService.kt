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
import java.time.Duration
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
    /**
     * Renova antes de vencer: 5 min ou 1/5 da validade, o que for MENOR. Com margem fixa de 5 min e token de 300 s
     * (o que o gateway devolve hoje — log do portal GBI, 05/11/2026), o cache nunca valia e TODA chamada ao Sankhya
     * autenticava de novo; numa instabilidade do /authenticate, a Torre inteira caía junto.
     */
    private const val MARGEM_RENOVACAO_MAX_MS = 5 * 60 * 1000L
    private const val TENTATIVAS_MAXIMAS = 3
    private const val DELAY_INICIAL_MS = 1000L
    /** Depois de falhar as tentativas, não bate de novo no /authenticate por este tempo — falha na hora (fila não trava). */
    private const val ESPERA_APOS_FALHA_MS = 30_000L
    private val TIMEOUT_CONEXAO: Duration = Duration.ofSeconds(10)
    private val TIMEOUT_REQUISICAO: Duration = Duration.ofSeconds(20)

    private data class TokenCacheado(val token: String, val renovarEm: Instant)
    private data class FalhaRecente(val ate: Instant, val motivo: String)

    private val cache = ConcurrentHashMap<String, TokenCacheado>()
    private val falhas = ConcurrentHashMap<String, FalhaRecente>()
    private val locksPortenant = ConcurrentHashMap<String, Mutex>()

    private val http = HttpClient.newBuilder().connectTimeout(TIMEOUT_CONEXAO).build()
    private val json = Json { ignoreUnknownKeys = true }

    @Serializable
    private data class RespostaAutenticacao(
        val access_token: String? = null,
        val expires_in: Long? = null,
    )

    /** Momento de renovar um token que vale [validadeSeg] segundos a partir de [agora]. */
    internal fun momentoRenovacao(agora: Instant, validadeSeg: Long): Instant {
        val validadeMs = validadeSeg.coerceAtLeast(1) * 1000
        return agora.plusMillis(validadeMs - minOf(MARGEM_RENOVACAO_MAX_MS, validadeMs / 5))
    }

    suspend fun obterTokenValido(tenantSlug: String): String {
        cache[tenantSlug]?.let { cached ->
            if (Instant.now().isBefore(cached.renovarEm)) return cached.token
        }
        falhaRecente(tenantSlug)?.let { throw it }

        val mutex = locksPortenant.computeIfAbsent(tenantSlug) { Mutex() }
        return mutex.withLock {
            // Outra corrotina pode ter renovado (ou falhado) enquanto esperávamos o lock.
            cache[tenantSlug]?.let { cached ->
                if (Instant.now().isBefore(cached.renovarEm)) return@withLock cached.token
            }
            falhaRecente(tenantSlug)?.let { throw it }
            try {
                autenticarComRetry(tenantSlug).also {
                    falhas.remove(tenantSlug)?.let { println("INFO: autenticação Sankhya do tenant '$tenantSlug' restabelecida") }
                }
            } catch (e: SankhyaAuthException) {
                falhas[tenantSlug] = FalhaRecente(Instant.now().plusMillis(ESPERA_APOS_FALHA_MS), e.message ?: "erro")
                println("AVISO: ${e.message} — novas tentativas em ${ESPERA_APOS_FALHA_MS / 1000}s")
                throw e
            }
        }
    }

    private fun falhaRecente(tenantSlug: String): SankhyaAuthException? {
        val f = falhas[tenantSlug] ?: return null
        if (Instant.now().isAfter(f.ate)) return null
        return SankhyaAuthException("Autenticação no Sankhya em espera após falha recente (tenant '$tenantSlug'): ${f.motivo}")
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
        // A causa vai NA mensagem: antes só aparecia "após 3 tentativas" e não dava pra saber se era 400, timeout ou rede.
        val causa = ultimoErro?.let { it.message ?: it::class.simpleName } ?: "erro desconhecido"
        throw SankhyaAuthException(
            "Falha ao autenticar no Sankhya para tenant '$tenantSlug' após $TENTATIVAS_MAXIMAS tentativas: ${causa.take(500)}",
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
            .timeout(TIMEOUT_REQUISICAO)
            .POST(HttpRequest.BodyPublishers.ofString(form))
        credenciais.xToken?.let { requestBuilder.header("X-Token", it) }

        val response = try {
            withContext(Dispatchers.IO) {
                http.send(requestBuilder.build(), HttpResponse.BodyHandlers.ofString())
            }
        } catch (e: HttpTimeoutException) {
            throw SankhyaAuthException("Timeout autenticando no Sankhya para tenant '$tenantSlug'", e)
        } catch (e: java.io.IOException) {
            throw SankhyaAuthException("Erro de rede autenticando no Sankhya para tenant '$tenantSlug': ${e.message ?: e::class.simpleName}", e)
        }

        if (response.statusCode() !in 200..299) {
            throw SankhyaAuthException("Sankhya respondeu ${response.statusCode()} para tenant '$tenantSlug': ${response.body()}")
        }

        val corpo = json.decodeFromString(RespostaAutenticacao.serializer(), response.body())
        val token = corpo.access_token
            ?: throw SankhyaAuthException("Sankhya não retornou access_token para tenant '$tenantSlug'")

        val validade = corpo.expires_in ?: 3600
        val anterior = cache[tenantSlug]
        cache[tenantSlug] = TokenCacheado(token, momentoRenovacao(Instant.now(), validade))
        if (anterior == null) println("INFO: token Sankhya do tenant '$tenantSlug' válido por ${validade}s")
        return token
    }

    private fun enc(valor: String): String =
        java.net.URLEncoder.encode(valor, Charsets.UTF_8)
}
