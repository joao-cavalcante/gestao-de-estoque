package wms.backend.inventario

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

/**
 * STUB DELIBERADO — mesma ressalva de wms.backend.transferencia.SankhyaTransferenciaWriteBackService:
 * ainda não temos a rotina/TOP real do Sankhya pra gravar um ajuste de inventário (não deve
 * ser adivinhado contra um sistema de produção real). Quando esse contrato existir, só o
 * corpo desta função muda — fila/retry/marcação já prontos.
 */
object SankhyaInventarioWriteBackService {
    suspend fun ajustar(tenantId: UUID, inventarioId: UUID) {
        // TODO: chamar a rotina real do Sankhya que grava o ajuste de inventário
        // (nunca escrita direta em TGFEST).
    }
}

/** Mesma estrutura de wms.backend.transferencia.TransferenciaWriteBackQueue — fire-and-forget + retry exponencial. */
object InventarioWriteBackQueue {
    private val escopo = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private const val TENTATIVAS_MAXIMAS = 5
    private const val DELAY_INICIAL_MS = 2_000L

    fun enfileirar(tenantId: UUID, inventarioId: UUID) {
        escopo.launch { processarComRetry(tenantId, inventarioId) }
    }

    fun retomarPendentes(tenantId: UUID) {
        escopo.launch {
            val pendentes = withContext(Dispatchers.IO) { InventarioRepository.listarPendentesWriteBack(tenantId) }
            pendentes.forEach { inventarioId -> processarComRetry(tenantId, inventarioId) }
        }
    }

    private suspend fun processarComRetry(tenantId: UUID, inventarioId: UUID) {
        var delayMs = DELAY_INICIAL_MS
        repeat(TENTATIVAS_MAXIMAS) { tentativa ->
            try {
                SankhyaInventarioWriteBackService.ajustar(tenantId, inventarioId)
                withContext(Dispatchers.IO) { InventarioRepository.marcarWriteBackConcluido(tenantId, inventarioId) }
                return
            } catch (e: Exception) {
                if (tentativa < TENTATIVAS_MAXIMAS - 1) {
                    delay(delayMs)
                    delayMs *= 2
                }
            }
        }
    }
}
