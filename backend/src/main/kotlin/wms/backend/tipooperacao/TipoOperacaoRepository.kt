package wms.backend.tipooperacao

import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import wms.backend.tenancy.TenantTx
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.util.UUID

object TipoOperacaoRepository {

    fun listar(tenantId: UUID): List<TipoOperacaoDto> = TenantTx.run(tenantId) {
        TipoOperacaoTable.selectAll()
            .where { TipoOperacaoTable.tenantId eq tenantId }
            .orderBy(TipoOperacaoTable.codtop, SortOrder.ASC)
            .map {
                TipoOperacaoDto(
                    id = it[TipoOperacaoTable.id].toString(),
                    codtop = it[TipoOperacaoTable.codtop],
                    descricao = it[TipoOperacaoTable.descricao],
                    nucco = it[TipoOperacaoTable.nucco],
                    localAtualizadoEm = DateTimeFormatter.ISO_INSTANT.format(it[TipoOperacaoTable.localAtualizadoEm]),
                )
            }
    }

    /**
     * Full refresh — apaga e regrava a partir do que já foi derivado de
     * app.tarefas (ver TipoOperacaoSyncService). Volume baixo (só os TOP
     * distintos em uso), sem custo de chamada ao Sankhya nesta etapa.
     */
    fun substituirDerivado(tenantId: UUID, linhas: List<TopDerivado>): Int = TenantTx.run(tenantId) {
        val agora = Instant.now()

        TipoOperacaoTable.deleteWhere { TipoOperacaoTable.tenantId eq tenantId }

        linhas.forEach { linha ->
            TipoOperacaoTable.insert {
                it[id] = UUID.randomUUID()
                it[TipoOperacaoTable.tenantId] = tenantId
                it[codtop] = linha.codtop
                it[descricao] = linha.descricao
                it[nucco] = linha.nucco
                it[localAtualizadoEm] = agora
            }
        }
        linhas.size
    }
}

data class TopDerivado(val codtop: Int, val descricao: String, val nucco: Int?)
