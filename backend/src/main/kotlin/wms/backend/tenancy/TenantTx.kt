package wms.backend.tenancy

import org.jetbrains.exposed.sql.Transaction
import org.jetbrains.exposed.sql.transactions.transaction
import java.util.UUID

/**
 * Helper ÚNICO pra toda operação de negócio escopada a um tenant — resolve
 * a conexão certa (compartilhada ou dedicada, via TenantDatabaseRouter),
 * seta o contexto de RLS (`SET LOCAL app.tenant_id`) e um teto de tempo de
 * execução (`SET LOCAL statement_timeout`) — pra uma query travada de um
 * tenant não segurar conexão do pool compartilhado indefinidamente.
 *
 * Substitui os `comTenant`/`comTenantRetorna` que estavam duplicados em
 * TarefasRepository e SyncEstadoRepository.
 */
object TenantTx {
    private const val TIMEOUT_PADRAO_MS = 5_000

    fun <T> run(tenantId: UUID, statementTimeoutMs: Int = TIMEOUT_PADRAO_MS, block: Transaction.() -> T): T {
        val db = TenantDatabaseRouter.resolver(tenantId)
        return transaction(db) {
            exec("SET LOCAL app.tenant_id = '$tenantId'")
            exec("SET LOCAL statement_timeout = '${statementTimeoutMs}ms'")
            block()
        }
    }
}
