package wms.backend.tipooperacao

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** Mesma estrutura de wms.backend.configconferencia.ConfigConferenciaSyncWorker. */
object TipoOperacaoSyncWorker {
    private val escopo = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private const val INTERVALO_MS = 15 * 60_000L

    fun iniciar() {
        escopo.launch { loop() }
    }

    private suspend fun loop() {
        while (escopo.isActive) {
            try {
                TipoOperacaoSyncService.sincronizarTodosOsTenants()
            } catch (e: Exception) {
                // não derruba o worker por uma falha pontual — próximo ciclo tenta de novo
            }
            delay(INTERVALO_MS)
        }
    }
}
