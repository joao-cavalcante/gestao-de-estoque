package wms.backend.configconferencia

import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.javatime.timestamp
import wms.backend.tarefas.jsonb

/** app.config_conferencia — ver db/migrations/V15__config_conferencia.sql. */
object ConfigConferenciaTable : Table("app.config_conferencia") {
    val id = uuid("id")
    val tenantId = uuid("tenant_id")
    val nucco = integer("nucco")
    val descricao = text("descricao")
    /** Os 55 campos nativos da TGFCCO, chave = NOMECAMPO. Catálogo (rótulo/aba/dependências) vive só no frontend. */
    val campos = jsonb("campos")
    val sankhyaAtualizadoEm = timestamp("sankhya_atualizado_em").nullable()
    val localAtualizadoEm = timestamp("local_atualizado_em")

    override val primaryKey = PrimaryKey(id)
}
