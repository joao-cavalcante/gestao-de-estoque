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
 * Quem conta é a CONTA LOGADA — inclusive conta de ESTAÇÃO (a balança fica fisicamente na
 * estação; libera-se a estação na lista do recurso). ADMINISTRADOR acessa tudo, sempre.
 */
object PermissoesRecurso {

    /** Administrador não sofre restrição de TOP nem de balança. */
    fun acessoTotal(claims: ClaimsToken): Boolean = claims.perfil == "ADMINISTRADOR"

    fun podeUsarTop(claims: ClaimsToken, restritas: Map<Int, Set<UUID>>, codtop: Int?): Boolean =
        acessoTotal(claims) || podeUsarTop(restritas, claims.userId, codtop)

    fun podeUsarTop(claims: ClaimsToken, codtop: Int?): Boolean =
        acessoTotal(claims) || podeUsarTop(claims.tenantId, claims.userId, codtop)

    fun podeUsarBalanca(claims: ClaimsToken, restritas: Map<UUID, Set<UUID>>, balancaId: UUID): Boolean =
        acessoTotal(claims) || podeUsarBalanca(restritas, claims.userId, balancaId)

    fun podeUsarBalanca(claims: ClaimsToken, balancaId: UUID): Boolean =
        acessoTotal(claims) || podeUsarBalanca(claims.tenantId, claims.userId, balancaId)

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
