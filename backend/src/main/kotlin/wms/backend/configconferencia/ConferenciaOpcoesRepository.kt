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
    /** V78 — ajuste de peso da compra por NUCCO; null = herda o do cliente (parametros.ajustePesoCompra). */
    val ajustePesoHabilitado = bool("ajuste_peso_habilitado").nullable()
    val ajustePesoEntrada = bool("ajuste_peso_entrada").nullable()
    val ajustePesoSaida = bool("ajuste_peso_saida").nullable()
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
    /**
     * V78 (25/11/2026) — ajuste de peso da compra nesta configuração: lançar nota de ajuste dentro da tolerância
     * ([ajustePesoHabilitado]) e em quais sentidos ([ajustePesoEntrada] = peso a maior, [ajustePesoSaida] = a menor;
     * false = vai para divergência/liberação). null = herda o do cliente. Na resposta do GET vêm já resolvidos.
     */
    val ajustePesoHabilitado: Boolean? = null,
    val ajustePesoEntrada: Boolean? = null,
    val ajustePesoSaida: Boolean? = null,
)

object ConferenciaOpcoesRepository {
    val PADRAO = OpcoesConferenciaDto(pedirQtdComercial = true, configurada = false)

    fun buscar(tenantId: UUID, nucco: Int?): OpcoesConferenciaDto {
        if (nucco == null) return PADRAO
        return TenantTx.run(tenantId) {
            ConferenciaOpcoesTable.selectAll()
                .where { (ConferenciaOpcoesTable.tenantId eq tenantId) and (ConferenciaOpcoesTable.nucco eq nucco) }
                .singleOrNull()
                ?.let {
                    OpcoesConferenciaDto(
                        pedirQtdComercial = it[ConferenciaOpcoesTable.pedirQtdComercial],
                        ajustePesoHabilitado = it[ConferenciaOpcoesTable.ajustePesoHabilitado],
                        ajustePesoEntrada = it[ConferenciaOpcoesTable.ajustePesoEntrada],
                        ajustePesoSaida = it[ConferenciaOpcoesTable.ajustePesoSaida],
                    )
                }
        } ?: PADRAO
    }

    fun salvar(tenantId: UUID, nucco: Int, opcoes: OpcoesConferenciaDto): Unit = TenantTx.run(tenantId) {
        val agora = Instant.now()
        val atualizou = ConferenciaOpcoesTable.update({
            (ConferenciaOpcoesTable.tenantId eq tenantId) and (ConferenciaOpcoesTable.nucco eq nucco)
        }) {
            it[pedirQtdComercial] = opcoes.pedirQtdComercial
            it[ajustePesoHabilitado] = opcoes.ajustePesoHabilitado
            it[ajustePesoEntrada] = opcoes.ajustePesoEntrada
            it[ajustePesoSaida] = opcoes.ajustePesoSaida
            it[atualizadoEm] = agora
        }
        if (atualizou == 0) {
            ConferenciaOpcoesTable.insert {
                it[ConferenciaOpcoesTable.tenantId] = tenantId
                it[ConferenciaOpcoesTable.nucco] = nucco
                it[pedirQtdComercial] = opcoes.pedirQtdComercial
                it[ajustePesoHabilitado] = opcoes.ajustePesoHabilitado
                it[ajustePesoEntrada] = opcoes.ajustePesoEntrada
                it[ajustePesoSaida] = opcoes.ajustePesoSaida
                it[atualizadoEm] = agora
            }
        }
    }
}
