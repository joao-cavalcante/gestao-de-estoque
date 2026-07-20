package wms.backend.tarefas

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Pool FIXO de workers reivindicando trabalho de `tenancy.sync_estado` via
 * `SELECT ... FOR UPDATE SKIP LOCKED` — substitui o modelo antigo de
 * "1 corrotina por tenant, vivendo pra sempre na memória do processo"
 * (TarefaSyncScheduler, removido).
 *
 * Por que isto e não aquilo:
 *  - Concorrência real NUNCA passa de `WMS_SYNC_WORKERS`, independente de
 *    ter 10 ou 10.000 tenants — o orçamento de recursos é explícito.
 *  - `SKIP LOCKED` coordena múltiplas INSTÂNCIAS do backend de graça: duas
 *    instâncias rodando este mesmo pool nunca reivindicam o mesmo tenant ao
 *    mesmo tempo, sem precisar de um coordenador externo (Redis/Zookeeper).
 *  - Estado da fila vive no Postgres (`tenancy.sync_estado`), não na
 *    memória do processo — sobrevive a restart.
 */
object SyncWorkerPool {
    private val escopo = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private const val POLL_QUANDO_VAZIO_MS = 2_000L
    private const val INTERVALO_PROVISIONADOR_MS = 30_000L

    fun iniciar() {
        val numWorkers = System.getenv("WMS_SYNC_WORKERS")?.toIntOrNull() ?: 8
        repeat(numWorkers) { indice ->
            escopo.launch { loopWorker(indice) }
        }
        escopo.launch { loopProvisionador() }
    }

    private suspend fun loopWorker(indice: Int) {
        while (escopo.isActive) {
            val claim = withContext(Dispatchers.IO) { SyncEstadoRepository.reivindicarProximo() }

            if (claim == null) {
                delay(POLL_QUANDO_VAZIO_MS)
                continue
            }

            try {
                TarefaSyncService.sincronizarTenant(claim.slug, claim.tenantId)
                withContext(Dispatchers.IO) {
                    SyncEstadoRepository.marcarSucessoESoltar(claim.tenantId, claim.intervaloSegundos)
                }
            } catch (e: Exception) {
                // Resiliência: falha (Sankhya fora do ar, auth, etc.) nunca
                // derruba o worker nem os outros tenants — só entra em
                // backoff exponencial (circuit breaker) e o próximo ciclo
                // tenta de novo.
                withContext(Dispatchers.IO) {
                    SyncEstadoRepository.marcarFalhaEBackoff(
                        claim.tenantId,
                        e.message ?: e::class.simpleName ?: "erro desconhecido",
                    )
                }
            }
        }
    }

    /** Leve, 1 statement — NÃO uma corrotina por tenant. Garante fila pra tenant novo. */
    private suspend fun loopProvisionador() {
        while (escopo.isActive) {
            try {
                withContext(Dispatchers.IO) { SyncEstadoRepository.garantirLinhasParaTenantsAtivos() }
            } catch (e: Exception) {
                // não derruba o provisionador por um erro pontual
            }
            delay(INTERVALO_PROVISIONADOR_MS)
        }
    }
}
