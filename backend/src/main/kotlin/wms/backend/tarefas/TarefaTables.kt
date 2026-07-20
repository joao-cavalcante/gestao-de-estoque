package wms.backend.tarefas

import org.jetbrains.exposed.sql.Column
import org.jetbrains.exposed.sql.ColumnType
import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.javatime.timestamp
import org.postgresql.util.PGobject

/**
 * O projeto não depende do módulo exposed-json — em vez de puxar mais uma
 * dependência só por isto, um ColumnType customizado que liga na String
 * (JSON já serializado pelo chamador) e faz o bind como PGobject tipo
 * "jsonb", que é como o driver Postgres exige pra essa coluna.
 */
class JsonbColumnType : ColumnType<String>() {
    override fun sqlType(): String = "jsonb"

    override fun valueFromDB(value: Any): String = when (value) {
        is PGobject -> value.value ?: "{}"
        is String -> value
        else -> value.toString()
    }

    override fun notNullValueToDB(value: String): Any {
        val obj = PGobject()
        obj.type = "jsonb"
        obj.value = value
        return obj
    }
}

fun Table.jsonb(name: String): Column<String> = registerColumn(name, JsonbColumnType())

/**
 * app.tarefas — ver db/migrations/V3__tarefas_sync.sql pro schema real e o
 * raciocínio de reconciliação (Sankhya é fonte de verdade pra dados/status
 * de negócio; esta tabela é fonte de verdade pra EXECUÇÃO operacional).
 */
object TarefasTable : Table("app.tarefas") {
    val id = uuid("id")
    val tenantId = uuid("tenant_id")
    val nunota = integer("nunota")
    val tipo = text("tipo")
    val statusSankhya = text("status_sankhya")
    val statusOperacional = text("status_operacional")
    val dados = jsonb("dados")
    val operadorExecucao = text("operador_execucao").nullable()
    val iniciadoEm = timestamp("iniciado_em").nullable()
    val concluidoEm = timestamp("concluido_em").nullable()
    val sankhyaAtualizadoEm = timestamp("sankhya_atualizado_em")
    val localAtualizadoEm = timestamp("local_atualizado_em")
    val pendenteWriteBack = bool("pendente_write_back")

    override val primaryKey = PrimaryKey(id)
}

object TarefasAuditoriaTable : Table("app.tarefas_auditoria") {
    val id = uuid("id")
    val tenantId = uuid("tenant_id")
    val nunota = integer("nunota")
    val statusAnterior = text("status_anterior")
    val statusNovo = text("status_novo")
    val origem = text("origem")
    val motivo = text("motivo")
    val criadoEm = timestamp("criado_em")

    override val primaryKey = PrimaryKey(id)
}

