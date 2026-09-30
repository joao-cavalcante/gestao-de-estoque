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
        val autorizadosPorTop = TipoOperacaoUsuariosTable.selectAll()
            .where { TipoOperacaoUsuariosTable.tenantId eq tenantId }
            .groupingBy { it[TipoOperacaoUsuariosTable.codtop] }
            .eachCount()
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
                    usuariosAutorizados = autorizadosPorTop[it[TipoOperacaoTable.codtop]] ?: 0,
                )
            }
    }

    /**
     * Upsert a partir do que foi derivado de app.tarefas (ver TipoOperacaoSyncService): TOP novo entra,
     * TOP já conhecido tem descrição/NUCCO/TIPMOV atualizados. NÃO apaga TOP que sumiu das tarefas —
     * antes era full refresh (delete + insert) e a lista inteira sumia quando a Fila esvaziava
     * (caso real: limpeza dos pedidos da Negri em 30/09, base do Sankhya zerada).
     * NÃO toca app.tipo_operacao_config — a escolha do usuário sobrevive à sync.
     */
    fun substituirDerivado(tenantId: UUID, linhas: List<TopDerivado>): Int = TenantTx.run(tenantId) {
        val agora = Instant.now()
        val existentes = TipoOperacaoTable.selectAll()
            .where { TipoOperacaoTable.tenantId eq tenantId }
            .map { it[TipoOperacaoTable.codtop] }
            .toSet()

        linhas.forEach { linha ->
            if (linha.codtop in existentes) {
                TipoOperacaoTable.update({ (TipoOperacaoTable.tenantId eq tenantId) and (TipoOperacaoTable.codtop eq linha.codtop) }) {
                    it[descricao] = linha.descricao
                    it[nucco] = linha.nucco
                    it[tipmov] = linha.tipmov
                    it[localAtualizadoEm] = agora
                }
            } else {
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
