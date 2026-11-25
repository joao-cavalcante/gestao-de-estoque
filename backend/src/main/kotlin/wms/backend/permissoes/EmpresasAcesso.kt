package wms.backend.permissoes

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.inList
import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.javatime.timestamp
import org.jetbrains.exposed.sql.selectAll
import wms.backend.auth.ClaimsToken
import wms.backend.tarefas.TarefasTable
import wms.backend.tenancy.TenantTx
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** app.usuario_empresas (V80). */
object UsuarioEmpresasTable : Table("app.usuario_empresas") {
    val tenantId = uuid("tenant_id")
    val usuarioId = uuid("usuario_id")
    val codemp = integer("codemp")
    val criadoEm = timestamp("criado_em")

    override val primaryKey = PrimaryKey(tenantId, usuarioId, codemp)
}

/**
 * Acesso por EMPRESA (V80, 25/11/2026 — transferência entre filiais na mesma Torre). REGRA ÚNICA:
 *   ADMINISTRADOR ou usuário sem empresa vinculada = vê todas (comportamento de antes);
 *   usuário com empresas vinculadas               = só notas/OCs/estoque dessas empresas.
 * Nota cuja empresa não se conhece (ainda não sincronizada) fica de fora para quem tem restrição.
 */
object EmpresasAcesso {

    private const val TTL_MS = 30_000L
    private data class Cache(val empresas: Set<Int>, val lidoEm: Long)
    private val cache = ConcurrentHashMap<Pair<UUID, UUID>, Cache>()

    /** Empresas do usuário (vazio = todas). */
    fun empresasDoUsuario(tenantId: UUID, usuarioId: UUID): Set<Int> {
        val chave = tenantId to usuarioId
        cache[chave]?.takeIf { System.currentTimeMillis() - it.lidoEm < TTL_MS }?.let { return it.empresas }
        val lidas = TenantTx.run(tenantId) {
            UsuarioEmpresasTable.selectAll()
                .where { (UsuarioEmpresasTable.tenantId eq tenantId) and (UsuarioEmpresasTable.usuarioId eq usuarioId) }
                .map { it[UsuarioEmpresasTable.codemp] }
                .toSet()
        }
        cache[chave] = Cache(lidas, System.currentTimeMillis())
        return lidas
    }

    /** null = sem restrição (admin ou usuário sem empresa vinculada). */
    fun permitidas(claims: ClaimsToken): Set<Int>? {
        if (PermissoesRecurso.acessoTotal(claims)) return null
        return empresasDoUsuario(claims.tenantId, claims.userId).ifEmpty { null }
    }

    fun permite(permitidas: Set<Int>?, codemp: Int?): Boolean = permitidas == null || (codemp != null && codemp in permitidas)

    fun permite(claims: ClaimsToken, codemp: Int?): Boolean = permite(permitidas(claims), codemp)

    /** "1", "1.0", " 1 " → 1. */
    fun codemp(texto: String?): Int? = texto?.trim()?.let { it.toIntOrNull() ?: it.replace(',', '.').toDoubleOrNull()?.toInt() }

    /** A nota (já sincronizada na Torre) é de uma empresa que o usuário vê? */
    fun podeVerNota(claims: ClaimsToken, nunota: Long): Boolean {
        val p = permitidas(claims) ?: return true
        return permite(p, codempPorNunota(claims.tenantId, listOf(nunota))[nunota])
    }

    fun <T> filtrar(claims: ClaimsToken, itens: List<T>, codempDe: (T) -> Int?): List<T> {
        val p = permitidas(claims) ?: return itens
        return itens.filter { permite(p, codempDe(it)) }
    }

    /** CODEMP das notas pela base local (TarefasTable.dados), como o codTipOperPorNunota. */
    fun codempPorNunota(tenantId: UUID, nunotas: Collection<Long>): Map<Long, Int> = TenantTx.run(tenantId) {
        val ints = nunotas.mapNotNull { n -> n.takeIf { it in 0..Int.MAX_VALUE }?.toInt() }
        if (ints.isEmpty()) return@run emptyMap()
        TarefasTable.selectAll()
            .where { (TarefasTable.tenantId eq tenantId) and (TarefasTable.nunota inList ints) }
            .mapNotNull { row ->
                val dados = runCatching { Json.parseToJsonElement(row[TarefasTable.dados]) as JsonObject }.getOrNull()
                val emp = codemp(dados?.get("CODEMP")?.jsonPrimitive?.contentOrNull) ?: return@mapNotNull null
                row[TarefasTable.nunota].toLong() to emp
            }
            .toMap()
    }

    /** OCs que o usuário vê (null = todas): as que têm pedido de uma empresa dele na base local. */
    fun ocsPermitidas(claims: ClaimsToken): Set<Long>? {
        val p = permitidas(claims) ?: return null
        return wms.backend.tarefas.TarefasRepository.listar(claims.tenantId)
            .filter { it.ordemCarga != null && permite(p, codemp(it.codigoEmpresa)) }
            .mapNotNull { it.ordemCarga }
            .toSet()
    }

    /** Só os números únicos que o usuário vê (sem restrição = todos). */
    fun nunotasPermitidas(claims: ClaimsToken, nunotas: Collection<Long>): List<Long> {
        val p = permitidas(claims) ?: return nunotas.toList()
        val emp = codempPorNunota(claims.tenantId, nunotas)
        return nunotas.filter { permite(p, emp[it]) }
    }

    /** Números únicos (base local) das empresas do usuário; null = sem restrição. */
    fun nunotasDasEmpresas(claims: ClaimsToken): Set<Long>? {
        val p = permitidas(claims) ?: return null
        return wms.backend.tarefas.TarefasRepository.listar(claims.tenantId)
            .filter { permite(p, codemp(it.codigoEmpresa)) }
            .map { it.nunota }
            .toSet()
    }

    /** Substitui as empresas do usuário (vazio = todas). */
    fun definir(tenantId: UUID, usuarioId: UUID, empresas: Collection<Int>) {
        TenantTx.run(tenantId) {
            UsuarioEmpresasTable.deleteWhere { (UsuarioEmpresasTable.tenantId eq tenantId) and (UsuarioEmpresasTable.usuarioId eq usuarioId) }
            val agora = Instant.now()
            empresas.filter { it > 0 }.distinct().forEach { e ->
                UsuarioEmpresasTable.insert {
                    it[UsuarioEmpresasTable.tenantId] = tenantId
                    it[UsuarioEmpresasTable.usuarioId] = usuarioId
                    it[codemp] = e
                    it[criadoEm] = agora
                }
            }
        }
        cache.remove(tenantId to usuarioId)
    }
}
