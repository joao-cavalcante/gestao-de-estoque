package wms.backend.configconferencia

import kotlinx.serialization.Serializable
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.javatime.timestamp
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.update
import wms.backend.tenancy.TenantTx
import java.time.Instant
import java.util.UUID

/** app.conferencia_opcoes (V70) — opções da Torre por NUCCO que não existem na TGFCCO. */
object ConferenciaOpcoesTable : Table("app.conferencia_opcoes") {
    val tenantId = uuid("tenant_id")
    val nucco = integer("nucco")
    val pedirQtdComercial = bool("pedir_qtd_comercial")
    val atualizadoEm = timestamp("atualizado_em")

    override val primaryKey = PrimaryKey(tenantId, nucco)
}

/**
 * [pedirQtdComercial] = o pop-up de pesagem pede a quantidade na unidade do pedido (CX, UN…) quando o pedido do
 * pesável não é em KG (V61). Compra da Negri não pede (05/11/2026). `configurada` = false: NUCCO sem linha, padrão.
 */
@Serializable
data class OpcoesConferenciaDto(
    val pedirQtdComercial: Boolean = true,
    val configurada: Boolean = true,
)

object ConferenciaOpcoesRepository {
    val PADRAO = OpcoesConferenciaDto(pedirQtdComercial = true, configurada = false)

    fun buscar(tenantId: UUID, nucco: Int?): OpcoesConferenciaDto {
        if (nucco == null) return PADRAO
        return TenantTx.run(tenantId) {
            ConferenciaOpcoesTable.selectAll()
                .where { (ConferenciaOpcoesTable.tenantId eq tenantId) and (ConferenciaOpcoesTable.nucco eq nucco) }
                .singleOrNull()
                ?.let { OpcoesConferenciaDto(pedirQtdComercial = it[ConferenciaOpcoesTable.pedirQtdComercial]) }
        } ?: PADRAO
    }

    fun salvar(tenantId: UUID, nucco: Int, opcoes: OpcoesConferenciaDto): Unit = TenantTx.run(tenantId) {
        val agora = Instant.now()
        val atualizou = ConferenciaOpcoesTable.update({
            (ConferenciaOpcoesTable.tenantId eq tenantId) and (ConferenciaOpcoesTable.nucco eq nucco)
        }) {
            it[pedirQtdComercial] = opcoes.pedirQtdComercial
            it[atualizadoEm] = agora
        }
        if (atualizou == 0) {
            ConferenciaOpcoesTable.insert {
                it[ConferenciaOpcoesTable.tenantId] = tenantId
                it[ConferenciaOpcoesTable.nucco] = nucco
                it[pedirQtdComercial] = opcoes.pedirQtdComercial
                it[atualizadoEm] = agora
            }
        }
    }
}
