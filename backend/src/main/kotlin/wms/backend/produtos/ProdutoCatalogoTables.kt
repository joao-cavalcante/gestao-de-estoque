package wms.backend.produtos

import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.javatime.timestamp

/** app.produtos_cache — ver db/migrations/V19__produto_catalogo_cache.sql. */
object ProdutosCacheTable : Table("app.produtos_cache") {
    val id = uuid("id")
    val tenantId = uuid("tenant_id")
    val codprod = integer("codprod")
    val descrprod = text("descrprod")
    val compldesc = text("compldesc").nullable()
    val marca = text("marca").nullable()
    val referencia = text("referencia").nullable()
    val tipcontest = text("tipcontest").nullable()
    val liscontest = text("liscontest").nullable()
    /** Cru, como o Sankhya devolve — mesmo motivo documentado na V16 (sem parser de data confirmado). */
    val dtalterSankhya = text("dtalter_sankhya").nullable()
    val localAtualizadoEm = timestamp("local_atualizado_em")

    override val primaryKey = PrimaryKey(id)
}

/**
 * app.codigos_barra_cache — ver db/migrations/V19/V20. `codvol` é '' (não null) de
 * propósito — NULL não bate em unique constraint no Postgres, o que quebrava o
 * upsert pra produto sem unidade alternativa (o caso comum).
 */
object CodigosBarraCacheTable : Table("app.codigos_barra_cache") {
    val id = uuid("id")
    val tenantId = uuid("tenant_id")
    val codprod = integer("codprod")
    val codvol = text("codvol")
    val codbarra = text("codbarra")
    val dhalterSankhya = text("dhalter_sankhya").nullable()
    val localAtualizadoEm = timestamp("local_atualizado_em")

    override val primaryKey = PrimaryKey(id)
}

/**
 * app.volumes_alternativos_cache — ver V19. Sem campo de auditoria (TGFVOA não tem DHALTER):
 * populada só sob demanda (cache pra sempre, sem sync periódico — ver ProdutoImagemService
 * pro mesmo padrão já usado nesta pacote).
 */
object VolumesAlternativosCacheTable : Table("app.volumes_alternativos_cache") {
    val id = uuid("id")
    val tenantId = uuid("tenant_id")
    val codprod = integer("codprod")
    /** '' (não null) — mesmo motivo do CodigosBarraCacheTable.codvol. */
    val codvol = text("codvol")
    val controle = text("controle").nullable()
    val divideMultiplica = text("divide_multiplica").nullable()
    val quantidade = text("quantidade").nullable()
    val codbarra = text("codbarra")
    val localAtualizadoEm = timestamp("local_atualizado_em")

    override val primaryKey = PrimaryKey(id)
}
