package wms.backend.tipooperacao

import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.update
import wms.backend.tenancy.TenantTx
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.util.UUID

object TipoOperacaoRepository {

    fun listar(tenantId: UUID): List<TipoOperacaoDto> = TenantTx.run(tenantId) {
        val semEtapa = topsSemConferenciaPorEtapaTx(tenantId)
        TipoOperacaoTable.selectAll()
            .where { TipoOperacaoTable.tenantId eq tenantId }
            .orderBy(TipoOperacaoTable.codtop, SortOrder.ASC)
            .map {
                TipoOperacaoDto(
                    id = it[TipoOperacaoTable.id].toString(),
                    codtop = it[TipoOperacaoTable.codtop],
                    descricao = it[TipoOperacaoTable.descricao],
                    nucco = it[TipoOperacaoTable.nucco],
                    tipmov = it[TipoOperacaoTable.tipmov],
                    conferenciaPorEtapa = it[TipoOperacaoTable.codtop] !in semEtapa,
                    localAtualizadoEm = DateTimeFormatter.ISO_INSTANT.format(it[TipoOperacaoTable.localAtualizadoEm]),
                )
            }
    }

    /**
     * Full refresh — apaga e regrava a partir do que já foi derivado de
     * app.tarefas (ver TipoOperacaoSyncService). Volume baixo (só os TOP
     * distintos em uso), sem custo de chamada ao Sankhya nesta etapa.
     * NÃO toca app.tipo_operacao_config — a escolha do usuário sobrevive à sync.
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
                it[tipmov] = linha.tipmov
                it[localAtualizadoEm] = agora
            }
        }
        linhas.size
    }

    // ─── Conferência por etapa por TOP (V49) ─────────────────────────────────

    /** TOPs com a conferência por etapa DESLIGADA (sem linha = ligada). */
    fun topsSemConferenciaPorEtapa(tenantId: UUID): Set<Int> = TenantTx.run(tenantId) { topsSemConferenciaPorEtapaTx(tenantId) }

    private fun topsSemConferenciaPorEtapaTx(tenantId: UUID): Set<Int> =
        TipoOperacaoConfigTable.selectAll()
            .where { (TipoOperacaoConfigTable.tenantId eq tenantId) and (TipoOperacaoConfigTable.conferenciaPorEtapa eq false) }
            .map { it[TipoOperacaoConfigTable.codtop] }
            .toSet()

    /** TOP desconhecido (null) segue o padrão — por etapa. */
    fun usaConferenciaPorEtapa(tenantId: UUID, codtop: Int?): Boolean =
        codtop == null || codtop !in topsSemConferenciaPorEtapa(tenantId)

    fun definirConferenciaPorEtapa(tenantId: UUID, codtop: Int, valor: Boolean): Unit = TenantTx.run(tenantId) {
        val agora = Instant.now()
        val atualizou = TipoOperacaoConfigTable.update({
            (TipoOperacaoConfigTable.tenantId eq tenantId) and (TipoOperacaoConfigTable.codtop eq codtop)
        }) {
            it[conferenciaPorEtapa] = valor
            it[atualizadoEm] = agora
        }
        if (atualizou == 0) {
            TipoOperacaoConfigTable.insert {
                it[TipoOperacaoConfigTable.tenantId] = tenantId
                it[TipoOperacaoConfigTable.codtop] = codtop
                it[conferenciaPorEtapa] = valor
                it[atualizadoEm] = agora
            }
        }
    }
}

data class TopDerivado(val codtop: Int, val descricao: String, val nucco: Int?, val tipmov: String? = null)
