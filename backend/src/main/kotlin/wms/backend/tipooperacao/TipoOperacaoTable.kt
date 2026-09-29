package wms.backend.tipooperacao

import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.javatime.timestamp

/** app.tipos_operacao — ver db/migrations/V16__tipo_operacao.sql, V17 (remoção do dhalter_sankhya) e V49 (tipmov). */
object TipoOperacaoTable : Table("app.tipos_operacao") {
    val id = uuid("id")
    val tenantId = uuid("tenant_id")
    val codtop = integer("codtop")
    val descricao = text("descricao")
    val nucco = integer("nucco").nullable()
    /** TGFCAB.TIPMOV das notas desse TOP — 'C'/'O' compra, 'V'/'P' venda (V49). */
    val tipmov = text("tipmov").nullable()
    val localAtualizadoEm = timestamp("local_atualizado_em")

    override val primaryKey = PrimaryKey(id)
}

/**
 * app.tipo_operacao_config (V49) — configuração do WMS por TOP, separada do espelho (que é
 * apagado e regravado a cada sync). Sem linha = padrão (conferência por etapa ligada).
 */
object TipoOperacaoConfigTable : Table("app.tipo_operacao_config") {
    val tenantId = uuid("tenant_id")
    val codtop = integer("codtop")
    val conferenciaPorEtapa = bool("conferencia_por_etapa")
    val atualizadoEm = timestamp("atualizado_em")

    override val primaryKey = PrimaryKey(tenantId, codtop)
}
