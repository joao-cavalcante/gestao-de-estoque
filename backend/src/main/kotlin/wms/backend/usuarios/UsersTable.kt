package wms.backend.usuarios

import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.javatime.timestamp

/** app.users — RLS, ver db/migrations/V2__app_users_rls_pattern.sql + V5 (reset_token). */
object UsersTable : Table("app.users") {
    val id = uuid("id")
    val tenantId = uuid("tenant_id")
    val codigoErp = integer("codigo_erp").nullable()
    val nome = text("nome")
    val email = text("email")
    val senhaHash = text("senha_hash").nullable()
    /** ADMINISTRADOR | OPERADOR | ESTACAO (V35 — login fixo de PC, ex.: "Stage1"; sem regra especial de permissão, só rótulo). */
    val perfil = text("perfil")
    val ativo = bool("ativo")
    val criadoEm = timestamp("criado_em")
    val atualizadoEm = timestamp("atualizado_em")
    val resetToken = text("reset_token").nullable()
    val resetTokenExpira = timestamp("reset_token_expira").nullable()
    /** Código do crachá (login nas estações de pesagem) — único por tenant, ver V32. */
    val crachaoCodigo = text("crachao_codigo").nullable()

    override val primaryKey = PrimaryKey(id)
}
