package wms.backend.produtos

import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.javatime.timestamp

/** app.produto_imagem_cache — ver db/migrations/V11__produto_imagem_cache.sql. */
object ProdutoImagemCacheTable : Table("app.produto_imagem_cache") {
    val tenantId = uuid("tenant_id")
    val codprod = integer("codprod")
    val imagem = text("imagem").nullable()
    val atualizadoEm = timestamp("atualizado_em")

    override val primaryKey = PrimaryKey(tenantId, codprod)
}
