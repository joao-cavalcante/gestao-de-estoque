package wms.backend.tarefas

import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import wms.backend.Database as SharedDatabase
import wms.backend.tenancy.SyncEstadoTable
import java.time.Instant
import java.util.UUID
import kotlin.random.Random

data class TenantReivindicado(val tenantId: UUID, val slug: String, val intervaloSegundos: Int)

/**
 * Fila de sync — vive em `tenancy.sync_estado` (sem RLS, ver
 * SyncEstadoTable.kt). Reivindicação via `SELECT ... FOR UPDATE SKIP LOCKED`
 * é o que permite N workers (e N *instâncias* do backend) coordenarem sem
 * pisar um no trabalho do outro, sem precisar de um coordenador externo.
 *
 * Padrão de "lease": a transação de reivindicação é CURTA — só marca
 * `proximo_run_em` num futuro distante (reserva temporária) e libera a
 * transação antes de rodar a sincronização de verdade (que envolve uma
 * chamada HTTP lenta ao Sankhya). Depois do trabalho real, uma segunda
 * transação curta grava o resultado de verdade (sucesso com jitter, ou
 * falha com backoff exponencial).
 */
object SyncEstadoRepository {
    private const val LEASE_SEGUNDOS = 5 * 60 // reserva enquanto a sincronização roda de verdade
    private const val JITTER_FRACAO = 0.2
    private const val BACKOFF_BASE_MS = 5_000L
    private const val BACKOFF_MAX_MS = 15 * 60_000L

    /** Transação curta — reivindica no máximo 1 tenant devido, ou null se não há nenhum. */
    fun reivindicarProximo(): TenantReivindicado? = transaction(SharedDatabase.shared) {
        exec("SET LOCAL statement_timeout = '2000ms'")

        val linha = exec(
            """
            SELECT se.tenant_id, t.slug, se.intervalo_segundos
            FROM tenancy.sync_estado se
            JOIN tenancy.tenants t ON t.id = se.tenant_id
            WHERE se.proximo_run_em <= now()
              AND (se.bloqueado_ate IS NULL OR se.bloqueado_ate <= now())
              AND t.status IN ('active', 'trial')
            ORDER BY se.proximo_run_em
            FOR UPDATE OF se SKIP LOCKED
            LIMIT 1
            """.trimIndent(),
        ) { rs ->
            if (rs.next()) {
                Triple(UUID.fromString(rs.getString("tenant_id")), rs.getString("slug"), rs.getInt("intervalo_segundos"))
            } else {
                null
            }
        } ?: return@transaction null

        val (tenantId, slug, intervalo) = linha

        // Lease: reserva esta linha por LEASE_SEGUNDOS pra nenhum outro worker
        // (ou instância) pegar o mesmo tenant enquanto a sincronização real roda.
        SyncEstadoTable.update({ SyncEstadoTable.tenantId eq tenantId }) {
            it[proximoRunEm] = Instant.now().plusSeconds(LEASE_SEGUNDOS.toLong())
        }

        TenantReivindicado(tenantId, slug, intervalo)
    }

    /** Sucesso: agenda o próximo ciclo com jitter (espalha carga no tempo) e zera o circuit breaker. */
    fun marcarSucessoESoltar(tenantId: UUID, intervaloSegundos: Int) = transaction(SharedDatabase.shared) {
        val agora = Instant.now()
        val jitterSegundos = (intervaloSegundos * JITTER_FRACAO * (Random.nextDouble() * 2 - 1)).toLong()
        SyncEstadoTable.update({ SyncEstadoTable.tenantId eq tenantId }) {
            it[ultimoSyncEm] = agora
            it[ultimoSyncSucessoEm] = agora
            it[ultimoErro] = null
            it[falhasConsecutivas] = 0
            it[bloqueadoAte] = null
            it[proximoRunEm] = agora.plusSeconds(intervaloSegundos.toLong() + jitterSegundos)
        }
    }

    /** Falha: circuit breaker — backoff exponencial com teto, tenant "doente" para de ser tentado a cada ciclo. */
    fun marcarFalhaEBackoff(tenantId: UUID, erro: String) = transaction(SharedDatabase.shared) {
        val atual = SyncEstadoTable.selectAll()
            .where { SyncEstadoTable.tenantId eq tenantId }
            .singleOrNull()
        val falhas = (atual?.get(SyncEstadoTable.falhasConsecutivas) ?: 0) + 1
        val atrasoMs = minOf(BACKOFF_MAX_MS, BACKOFF_BASE_MS * (1L shl minOf(falhas, 20)))
        val agora = Instant.now()
        val bloqueadoAteMomento = agora.plusMillis(atrasoMs)

        SyncEstadoTable.update({ SyncEstadoTable.tenantId eq tenantId }) {
            it[ultimoSyncEm] = agora
            it[ultimoErro] = erro.take(2000)
            it[falhasConsecutivas] = falhas
            it[bloqueadoAte] = bloqueadoAteMomento
            it[proximoRunEm] = bloqueadoAteMomento
        }
    }

    /**
     * Loop provisionador (leve, 1 statement, NÃO uma corrotina por tenant):
     * garante uma linha de fila pra todo tenant ativo com Sankhya
     * configurado que ainda não tem uma.
     */
    fun garantirLinhasParaTenantsAtivos() = transaction(SharedDatabase.shared) {
        exec(
            """
            INSERT INTO tenancy.sync_estado (tenant_id, proximo_run_em, intervalo_segundos)
            SELECT t.id, now(), 60
            FROM tenancy.tenants t
            JOIN tenancy.erp_connections ec ON ec.tenant_id = t.id
            WHERE ec.erp_type = 'sankhya' AND ec.ativo = true
              AND t.status IN ('active', 'trial')
            ON CONFLICT (tenant_id) DO NOTHING
            """.trimIndent(),
        )
    }
}
