package wms.backend.balancas

import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.javatime.timestamp

/**
 * app.balanca_sessoes_ativas — quem está logado AGORA em cada balança
 * (login por crachá, V32). Só 1 linha por balança: o crachá mais recente
 * lido ali substitui o anterior.
 *
 * Propositalmente separada de BalancaUsuariosTable — aquela é a lista de
 * autorização (N:N, mantida pelo admin) e não deve ser mexida por um login
 * de operador.
 */
object BalancaSessoesAtivasTable : Table("app.balanca_sessoes_ativas") {
    val balancaId = uuid("balanca_id")
    val tenantId = uuid("tenant_id")
    val usuarioId = uuid("usuario_id")
    val atualizadoEm = timestamp("atualizado_em")

    override val primaryKey = PrimaryKey(balancaId)
}
