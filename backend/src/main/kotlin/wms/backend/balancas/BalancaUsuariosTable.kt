package wms.backend.balancas

import org.jetbrains.exposed.sql.Table

/** app.balanca_usuarios — vínculo balança↔usuário (portado do legado: operador só vê as suas, com fallback pra todas as ativas se sem vínculo nenhum). */
object BalancaUsuariosTable : Table("app.balanca_usuarios") {
    val id = uuid("id")
    val tenantId = uuid("tenant_id")
    val balancaId = uuid("balanca_id")
    val usuarioId = uuid("usuario_id")

    override val primaryKey = PrimaryKey(id)
}
