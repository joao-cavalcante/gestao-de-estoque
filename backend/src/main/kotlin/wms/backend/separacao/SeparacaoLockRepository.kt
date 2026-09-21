package wms.backend.separacao

import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.greaterEq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.less
import org.jetbrains.exposed.sql.SqlExpressionBuilder.neq
import org.jetbrains.exposed.sql.Op
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.insertIgnore
import org.jetbrains.exposed.sql.or
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.update
import wms.backend.tenancy.TenantTx
import java.time.Duration
import java.time.Instant
import java.util.UUID

/**
 * Lock exclusivo por etapa da conferência (V46). Uma sessão de conferência por etapa por vez: quem
 * chega depois é bloqueado até o lock ser liberado ou EXPIRAR por 10 min sem heartbeat. A exclusividade
 * é garantida pelo banco (PK tenant+sessão+tipo, aquisição por UPDATE/INSERT atômico) — nunca
 * "consulta se está livre e depois cria".
 */
object SeparacaoLockRepository {
    /** Tempo máximo sem comunicação antes do lock ser considerado abandonado. */
    val TTL: Duration = Duration.ofMinutes(10)

    data class Lock(val tipo: Short, val token: UUID, val userId: UUID, val operadorId: UUID?, val ultimaAtividade: Instant)

    private fun corte(): Instant = Instant.now().minus(TTL)

    private fun chave(tenantId: UUID, sessaoId: UUID, tipo: Short): Op<Boolean> =
        (SeparacaoLocksTable.tenantId eq tenantId) and
            (SeparacaoLocksTable.sessaoId eq sessaoId) and
            (SeparacaoLocksTable.tipoSeparacao eq tipo)

    /**
     * Tenta assumir a etapa. true = este token agora é o dono (novo, re-entrada do mesmo token, ou tomada
     * de um lock expirado). false = outro token com atividade recente já está usando.
     */
    fun adquirir(tenantId: UUID, sessaoId: UUID, tipo: Short, token: UUID, userId: UUID, operadorId: UUID?): Boolean =
        TenantTx.run(tenantId) {
            val agora = Instant.now()
            val limite = corte()

            // 1) Re-entrada: o mesmo token só renova (vale mesmo que o lock já tenha passado dos 10 min,
            //    desde que ninguém tenha assumido no meio — a linha continua sendo dele).
            val renovou = SeparacaoLocksTable.update({ chave(tenantId, sessaoId, tipo) and (SeparacaoLocksTable.token eq token) }) {
                it[ultimaAtividade] = agora
                it[SeparacaoLocksTable.userId] = userId
                it[SeparacaoLocksTable.operadorId] = operadorId
            }
            if (renovou > 0) return@run true

            // 2) Tomada de um lock EXPIRADO (outro token, sem atividade há mais de TTL) — UPDATE atômico:
            //    dois candidatos simultâneos: só um casa a condição, o outro atualiza 0 linhas.
            val tomou = SeparacaoLocksTable.update({ chave(tenantId, sessaoId, tipo) and (SeparacaoLocksTable.ultimaAtividade less limite) }) {
                it[SeparacaoLocksTable.token] = token
                it[SeparacaoLocksTable.userId] = userId
                it[SeparacaoLocksTable.operadorId] = operadorId
                it[adquiridoEm] = agora
                it[ultimaAtividade] = agora
            }
            if (tomou > 0) return@run true

            // 3) Nenhuma linha (etapa nunca usada / já liberada): INSERT ... ON CONFLICT DO NOTHING — se
            //    outro candidato inseriu primeiro, insertedCount = 0 e este perde.
            val inseriu = SeparacaoLocksTable.insertIgnore {
                it[SeparacaoLocksTable.tenantId] = tenantId
                it[SeparacaoLocksTable.sessaoId] = sessaoId
                it[tipoSeparacao] = tipo
                it[SeparacaoLocksTable.token] = token
                it[SeparacaoLocksTable.userId] = userId
                it[SeparacaoLocksTable.operadorId] = operadorId
                it[adquiridoEm] = agora
                it[ultimaAtividade] = agora
            }
            inseriu.insertedCount > 0
        }

    /**
     * Confere que o token tem um lock VÁLIDO (dentro do TTL) e renova a atividade — heartbeat e validação
     * das operações críticas usam o mesmo caminho, então cada operação também conta como "sessão viva".
     * `tipo = null`: qualquer etapa da sessão que o token possua.
     */
    fun validarETocar(tenantId: UUID, sessaoId: UUID, tipo: Short?, token: UUID): Boolean = TenantTx.run(tenantId) {
        val limite = corte()
        val filtro = (SeparacaoLocksTable.tenantId eq tenantId) and
            (SeparacaoLocksTable.sessaoId eq sessaoId) and
            (SeparacaoLocksTable.token eq token) and
            (SeparacaoLocksTable.ultimaAtividade greaterEq limite) and
            (if (tipo != null) SeparacaoLocksTable.tipoSeparacao eq tipo else Op.TRUE)
        SeparacaoLocksTable.update({ filtro }) { it[ultimaAtividade] = Instant.now() } > 0
    }

    /** Libera a etapa do token (ao sair da conferência). Idempotente. */
    fun liberar(tenantId: UUID, sessaoId: UUID, tipo: Short?, token: UUID): Unit = TenantTx.run(tenantId) {
        SeparacaoLocksTable.deleteWhere {
            (SeparacaoLocksTable.tenantId eq tenantId) and
                (SeparacaoLocksTable.sessaoId eq sessaoId) and
                (SeparacaoLocksTable.token eq token) and
                (if (tipo != null) SeparacaoLocksTable.tipoSeparacao eq tipo else Op.TRUE)
        }
        Unit
    }

    /** Libera a etapa concluída, seja de quem for (concluir-etapa já é decisão do dono do lock). */
    fun liberarEtapa(tenantId: UUID, sessaoId: UUID, tipo: Short): Unit = TenantTx.run(tenantId) {
        SeparacaoLocksTable.deleteWhere {
            (SeparacaoLocksTable.tenantId eq tenantId) and
                (SeparacaoLocksTable.sessaoId eq sessaoId) and
                (SeparacaoLocksTable.tipoSeparacao eq tipo)
        }
        Unit
    }

    /** Sessão finalizada/cancelada: nenhum lock sobrevive. */
    fun liberarTodos(tenantId: UUID, sessaoId: UUID): Unit = TenantTx.run(tenantId) {
        SeparacaoLocksTable.deleteWhere {
            (SeparacaoLocksTable.tenantId eq tenantId) and (SeparacaoLocksTable.sessaoId eq sessaoId)
        }
        Unit
    }

    /** Locks com atividade dentro do TTL (os expirados contam como etapa livre). */
    fun ativos(tenantId: UUID, sessaoId: UUID): List<Lock> = TenantTx.run(tenantId) {
        val limite = corte()
        SeparacaoLocksTable.selectAll()
            .where {
                (SeparacaoLocksTable.tenantId eq tenantId) and
                    (SeparacaoLocksTable.sessaoId eq sessaoId) and
                    (SeparacaoLocksTable.ultimaAtividade greaterEq limite)
            }
            .map {
                Lock(
                    tipo = it[SeparacaoLocksTable.tipoSeparacao],
                    token = it[SeparacaoLocksTable.token],
                    userId = it[SeparacaoLocksTable.userId],
                    operadorId = it[SeparacaoLocksTable.operadorId],
                    ultimaAtividade = it[SeparacaoLocksTable.ultimaAtividade],
                )
            }
    }

    /** Dono atual (ativo) de uma etapa, pra mensagem "em uso por X". */
    fun donoAtivo(tenantId: UUID, sessaoId: UUID, tipo: Short): Lock? =
        ativos(tenantId, sessaoId).firstOrNull { it.tipo == tipo }
}
