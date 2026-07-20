package wms.backend.tenancy

import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.javatime.timestamp

/**
 * Espelha exatamente db/migrations/V1__tenancy_foundation.sql — este objeto
 * NÃO cria/altera schema (migração é feita via SQL puro, ver db/README.md),
 * só descreve as tabelas já existentes pro Exposed montar queries.
 */
object TenantsTable : Table("tenancy.tenants") {
    val id = uuid("id")
    val slug = text("slug")
    val nome = text("nome")
    val tier = text("tier")
    val status = text("status")
    val dedicatedDbUrl = text("dedicated_db_url").nullable()
    val criadoEm = timestamp("criado_em")
    val atualizadoEm = timestamp("atualizado_em")

    override val primaryKey = PrimaryKey(id)
}

object ErpConnectionsTable : Table("tenancy.erp_connections") {
    val id = uuid("id")
    val tenantId = uuid("tenant_id").references(TenantsTable.id)
    val erpType = text("erp_type")
    val baseUrl = text("base_url")
    val gatewayPath = text("gateway_path").nullable()
    val dialect = text("dialect").nullable()
    // credenciais é jsonb no banco; lido/escrito aqui como texto JSON cru
    // (serialização/desserialização fica por conta da camada de rotas) pra
    // não precisar de um column-type jsonb dedicado do Exposed nesta fase
    // inicial. Postgres aceita texto JSON válido em coluna jsonb via cast
    // implícito de string literal.
    val credenciais = text("credenciais")
    val ativo = bool("ativo")
    val criadoEm = timestamp("criado_em")
    val atualizadoEm = timestamp("atualizado_em")

    override val primaryKey = PrimaryKey(id)
}
