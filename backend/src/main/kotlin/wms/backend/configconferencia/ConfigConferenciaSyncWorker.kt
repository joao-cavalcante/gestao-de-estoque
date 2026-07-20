package wms.backend.configconferencia

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** Mesma estrutura de wms.backend.transferencia.TransferenciaAbandonoWorker — varredura periódica, não 1 corrotina por tenant. */
object ConfigConferenciaSyncWorker {
    private val escopo = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private const val INTERVALO_MS = 15 * 60_000L

    fun iniciar() {
        escopo.launch { loop() }
    }

    private suspend fun loop() {
        while (escopo.isActive) {
            try {
                ConfigConferenciaSyncService.sincronizarTodosOsTenants()
            } catch (e: Exception) {
                // não derruba o worker por uma falha pontual — próximo ciclo tenta de novo
            }
            delay(INTERVALO_MS)
        }
    }
}
