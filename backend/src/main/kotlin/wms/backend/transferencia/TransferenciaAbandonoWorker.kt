package wms.backend.transferencia

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import wms.backend.tenancy.TenantRepository
import java.util.UUID

/**
 * Varredura periódica (não 1 corrotina por tenant — mesmo raciocínio de
 * SyncWorkerPool: poucos tenants hoje, mas o custo por ciclo é 1 SELECT +
 * updates pontuais, então iterar `TenantRepository.listar()` inteiro a cada
 * ciclo é aceitável e muito mais simples que um pool dedicado).
 *
 * Marca como 'abandonada' só rascunhos 'aberta' SEM NENHUM item — uma
 * transferência com itens mas parada (ex: operador trocou de aba no meio da
 * bipagem) fica de fora de propósito, ver ressalva no prompt que motivou
 * isto: só o rascunho vazio (origem/destino digitado e esquecido) é o
 * candidato claro a limpeza automática.
 */
object TransferenciaAbandonoWorker {
    private val escopo = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private const val INTERVALO_MS = 5 * 60_000L
    private const val MINUTOS_LIMITE = 30L

    fun iniciar() {
        escopo.launch { loop() }
    }

    private suspend fun loop() {
        while (escopo.isActive) {
            try {
                val tenants = withContext(Dispatchers.IO) { TenantRepository.listar() }
                tenants.mapNotNull { it.id }.forEach { tenantId ->
                    withContext(Dispatchers.IO) {
                        TransferenciaRepository.marcarAbandonadas(UUID.fromString(tenantId), MINUTOS_LIMITE)
                    }
                }
            } catch (e: Exception) {
                // não derruba o worker por uma falha pontual — próximo ciclo tenta de novo
            }
            delay(INTERVALO_MS)
        }
    }
}
