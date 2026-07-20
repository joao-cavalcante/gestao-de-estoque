package wms.backend.tipooperacao

import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.javatime.timestamp

/** app.tipos_operacao — ver db/migrations/V16__tipo_operacao.sql e V17 (remoção do dhalter_sankhya). */
object TipoOperacaoTable : Table("app.tipos_operacao") {
    val id = uuid("id")
    val tenantId = uuid("tenant_id")
    val codtop = integer("codtop")
    val descricao = text("descricao")
    val nucco = integer("nucco").nullable()
    val localAtualizadoEm = timestamp("local_atualizado_em")

    override val primaryKey = PrimaryKey(id)
}
