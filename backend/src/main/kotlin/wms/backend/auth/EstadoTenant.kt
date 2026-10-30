package wms.backend.auth

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import wms.backend.tenancy.TenantRepository
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Situação do tenant conferida em TODA rota autenticada (exigirAuth) — token emitido antes da suspensão perde o
 * acesso sem esperar o `exp` de 8h. Pra não consultar o banco a cada requisição, a situação fica em memória por
 * [TTL_MS]: suspender no Painel Master invalida na hora nesta réplica (MasterRoutes /status) e, em outra réplica,
 * vale em no máximo esse tempo. Tenant inexistente = sem acesso.
 */
object EstadoTenant {
    const val TTL_MS = 15_000L
    val STATUS_COM_ACESSO = setOf("active", "trial")

    /** Situação atual no banco (null = tenant não existe). Trocado nos testes. */
    @Volatile
    var consultar: (UUID) -> String? = TenantRepository::statusDe

    @Volatile
    var relogio: () -> Long = System::currentTimeMillis

    private class Lido(val status: String?, val em: Long)

    private val cache = ConcurrentHashMap<UUID, Lido>()

    suspend fun permiteAcesso(tenantId: UUID): Boolean {
        val agora = relogio()
        val lido = cache[tenantId]?.takeIf { agora - it.em < TTL_MS }
            ?: Lido(withContext(Dispatchers.IO) { consultar(tenantId) }, agora).also { cache[tenantId] = it }
        return lido.status in STATUS_COM_ACESSO
    }

    fun invalidar(tenantId: UUID) {
        cache.remove(tenantId)
    }

    fun limpar() = cache.clear()
}
