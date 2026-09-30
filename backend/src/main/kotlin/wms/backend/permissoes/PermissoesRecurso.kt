package wms.backend.permissoes

import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import wms.backend.auth.ClaimsToken
import wms.backend.balancas.BalancaUsuariosTable
import wms.backend.tenancy.TenantTx
import wms.backend.tipooperacao.TipoOperacaoUsuariosTable
import java.time.Instant
import java.util.UUID

/**
 * Controle de acesso por usuário a RECURSOS da conferência — TOP (V51) e Balança (V23), cada um
 * com a sua lista N:N de usuários autorizados, independentes entre si (nunca TOP + Balança juntos).
 *
 * REGRA ÚNICA (todo ponto de validação passa por aqui):
 *   recurso SEM nenhum usuário vinculado = sem restrição (todos usam — comportamento de antes);
 *   recurso COM usuários vinculados      = só eles usam.
 *
 * Usuário efetivo: login pessoal = quem está logado; conta de ESTAÇÃO (tablet compartilhado) = o
 * operador que bipou o crachá na sessão (antes do crachá não há pessoa — a estação não é barrada,
 * e as escritas já exigem o crachá; ver exigirOperadorSeEstacao).
 */
object PermissoesRecurso {

    fun usuarioEfetivo(claims: ClaimsToken, operadorIdDaSessao: String?): UUID? =
        if (claims.perfil == "ESTACAO") operadorIdDaSessao?.let { runCatching { UUID.fromString(it) }.getOrNull() }
        else claims.userId

    // ─── TOP ────────────────────────────────────────────────────────────────

    /** codtop → usuários autorizados, só das TOPs que têm restrição. */
    fun topsRestritas(tenantId: UUID): Map<Int, Set<UUID>> = TenantTx.run(tenantId) {
        TipoOperacaoUsuariosTable.selectAll()
            .where { TipoOperacaoUsuariosTable.tenantId eq tenantId }
            .groupBy({ it[TipoOperacaoUsuariosTable.codtop] }, { it[TipoOperacaoUsuariosTable.usuarioId] })
            .mapValues { it.value.toSet() }
    }

    /** TOP desconhecida (null) ou sem restrição = liberada. */
    fun podeUsarTop(restritas: Map<Int, Set<UUID>>, usuarioId: UUID?, codtop: Int?): Boolean {
        val autorizados = codtop?.let { restritas[it] } ?: return true
        return usuarioId != null && usuarioId in autorizados
    }

    fun podeUsarTop(tenantId: UUID, usuarioId: UUID?, codtop: Int?): Boolean =
        codtop == null || podeUsarTop(topsRestritas(tenantId), usuarioId, codtop)

    fun usuariosDaTop(tenantId: UUID, codtop: Int): List<String> = TenantTx.run(tenantId) {
        TipoOperacaoUsuariosTable.selectAll()
            .where { (TipoOperacaoUsuariosTable.tenantId eq tenantId) and (TipoOperacaoUsuariosTable.codtop eq codtop) }
            .map { it[TipoOperacaoUsuariosTable.usuarioId].toString() }
    }

    /** Substitui a lista inteira (lista vazia = TOP volta a ser sem restrição). */
    fun definirUsuariosDaTop(tenantId: UUID, codtop: Int, usuarioIds: Collection<UUID>): Unit = TenantTx.run(tenantId) {
        TipoOperacaoUsuariosTable.deleteWhere { (TipoOperacaoUsuariosTable.tenantId eq tenantId) and (TipoOperacaoUsuariosTable.codtop eq codtop) }
        val agora = Instant.now()
        usuarioIds.distinct().forEach { u ->
            TipoOperacaoUsuariosTable.insert {
                it[TipoOperacaoUsuariosTable.tenantId] = tenantId
                it[TipoOperacaoUsuariosTable.codtop] = codtop
                it[usuarioId] = u
                it[criadoEm] = agora
            }
        }
    }

    // ─── Balança ────────────────────────────────────────────────────────────

    /** balancaId → usuários autorizados, só das balanças que têm restrição. */
    fun balancasRestritas(tenantId: UUID): Map<UUID, Set<UUID>> = TenantTx.run(tenantId) {
        BalancaUsuariosTable.selectAll()
            .where { BalancaUsuariosTable.tenantId eq tenantId }
            .groupBy({ it[BalancaUsuariosTable.balancaId] }, { it[BalancaUsuariosTable.usuarioId] })
            .mapValues { it.value.toSet() }
    }

    fun podeUsarBalanca(restritas: Map<UUID, Set<UUID>>, usuarioId: UUID?, balancaId: UUID): Boolean {
        val autorizados = restritas[balancaId] ?: return true
        return usuarioId != null && usuarioId in autorizados
    }

    fun podeUsarBalanca(tenantId: UUID, usuarioId: UUID?, balancaId: UUID): Boolean =
        podeUsarBalanca(balancasRestritas(tenantId), usuarioId, balancaId)
}
