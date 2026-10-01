package wms.backend.reconferencia

import io.ktor.http.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.insertIgnore
import org.jetbrains.exposed.sql.javatime.timestamp
import org.jetbrains.exposed.sql.selectAll
import wms.backend.auth.exigirAuth
import wms.backend.separacao.SeparacaoEtapasTable
import wms.backend.separacao.SeparacaoItensTable
import wms.backend.separacao.SeparacaoSessoesTable
import wms.backend.separacao.SeparacaoStatus
import wms.backend.tarefas.TarefasRepository
import wms.backend.tarefas.TarefasTable
import wms.backend.tenancy.TenantTx
import wms.backend.usuarios.UsuariosRepository
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.util.UUID

/**
 * Reconferência: check manual, item a item, do que foi conferido numa sessão — no pop-up "Ver
 * conferidos" (conferência em andamento) e na tela Reconferência (conferências finalizadas).
 * Só registro (V53 app.reconferencia_checks) — não mexe na conferência nem no Sankhya.
 */
object ReconferenciaChecksTable : Table("app.reconferencia_checks") {
    val tenantId = uuid("tenant_id")
    val sessaoId = uuid("sessao_id")
    val codprod = integer("codprod")
    val controle = text("controle")
    val checadoPor = text("checado_por").nullable()
    val checadoEm = timestamp("checado_em")
    override val primaryKey = PrimaryKey(tenantId, sessaoId, codprod, controle)
}

@Serializable
data class ReconferenciaItemDto(
    val codprod: Int,
    val controle: String,
    val descricao: String,
    /** 1 Secos | 2 Refrigerado | 3 Congelado | 0 sem etapa. */
    val tipoSeparacao: Int,
    /** Unidade padrão (base) — mesma magnitude de qtd/conferido. */
    val unidade: String?,
    val qtdPedido: String,
    val qtdConferida: String,
    val pesavel: Boolean,
    val checado: Boolean,
    val checadoPor: String? = null,
    val checadoEm: String? = null,
)

@Serializable
data class ReconferenciaDetalheDto(
    val sessaoId: String,
    val nunota: Long,
    val numNota: Long?,
    val cliente: String?,
    val finalizada: Boolean,
    /** Tolerância de peso da sessão (%) — o front usa pra não acusar pesável dentro dela. */
    val tolPesoAbaixoPct: Double?,
    val tolPesoAcimaPct: Double?,
    val itens: List<ReconferenciaItemDto>,
)

@Serializable
data class ReconferenciaResumoDto(
    val sessaoId: String,
    val nunota: Long,
    val numNota: Long?,
    val cliente: String?,
    val ordemCarga: Long?,
    val dataMovimento: String?,
    val finalizadaEm: String,
    val express: Boolean,
    val retira: Boolean,
    val entrega: Boolean,
    val totalItens: Int,
    val checados: Int,
)

@Serializable
data class CheckRequest(val codprod: Int, val controle: String = "", val checado: Boolean)

object ReconferenciaService {
    private val iso = DateTimeFormatter.ISO_INSTANT

    private fun dadosDe(raw: String?): JsonObject? = raw?.let { runCatching { Json.parseToJsonElement(it) as JsonObject }.getOrNull() }
    private fun JsonObject?.campo(n: String) = this?.get(n)?.jsonPrimitive?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }

    fun detalhe(tenantId: UUID, sessaoId: UUID): ReconferenciaDetalheDto? = TenantTx.run(tenantId) {
        val s = SeparacaoSessoesTable.selectAll()
            .where { (SeparacaoSessoesTable.tenantId eq tenantId) and (SeparacaoSessoesTable.id eq sessaoId) }
            .singleOrNull() ?: return@run null
        val nunota = s[SeparacaoSessoesTable.nunota]
        val tarefa = TarefasTable.selectAll()
            .where { (TarefasTable.tenantId eq tenantId) and (TarefasTable.nunota eq nunota) }
            .singleOrNull()?.let { dadosDe(it[TarefasTable.dados]) }
        val checks = ReconferenciaChecksTable.selectAll()
            .where { (ReconferenciaChecksTable.tenantId eq tenantId) and (ReconferenciaChecksTable.sessaoId eq sessaoId) }
            .associateBy { it[ReconferenciaChecksTable.codprod] to it[ReconferenciaChecksTable.controle] }
        // Uma linha por produto+controle (soma das linhas do pedido), só o que tem algo conferido.
        val itens = SeparacaoItensTable.selectAll()
            .where {
                (SeparacaoItensTable.tenantId eq tenantId) and (SeparacaoItensTable.sessaoId eq sessaoId) and
                    (SeparacaoItensTable.silencioso eq false)
            }
            .orderBy(SeparacaoItensTable.sequencia, SortOrder.ASC)
            .groupBy { it[SeparacaoItensTable.codprod] to it[SeparacaoItensTable.controle].trim() }
            .mapNotNull { (chave, linhas) ->
                val conferida = linhas.sumOf { it[SeparacaoItensTable.qtdConferidaLocal] }
                if (conferida.signum() <= 0) return@mapNotNull null
                val r = linhas.first()
                val d = dadosDe(r[SeparacaoItensTable.dados])
                val check = checks[chave]
                ReconferenciaItemDto(
                    codprod = chave.first,
                    controle = chave.second,
                    descricao = listOfNotNull(d.campo("Produto.DESCRPROD"), d.campo("Produto.COMPLDESC")).joinToString(" ")
                        .ifBlank { "Produto ${chave.first}" },
                    tipoSeparacao = r[SeparacaoItensTable.tipoSeparacao].toInt(),
                    unidade = r[SeparacaoItensTable.unidadePadrao] ?: r[SeparacaoItensTable.codvol],
                    qtdPedido = linhas.sumOf { it[SeparacaoItensTable.qtdNeg] }.stripTrailingZeros().toPlainString(),
                    qtdConferida = conferida.stripTrailingZeros().toPlainString(),
                    pesavel = r[SeparacaoItensTable.usaConfPeso],
                    checado = check != null,
                    checadoPor = check?.get(ReconferenciaChecksTable.checadoPor),
                    checadoEm = check?.get(ReconferenciaChecksTable.checadoEm)?.let(iso::format),
                )
            }
        ReconferenciaDetalheDto(
            sessaoId = sessaoId.toString(),
            nunota = nunota.toLong(),
            numNota = tarefa.campo("NUMNOTA")?.let { it.toLongOrNull() ?: it.toDoubleOrNull()?.toLong() },
            cliente = tarefa.campo("Parceiro.NOMEPARC"),
            finalizada = s[SeparacaoSessoesTable.status] == SeparacaoStatus.CONCLUIDA,
            tolPesoAbaixoPct = s[SeparacaoSessoesTable.tolPesoAbaixoPct]?.toDouble(),
            tolPesoAcimaPct = s[SeparacaoSessoesTable.tolPesoAcimaPct]?.toDouble(),
            itens = itens,
        )
    }

    fun marcar(tenantId: UUID, sessaoId: UUID, req: CheckRequest, por: String?): Unit = TenantTx.run(tenantId) {
        val controle = req.controle.trim()
        if (req.checado) {
            ReconferenciaChecksTable.insertIgnore {
                it[ReconferenciaChecksTable.tenantId] = tenantId
                it[ReconferenciaChecksTable.sessaoId] = sessaoId
                it[codprod] = req.codprod
                it[ReconferenciaChecksTable.controle] = controle
                it[checadoPor] = por
                it[checadoEm] = Instant.now()
            }
        } else {
            ReconferenciaChecksTable.deleteWhere {
                (ReconferenciaChecksTable.tenantId eq tenantId) and (ReconferenciaChecksTable.sessaoId eq sessaoId) and
                    (ReconferenciaChecksTable.codprod eq req.codprod) and (ReconferenciaChecksTable.controle eq controle)
            }
        }
    }

    /** Conferências FINALIZADAS (sessão concluída) dos últimos [dias] dias, mais recentes primeiro. */
    fun listarFinalizadas(tenantId: UUID, dias: Long): List<ReconferenciaResumoDto> = TenantTx.run(tenantId) {
        val desde = Instant.now().minusSeconds(dias * 86_400)
        val sessoes = SeparacaoSessoesTable.selectAll()
            .where {
                (SeparacaoSessoesTable.tenantId eq tenantId) and (SeparacaoSessoesTable.status eq SeparacaoStatus.CONCLUIDA) and
                    (SeparacaoSessoesTable.atualizadoEm greaterEq desde)
            }
            .orderBy(SeparacaoSessoesTable.atualizadoEm, SortOrder.DESC)
            .toList()
        if (sessoes.isEmpty()) return@run emptyList()
        val ids = sessoes.map { it[SeparacaoSessoesTable.id] }
        val nunotas = sessoes.map { it[SeparacaoSessoesTable.nunota] }.distinct()
        val dadosPorNunota = TarefasTable.selectAll()
            .where { (TarefasTable.tenantId eq tenantId) and (TarefasTable.nunota inList nunotas) }
            .associate { it[TarefasTable.nunota] to dadosDe(it[TarefasTable.dados]) }
        // Total de itens conferidos (produto+controle distintos com qtd > 0) e checados, por sessão.
        val itensPorSessao = SeparacaoItensTable.selectAll()
            .where {
                (SeparacaoItensTable.tenantId eq tenantId) and (SeparacaoItensTable.sessaoId inList ids) and
                    (SeparacaoItensTable.silencioso eq false)
            }
            .filter { it[SeparacaoItensTable.qtdConferidaLocal].signum() > 0 }
            .groupBy({ it[SeparacaoItensTable.sessaoId] }, { it[SeparacaoItensTable.codprod] to it[SeparacaoItensTable.controle].trim() })
            .mapValues { it.value.toSet().size }
        val checadosPorSessao = ReconferenciaChecksTable.selectAll()
            .where { (ReconferenciaChecksTable.tenantId eq tenantId) and (ReconferenciaChecksTable.sessaoId inList ids) }
            .groupingBy { it[ReconferenciaChecksTable.sessaoId] }.eachCount()
        sessoes.map { s ->
            val id = s[SeparacaoSessoesTable.id]
            val d = dadosPorNunota[s[SeparacaoSessoesTable.nunota]]
            val mod = TarefasRepository.modalidadeDe(d)
            ReconferenciaResumoDto(
                sessaoId = id.toString(),
                nunota = s[SeparacaoSessoesTable.nunota].toLong(),
                numNota = d.campo("NUMNOTA")?.let { it.toLongOrNull() ?: it.toDoubleOrNull()?.toLong() },
                cliente = d.campo("Parceiro.NOMEPARC"),
                ordemCarga = TarefasRepository.normalizarOrdemCarga(d.campo("ORDEMCARGA")),
                dataMovimento = d.campo("DTNEG"),
                finalizadaEm = iso.format(s[SeparacaoSessoesTable.atualizadoEm]),
                express = mod.express, retira = mod.retira, entrega = mod.entrega,
                totalItens = itensPorSessao[id] ?: 0,
                checados = checadosPorSessao[id] ?: 0,
            )
        }
    }
}

fun Route.reconferenciaRoutes() {
    route("/api/reconferencia") {
        get {
            val claims = call.exigirAuth() ?: return@get
            val dias = call.request.queryParameters["dias"]?.toLongOrNull()?.coerceIn(1, 60) ?: 7
            call.respond(ReconferenciaService.listarFinalizadas(claims.tenantId, dias))
        }
        get("/{sessaoId}") {
            val claims = call.exigirAuth() ?: return@get
            val id = call.parameters["sessaoId"]?.let { runCatching { UUID.fromString(it) }.getOrNull() }
                ?: return@get call.respond(HttpStatusCode.BadRequest, mapOf("erro" to "sessão inválida"))
            val d = ReconferenciaService.detalhe(claims.tenantId, id)
                ?: return@get call.respond(HttpStatusCode.NotFound, mapOf("erro" to "conferência não encontrada"))
            call.respond(d)
        }
        put("/{sessaoId}/check") {
            val claims = call.exigirAuth() ?: return@put
            val id = call.parameters["sessaoId"]?.let { runCatching { UUID.fromString(it) }.getOrNull() }
                ?: return@put call.respond(HttpStatusCode.BadRequest, mapOf("erro" to "sessão inválida"))
            val req = call.receive<CheckRequest>()
            val nome = UsuariosRepository.buscarPorId(claims.tenantId, claims.userId)?.nome
            ReconferenciaService.marcar(claims.tenantId, id, req, nome)
            call.respond(mapOf("ok" to true))
        }
    }
}
