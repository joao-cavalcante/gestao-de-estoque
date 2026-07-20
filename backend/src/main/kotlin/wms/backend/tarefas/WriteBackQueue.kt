package wms.backend.tarefas

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

/**
 * STUB DELIBERADO: ainda não temos identificada a rotina/TOP real do
 * Sankhya pra confirmar uma conferência via API (isso não foi especificado
 * e não deve ser adivinhado contra um sistema de produção real — uma
 * chamada de ESCRITA errada é bem mais arriscada que uma de leitura).
 *
 * Quando essa rotina for definida, o único ponto a trocar é o corpo desta
 * função — todo o resto (retry, fila, marcação de pendente_write_back)
 * já está pronto e não muda.
 */
object SankhyaWriteBackService {
    suspend fun confirmarConferencia(tenantSlug: String, nunota: Long) {
        // TODO: chamar a rotina/TOP real do Sankhya que finaliza a
        // conferência (nunca ajuste direto de estoque). Por enquanto,
        // como não temos esse contrato, apenas simula sucesso imediato —
        // ver aviso acima.
    }
}

/**
 * Fila de write-back com retry (substitui o BullMQ do pedido original —
 * não há Node/Redis nesta stack; aqui é um canal de corrotinas + backoff
 * exponencial, com `pendente_write_back=true` na própria tabela agindo
 * como o registro durável: se o processo cair, `retomarPendentes()` no
 * boot reencontra o trabalho que ficou faltando).
 */
object WriteBackQueue {
    private val escopo = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private const val TENTATIVAS_MAXIMAS = 5
    private const val DELAY_INICIAL_MS = 2_000L

    fun enfileirar(tenantSlug: String, tenantId: UUID, nunota: Long) {
        escopo.launch { processarComRetry(tenantSlug, tenantId, nunota) }
    }

    /** Chamar no boot da aplicação — retoma write-backs que ficaram pendentes de um restart. */
    fun retomarPendentes(tenantSlug: String, tenantId: UUID) {
        escopo.launch {
            val pendentes = withContext(Dispatchers.IO) { TarefasRepository.listarPendentesWriteBack(tenantId) }
            pendentes.forEach { nunota -> processarComRetry(tenantSlug, tenantId, nunota) }
        }
    }

    private suspend fun processarComRetry(tenantSlug: String, tenantId: UUID, nunota: Long) {
        var delayMs = DELAY_INICIAL_MS
        repeat(TENTATIVAS_MAXIMAS) { tentativa ->
            try {
                SankhyaWriteBackService.confirmarConferencia(tenantSlug, nunota)
                withContext(Dispatchers.IO) { TarefasRepository.marcarWriteBackConfirmado(tenantId, nunota) }
                return
            } catch (e: Exception) {
                if (tentativa < TENTATIVAS_MAXIMAS - 1) {
                    delay(delayMs)
                    delayMs *= 2
                }
            }
        }
        // Esgotou tentativas: pendente_write_back continua true — o próximo
        // boot (retomarPendentes) ou uma nova ação do operador tentam de novo.
    }
}
