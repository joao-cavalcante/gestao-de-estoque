package wms.backend.tenancy

import org.jetbrains.exposed.sql.Table

/**
 * tenancy.user_login_lookup — e-mail (globalmente único, decisão de
 * produto) -> tenant_id. Sem RLS: login por e-mail precisa resolver o
 * tenant ANTES de existir qualquer contexto de RLS (mesmo raciocínio de
 * tenancy.sync_estado).
 */
object UserLoginLookupTable : Table("tenancy.user_login_lookup") {
    val email = text("email")
    val tenantId = uuid("tenant_id")
    val userId = uuid("user_id")

    override val primaryKey = PrimaryKey(email)
}
