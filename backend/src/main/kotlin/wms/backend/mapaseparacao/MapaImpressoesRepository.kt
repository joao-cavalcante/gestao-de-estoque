package wms.backend.mapaseparacao

import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.SqlExpressionBuilder.isNotNull
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.javatime.timestamp
import org.jetbrains.exposed.sql.selectAll
import wms.backend.tenancy.TenantTx
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.util.UUID

/** app.mapa_impressoes (V52) — histórico de impressão do Mapa de Separação. */
object MapaImpressoesTable : Table("app.mapa_impressoes") {
    val id = uuid("id")
    val tenantId = uuid("tenant_id")
    val ordemCarga = long("ordem_carga").nullable()
    val nunota = long("nunota").nullable()
    val impressoPor = text("impresso_por").nullable()
    val impressoEm = timestamp("impresso_em")

    override val primaryKey = PrimaryKey(id)
}

object MapaImpressoesRepository {

    data class Ultima(val em: String, val por: String?)

    /**
     * [pedidosPorOc] = pedidos que estavam no mapa impresso de cada OC — linha com ordem_carga E nunota
     * (fotografia da OC na hora da impressão). É o que detecta pedido incluído DEPOIS na OC já impressa.
     */
    fun registrar(
        tenantId: UUID, ordensCarga: Collection<Long>, nunotas: Collection<Long>, por: String?,
        pedidosPorOc: Map<Long, Collection<Long>> = emptyMap(),
    ): Unit = TenantTx.run(tenantId) {
        val agora = Instant.now()
        pedidosPorOc.forEach { (oc, pedidos) -> inserirFotografia(tenantId, oc, pedidos, por, agora) }
        ordensCarga.distinct().forEach { oc ->
            MapaImpressoesTable.insert {
                it[id] = UUID.randomUUID(); it[MapaImpressoesTable.tenantId] = tenantId
                it[ordemCarga] = oc; it[impressoPor] = por; it[impressoEm] = agora
            }
        }
        nunotas.distinct().forEach { n ->
            MapaImpressoesTable.insert {
                it[id] = UUID.randomUUID(); it[MapaImpressoesTable.tenantId] = tenantId
                it[nunota] = n; it[impressoPor] = por; it[impressoEm] = agora
            }
        }
    }

    private fun inserirFotografia(tenantId: UUID, oc: Long, pedidos: Collection<Long>, por: String?, em: Instant) {
        pedidos.distinct().forEach { n ->
            MapaImpressoesTable.insert {
                it[id] = UUID.randomUUID(); it[MapaImpressoesTable.tenantId] = tenantId
                it[ordemCarga] = oc; it[nunota] = n; it[impressoPor] = por; it[impressoEm] = em
            }
        }
    }

    /** Pedidos que já saíram em algum mapa impresso de cada OC (fotografias da impressão). */
    fun pedidosImpressosPorOc(tenantId: UUID, ordensCarga: Collection<Long>): Map<Long, Set<Long>> =
        if (ordensCarga.isEmpty()) emptyMap() else TenantTx.run(tenantId) {
            MapaImpressoesTable.selectAll()
                .where {
                    (MapaImpressoesTable.tenantId eq tenantId) and (MapaImpressoesTable.ordemCarga inList ordensCarga.distinct()) and
                        MapaImpressoesTable.nunota.isNotNull()
                }
                .groupBy({ it[MapaImpressoesTable.ordemCarga]!! }, { it[MapaImpressoesTable.nunota]!! })
                .mapValues { it.value.toSet() }
        }

    /**
     * OC impressa ANTES de existir a fotografia (06–07/10/2026): assume que os pedidos de agora são os que
     * foram impressos e grava a fotografia com a data da impressão — daqui em diante, pedido novo é detectado.
     */
    fun completarFotografia(tenantId: UUID, oc: Long, pedidos: Collection<Long>, ultima: Ultima): Unit = TenantTx.run(tenantId) {
        inserirFotografia(tenantId, oc, pedidos, ultima.por, Instant.parse(ultima.em))
    }

    /** Última impressão por Ordem de Carga. */
    fun ultimaPorOrdemCarga(tenantId: UUID, ordensCarga: Collection<Long>): Map<Long, Ultima> =
        if (ordensCarga.isEmpty()) emptyMap() else TenantTx.run(tenantId) {
            MapaImpressoesTable.selectAll()
                .where { (MapaImpressoesTable.tenantId eq tenantId) and (MapaImpressoesTable.ordemCarga inList ordensCarga.distinct()) }
                .groupBy { it[MapaImpressoesTable.ordemCarga]!! }
                .mapValues { (_, l) -> l.maxBy { it[MapaImpressoesTable.impressoEm] }.let(::ultima) }
        }

    /** Última impressão por Número Único (mapa S/ OC). */
    fun ultimaPorNunota(tenantId: UUID, nunotas: Collection<Long>): Map<Long, Ultima> =
        if (nunotas.isEmpty()) emptyMap() else TenantTx.run(tenantId) {
            MapaImpressoesTable.selectAll()
                .where { (MapaImpressoesTable.tenantId eq tenantId) and (MapaImpressoesTable.nunota inList nunotas.distinct()) }
                .groupBy { it[MapaImpressoesTable.nunota]!! }
                .mapValues { (_, l) -> l.maxBy { it[MapaImpressoesTable.impressoEm] }.let(::ultima) }
        }

    private fun ultima(r: org.jetbrains.exposed.sql.ResultRow) =
        Ultima(DateTimeFormatter.ISO_INSTANT.format(r[MapaImpressoesTable.impressoEm]), r[MapaImpressoesTable.impressoPor])
}
