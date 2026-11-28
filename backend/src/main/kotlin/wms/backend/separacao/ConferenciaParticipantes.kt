package wms.backend.separacao

import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.greater
import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.javatime.timestamp
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.update
import wms.backend.tenancy.TenantTx
import wms.backend.tarefas.TarefasRepository
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

object ConferenciaParticipantesTable : Table("app.conferencia_participantes") {
    val tenantId = uuid("tenant_id")
    val sessaoId = uuid("sessao_id")
    val token = uuid("token")
    val userId = uuid("user_id")
    val nome = text("nome").nullable()
    val entrouEm = timestamp("entrou_em")
    val ultimaAtividade = timestamp("ultima_atividade")
    override val primaryKey = PrimaryKey(tenantId, sessaoId, token)
}

/**
 * V84 (27/11/2026) — conferência de COMPRA com vários conferentes. Na compra (TIPMOV C, sem segmentação/recontagem)
 * cada aparelho entra como participante em vez de disputar o lock exclusivo (V46, que segue valendo para a saída).
 * Cada leitura guarda o conferente; "Fechar palete" pega só o dele (Paletes.bolsos).
 */
object ConferenciaParticipantes {
    /** Sem atividade há mais que isso = saiu (o heartbeat da tela é de ~60 s). */
    val ATIVO: Duration = Duration.ofMinutes(3)

    data class Participante(val userId: UUID, val nome: String?, val token: UUID, val ultimaAtividade: Instant)

    private val tipMovCache = ConcurrentHashMap<UUID, String>()

    /** A sessão aceita vários conferentes? Compra não segmentada e fora de recontagem. */
    fun multi(tenantId: UUID, sessao: SessaoSeparacaoDto?): Boolean {
        if (sessao == null || sessao.conferenciaSegmentada || sessao.recontagem) return false
        val id = UUID.fromString(sessao.id)
        val tip = tipMovCache[id] ?: (TarefasRepository.buscarTipMovLocal(tenantId, sessao.nunota)?.trim().orEmpty()).also { tipMovCache[id] = it }
        return tip == "C"
    }

    fun entrar(tenantId: UUID, sessaoId: UUID, token: UUID, userId: UUID, nome: String?): Unit = TenantTx.run(tenantId) {
        val agora = Instant.now()
        val n = ConferenciaParticipantesTable.update({ chave(tenantId, sessaoId, token) }) {
            it[ultimaAtividade] = agora
            it[ConferenciaParticipantesTable.userId] = userId
        }
        if (n == 0) ConferenciaParticipantesTable.insert {
            it[ConferenciaParticipantesTable.tenantId] = tenantId
            it[ConferenciaParticipantesTable.sessaoId] = sessaoId
            it[ConferenciaParticipantesTable.token] = token
            it[ConferenciaParticipantesTable.userId] = userId
            it[ConferenciaParticipantesTable.nome] = nome
            it[entrouEm] = agora
            it[ultimaAtividade] = agora
        }
    }

    /** Valida e renova. Token desconhecido = não entrou (ou saiu): a tela reabre a conferência. */
    fun validarETocar(tenantId: UUID, sessaoId: UUID, token: UUID): Boolean = TenantTx.run(tenantId) {
        ConferenciaParticipantesTable.update({ chave(tenantId, sessaoId, token) }) { it[ultimaAtividade] = Instant.now() } > 0
    }

    fun sair(tenantId: UUID, sessaoId: UUID, token: UUID): Unit = TenantTx.run(tenantId) {
        ConferenciaParticipantesTable.deleteWhere { chave(tenantId, sessaoId, token) }
    }

    fun ativos(tenantId: UUID, sessaoId: UUID): List<Participante> = TenantTx.run(tenantId) {
        val corte = Instant.now().minus(ATIVO)
        ConferenciaParticipantesTable.selectAll()
            .where { (ConferenciaParticipantesTable.tenantId eq tenantId) and (ConferenciaParticipantesTable.sessaoId eq sessaoId) and (ConferenciaParticipantesTable.ultimaAtividade greater corte) }
            .map { Participante(it[ConferenciaParticipantesTable.userId], it[ConferenciaParticipantesTable.nome], it[ConferenciaParticipantesTable.token], it[ConferenciaParticipantesTable.ultimaAtividade]) }
    }

    private fun chave(tenantId: UUID, sessaoId: UUID, token: UUID) =
        (ConferenciaParticipantesTable.tenantId eq tenantId) and (ConferenciaParticipantesTable.sessaoId eq sessaoId) and (ConferenciaParticipantesTable.token eq token)
}
