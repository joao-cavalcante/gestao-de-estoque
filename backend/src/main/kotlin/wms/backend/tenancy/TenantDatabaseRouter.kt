package wms.backend.tenancy

import org.jetbrains.exposed.sql.Database
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import wms.backend.Database as SharedDatabase

/**
 * Resolve, por tenant, qual conexão física de banco usar: a compartilhada
 * (padrão, `tier='shared'`) ou uma dedicada (própria, pequena, criada sob
 * demanda a partir de `dedicated_db_url`) — mesmo código de negócio nos
 * dois casos, só muda a `Database` que a transação abre.
 *
 * IMPORTANTE: isto só resolve o schema `app.*` (dados de NEGÓCIO do
 * tenant). `tenancy.*` (tenants, erp_connections, sync_estado) é plano de
 * controle e vive SEMPRE no banco central, mesmo pra tenant dedicated —
 * é o próprio TenantRepository/SyncEstadoRepository que precisa saber onde
 * está cada tenant antes de rotear, então não faria sentido essa
 * informação também estar espalhada.
 */
object TenantDatabaseRouter {
    private val dedicados = ConcurrentHashMap<UUID, Database>()

    fun resolver(tenantId: UUID): Database {
        val tenant = TenantRepository.buscarPorId(tenantId) ?: return SharedDatabase.shared
        if (tenant.tier != "dedicated" || tenant.dedicatedDbUrl.isNullOrBlank()) {
            return SharedDatabase.shared
        }

        return dedicados.computeIfAbsent(tenantId) {
            val user = System.getenv("WMS_DB_USER") ?: "wms_app"
            val password = System.getenv("WMS_DB_PASSWORD") ?: "CHANGE_ME_EM_PRODUCAO"
            SharedDatabase.conectar(tenant.dedicatedDbUrl!!, user, password, poolSize = 5)
        }
    }
}
