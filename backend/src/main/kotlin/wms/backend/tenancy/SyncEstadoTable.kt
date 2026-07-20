package wms.backend.tenancy

import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.javatime.timestamp

/**
 * tenancy.sync_estado — fila/estado do job de sync por tenant (ver
 * db/migrations/V4__fila_sync_e_tenant_router.sql). Vive em `tenancy.*`
 * (sem RLS) porque é dado de ORQUESTRAÇÃO cross-tenant — o SyncWorkerPool
 * precisa varrer o estado de TODOS os tenants pra decidir qual reivindicar
 * via `SELECT ... FOR UPDATE SKIP LOCKED`, o que é incompatível com RLS
 * fail-closed (não dá pra "logar como um tenant" antes de saber qual é).
 */
object SyncEstadoTable : Table("tenancy.sync_estado") {
    val tenantId = uuid("tenant_id")
    val intervaloSegundos = integer("intervalo_segundos")
    val ultimoSyncEm = timestamp("ultimo_sync_em").nullable()
    val ultimoSyncSucessoEm = timestamp("ultimo_sync_sucesso_em").nullable()
    val ultimoErro = text("ultimo_erro").nullable()
    val proximoRunEm = timestamp("proximo_run_em")
    val falhasConsecutivas = integer("falhas_consecutivas")
    val bloqueadoAte = timestamp("bloqueado_ate").nullable()

    override val primaryKey = PrimaryKey(tenantId)
}
