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
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID

/** app.conferencia_tolerancia (V50) — tolerância de peso do WMS por NUCCO. */
object ConferenciaToleranciaTable : Table("app.conferencia_tolerancia") {
    val tenantId = uuid("tenant_id")
    val nucco = integer("nucco")
    val tolAcimaPct = decimal("tol_acima_pct", 6, 2).nullable()
    val tolAbaixoPct = decimal("tol_abaixo_pct", 6, 2).nullable()
    val atualizadoEm = timestamp("atualizado_em")

    override val primaryKey = PrimaryKey(tenantId, nucco)
}

/**
 * Tolerância de peso de item pesável, em %: quanto pode pesar a MAIS / a MENOS que o pedido
 * sem virar divergência (e ser liberado sozinho no corte). null = sem limite naquele sentido.
 * `configurada` = false quando o NUCCO não tem linha e vale o [PADRAO].
 */
@Serializable
data class ToleranciaPesoDto(
    val acimaPct: Double?,
    val abaixoPct: Double?,
    val configurada: Boolean = true,
)

object ConferenciaToleranciaRepository {
    /**
     * Regra de antes da V50 (continua valendo pra NUCCO sem configuração): a maior sem limite,
     * a menor até 5%.
     */
    val PADRAO = ToleranciaPesoDto(acimaPct = null, abaixoPct = 5.0, configurada = false)

    fun buscar(tenantId: UUID, nucco: Int?): ToleranciaPesoDto {
        if (nucco == null) return PADRAO
        return TenantTx.run(tenantId) {
            ConferenciaToleranciaTable.selectAll()
                .where { (ConferenciaToleranciaTable.tenantId eq tenantId) and (ConferenciaToleranciaTable.nucco eq nucco) }
                .singleOrNull()
                ?.let {
                    ToleranciaPesoDto(
                        acimaPct = it[ConferenciaToleranciaTable.tolAcimaPct]?.toDouble(),
                        abaixoPct = it[ConferenciaToleranciaTable.tolAbaixoPct]?.toDouble(),
                    )
                }
        } ?: PADRAO
    }

    fun salvar(tenantId: UUID, nucco: Int, acimaPct: Double?, abaixoPct: Double?): Unit = TenantTx.run(tenantId) {
        val acima = acimaPct?.let { BigDecimal.valueOf(it) }
        val abaixo = abaixoPct?.let { BigDecimal.valueOf(it) }
        val agora = Instant.now()
        val atualizou = ConferenciaToleranciaTable.update({
            (ConferenciaToleranciaTable.tenantId eq tenantId) and (ConferenciaToleranciaTable.nucco eq nucco)
        }) {
            it[tolAcimaPct] = acima
            it[tolAbaixoPct] = abaixo
            it[atualizadoEm] = agora
        }
        if (atualizou == 0) {
            ConferenciaToleranciaTable.insert {
                it[ConferenciaToleranciaTable.tenantId] = tenantId
                it[ConferenciaToleranciaTable.nucco] = nucco
                it[tolAcimaPct] = acima
                it[tolAbaixoPct] = abaixo
                it[atualizadoEm] = agora
            }
        }
    }
}
