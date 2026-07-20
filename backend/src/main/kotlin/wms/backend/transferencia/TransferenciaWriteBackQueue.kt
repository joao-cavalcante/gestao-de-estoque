package wms.backend.transferencia

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

/**
 * STUB DELIBERADO — mesma ressalva de wms.backend.tarefas.SankhyaWriteBackService:
 * ainda não temos a rotina/TOP real do Sankhya pra gravar uma transferência entre
 * locais (não deve ser adivinhado contra um sistema de produção real). Quando esse
 * contrato existir, só o corpo desta função muda — fila/retry/marcação já prontos.
 */
object SankhyaTransferenciaWriteBackService {
    suspend fun confirmarTransferencia(tenantId: UUID, transferenciaId: UUID) {
        // TODO: chamar a rotina real do Sankhya (provavelmente via SankhyaSpClient,
        // usando o modelo_nota — codtop/codemp/codnat — resolvido na criação).
    }
}

/** Mesma estrutura de wms.backend.tarefas.WriteBackQueue — fire-and-forget + retry exponencial. */
object TransferenciaWriteBackQueue {
    private val escopo = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private const val TENTATIVAS_MAXIMAS = 5
    private const val DELAY_INICIAL_MS = 2_000L

    fun enfileirar(tenantId: UUID, transferenciaId: UUID) {
        escopo.launch { processarComRetry(tenantId, transferenciaId) }
    }

    /** Chamar no boot — retoma write-backs que ficaram pendentes de um restart. */
    fun retomarPendentes(tenantId: UUID) {
        escopo.launch {
            val pendentes = withContext(Dispatchers.IO) { TransferenciaRepository.listarPendentesWriteBack(tenantId) }
            pendentes.forEach { transferenciaId -> processarComRetry(tenantId, transferenciaId) }
        }
    }

    private suspend fun processarComRetry(tenantId: UUID, transferenciaId: UUID) {
        var delayMs = DELAY_INICIAL_MS
        repeat(TENTATIVAS_MAXIMAS) { tentativa ->
            try {
                SankhyaTransferenciaWriteBackService.confirmarTransferencia(tenantId, transferenciaId)
                withContext(Dispatchers.IO) { TransferenciaRepository.marcarWriteBackConcluido(tenantId, transferenciaId) }
                return
            } catch (e: Exception) {
                if (tentativa < TENTATIVAS_MAXIMAS - 1) {
                    delay(delayMs)
                    delayMs *= 2
                }
            }
        }
        // Esgotou tentativas: pendente_write_back continua true — próximo boot ou nova ação tentam de novo.
    }
}
