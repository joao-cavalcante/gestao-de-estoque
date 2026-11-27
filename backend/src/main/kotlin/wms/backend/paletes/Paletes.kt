package wms.backend.paletes

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.javatime.timestamp
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.TransactionManager
import org.jetbrains.exposed.sql.update
import wms.backend.auth.ClaimsToken
import wms.backend.auth.exigirAuth
import wms.backend.separacao.SeparacaoItensTable
import wms.backend.separacao.SeparacaoLeiturasTable
import wms.backend.separacao.SeparacaoSessoesTable
import wms.backend.tenancy.TenantRepository
import wms.backend.tenancy.TenantTx
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Instant
import java.util.UUID

/**
 * Paletes do recebimento (endereçamento etapa 4a, 27/11/2026) — LPN montado na conferência da compra.
 * "Fechar palete": o que foi lido de cada produto e ainda não está em palete (lido − paletizado) vira um palete.
 * Um produto = NORMAL; vários = MISTO (só com ParametrosPaletes.permitirMisto). Espelha o nativo do Sankhya
 * (TGWRPL/TGWRUMA/TGWEPL — ver sankhya-hub docs/sankhya-wms.md "Pálete / UMA no recebimento").
 * A tarefa e a armazenagem por palete (addon TOR) são a etapa 4b.
 */
object RecebimentoPaletesTable : Table("app.recebimento_paletes") {
    val id = uuid("id")
    val tenantId = uuid("tenant_id")
    val sessaoId = uuid("sessao_id")
    val nunota = integer("nunota")
    val numero = long("numero")
    val uma = text("uma")
    val seq = integer("seq")
    val tipo = text("tipo")
    val impresso = bool("impresso")
    val criadoPorNome = text("criado_por_nome").nullable()
    val criadoEm = timestamp("criado_em")
    val canceladoEm = timestamp("cancelado_em").nullable()
    override val primaryKey = PrimaryKey(id)
}

object RecebimentoPaleteItensTable : Table("app.recebimento_palete_itens") {
    val id = uuid("id")
    val tenantId = uuid("tenant_id")
    val paleteId = uuid("palete_id")
    val codprod = integer("codprod")
    val controle = text("controle")
    val descricao = text("descricao").nullable()
    val qtd = decimal("qtd", 15, 5)
    val unidade = text("unidade").nullable()
    val qtdComercial = decimal("qtd_comercial", 15, 5).nullable()
    val unidadeComercial = text("unidade_comercial").nullable()
    override val primaryKey = PrimaryKey(id)
}

@Serializable
data class PaleteItemDto(
    val codprod: Int,
    val controle: String,
    val descricao: String?,
    val qtd: Double,
    val unidade: String?,
    val qtdComercial: Double?,
    val unidadeComercial: String?,
)

@Serializable
data class PaleteDto(
    val id: String,
    val nunota: Int,
    val numero: Long,
    val uma: String,
    val seq: Int,
    val tipo: String,
    val impresso: Boolean,
    val criadoPor: String?,
    val criadoEm: String,
    val cancelado: Boolean,
    val itens: List<PaleteItemDto>,
)

@Serializable
data class ResumoPaletesDto(val permitirMisto: Boolean, val pendentes: List<PaleteItemDto>, val paletes: List<PaleteDto>)

@Serializable
data class ChaveProduto(val codprod: Int, val controle: String = " ")

@Serializable
data class FecharPaleteRequest(
    /** Produtos a fechar; vazio = todos os que têm quantidade sem palete. */
    val itens: List<ChaveProduto> = emptyList(),
    /** Sem palete misto: fecha um palete por produto (usado ao finalizar a conferência). */
    val umPorProduto: Boolean = false,
)

@Serializable
data class IdsRequest(val ids: List<String>)

class PaleteException(msg: String, val status: HttpStatusCode = HttpStatusCode.BadRequest) : Exception(msg)

object Paletes {

    private val json = Json { ignoreUnknownKeys = true }
    private fun c(s: String?) = s?.takeIf { it.isNotBlank() } ?: " "

    fun permitirMisto(tenantId: UUID): Boolean = runCatching { TenantRepository.parametros(tenantId).paletes.permitirMisto }.getOrDefault(false)

    private data class Meta(val descricao: String?, val unidade: String?, val unidadeComercial: String?, val dm: String?, val fator: BigDecimal?)

    /** Descrição e unidades do produto na conferência (a 1ª linha do item basta). Precisa de transação aberta. */
    private fun metas(tenantId: UUID, sessaoId: UUID): Map<Pair<Int, String>, Meta> =
        SeparacaoItensTable.selectAll()
            .where { (SeparacaoItensTable.tenantId eq tenantId) and (SeparacaoItensTable.sessaoId eq sessaoId) }
            .associate { r ->
                val desc = runCatching {
                    (json.parseToJsonElement(r[SeparacaoItensTable.dados].toString()) as? JsonObject)
                        ?.get("Produto.DESCRPROD")?.let { (it as? JsonPrimitive)?.contentOrNull }
                }.getOrNull()
                (r[SeparacaoItensTable.codprod] to c(r[SeparacaoItensTable.controle])) to Meta(
                    desc?.trim(),
                    r[SeparacaoItensTable.unidadePadrao] ?: r[SeparacaoItensTable.codvol],
                    r[SeparacaoItensTable.unidadeComercial],
                    r[SeparacaoItensTable.divideMultiplica],
                    r[SeparacaoItensTable.fatorConversao],
                )
            }

    /** Quantidade na unidade da compra: 'M' → padrão ÷ fator; 'D' → padrão × fator; mesma unidade → null. */
    internal fun comercial(qtd: BigDecimal, unidade: String?, unidadeComercial: String?, dm: String?, fator: BigDecimal?): BigDecimal? {
        if (unidadeComercial.isNullOrBlank() || unidadeComercial == unidade || fator == null || fator.signum() <= 0) return null
        return when (dm?.trim()) {
            "M" -> qtd.divide(fator, 3, RoundingMode.HALF_UP)
            "D" -> qtd.multiply(fator).setScale(3, RoundingMode.HALF_UP)
            else -> null
        }
    }

    private fun item(cod: Int, ctl: String, qtd: BigDecimal, m: Meta?) = PaleteItemDto(
        cod, ctl, m?.descricao, qtd.toDouble(), m?.unidade,
        comercial(qtd, m?.unidade, m?.unidadeComercial, m?.dm, m?.fator)?.toDouble(),
        m?.unidadeComercial?.takeIf { comercial(qtd, m.unidade, it, m.dm, m.fator) != null },
    )

    /** Lido − paletizado por produto (só o que é > 0). Precisa de transação aberta. */
    private fun pendentes(tenantId: UUID, sessaoId: UUID): Map<Pair<Int, String>, BigDecimal> {
        val lido = HashMap<Pair<Int, String>, BigDecimal>()
        SeparacaoLeiturasTable.selectAll()
            .where { (SeparacaoLeiturasTable.tenantId eq tenantId) and (SeparacaoLeiturasTable.sessaoId eq sessaoId) }
            .forEach { r ->
                val k = r[SeparacaoLeiturasTable.codprod] to c(r[SeparacaoLeiturasTable.controle])
                lido[k] = (lido[k] ?: BigDecimal.ZERO) + r[SeparacaoLeiturasTable.qtd]
            }
        val ativos = RecebimentoPaletesTable.selectAll()
            .where { (RecebimentoPaletesTable.tenantId eq tenantId) and (RecebimentoPaletesTable.sessaoId eq sessaoId) and (RecebimentoPaletesTable.canceladoEm.isNull()) }
            .map { it[RecebimentoPaletesTable.id] }.toSet()
        if (ativos.isNotEmpty()) {
            RecebimentoPaleteItensTable.selectAll()
                .where { (RecebimentoPaleteItensTable.tenantId eq tenantId) }
                .filter { it[RecebimentoPaleteItensTable.paleteId] in ativos }
                .forEach { r ->
                    val k = r[RecebimentoPaleteItensTable.codprod] to c(r[RecebimentoPaleteItensTable.controle])
                    lido[k] = (lido[k] ?: BigDecimal.ZERO) - r[RecebimentoPaleteItensTable.qtd]
                }
        }
        return lido.filterValues { it.signum() > 0 }
    }

    private fun paletesDto(tenantId: UUID, filtro: (org.jetbrains.exposed.sql.ResultRow) -> Boolean, linhas: List<org.jetbrains.exposed.sql.ResultRow>): List<PaleteDto> {
        val sel = linhas.filter(filtro)
        if (sel.isEmpty()) return emptyList()
        val ids = sel.map { it[RecebimentoPaletesTable.id] }.toSet()
        val itens = RecebimentoPaleteItensTable.selectAll().where { RecebimentoPaleteItensTable.tenantId eq tenantId }
            .filter { it[RecebimentoPaleteItensTable.paleteId] in ids }
            .groupBy { it[RecebimentoPaleteItensTable.paleteId] }
        return sel.map { p ->
            PaleteDto(
                id = p[RecebimentoPaletesTable.id].toString(),
                nunota = p[RecebimentoPaletesTable.nunota],
                numero = p[RecebimentoPaletesTable.numero],
                uma = p[RecebimentoPaletesTable.uma],
                seq = p[RecebimentoPaletesTable.seq],
                tipo = p[RecebimentoPaletesTable.tipo],
                impresso = p[RecebimentoPaletesTable.impresso],
                criadoPor = p[RecebimentoPaletesTable.criadoPorNome],
                criadoEm = p[RecebimentoPaletesTable.criadoEm].toString(),
                cancelado = p[RecebimentoPaletesTable.canceladoEm] != null,
                itens = (itens[p[RecebimentoPaletesTable.id]] ?: emptyList()).map { i ->
                    PaleteItemDto(
                        i[RecebimentoPaleteItensTable.codprod], i[RecebimentoPaleteItensTable.controle], i[RecebimentoPaleteItensTable.descricao],
                        i[RecebimentoPaleteItensTable.qtd].toDouble(), i[RecebimentoPaleteItensTable.unidade],
                        i[RecebimentoPaleteItensTable.qtdComercial]?.toDouble(), i[RecebimentoPaleteItensTable.unidadeComercial],
                    )
                }.sortedBy { it.codprod },
            )
        }.sortedBy { it.seq }
    }

    private fun sessao(tenantId: UUID, sessaoId: UUID) =
        SeparacaoSessoesTable.selectAll().where { (SeparacaoSessoesTable.tenantId eq tenantId) and (SeparacaoSessoesTable.id eq sessaoId) }.singleOrNull()
            ?: throw PaleteException("Conferência não encontrada.", HttpStatusCode.NotFound)

    fun resumo(tenantId: UUID, sessaoId: UUID): ResumoPaletesDto = TenantTx.run(tenantId) {
        sessao(tenantId, sessaoId)
        val metas = metas(tenantId, sessaoId)
        val pend = pendentes(tenantId, sessaoId).map { (k, q) -> item(k.first, k.second, q, metas[k]) }.sortedBy { it.codprod }
        val todos = RecebimentoPaletesTable.selectAll()
            .where { (RecebimentoPaletesTable.tenantId eq tenantId) and (RecebimentoPaletesTable.sessaoId eq sessaoId) }.toList()
        ResumoPaletesDto(permitirMisto(tenantId), pend, paletesDto(tenantId, { it[RecebimentoPaletesTable.canceladoEm] == null }, todos))
    }

    /** Fecha palete(s) com o que está sem palete. Devolve os paletes criados (para imprimir). */
    fun fechar(tenantId: UUID, sessaoId: UUID, req: FecharPaleteRequest, nomeUsuario: String): List<PaleteDto> = TenantTx.run(tenantId) {
        val s = sessao(tenantId, sessaoId)
        val pend = pendentes(tenantId, sessaoId)
        val escolhidos = if (req.itens.isEmpty()) pend else req.itens.associate { (it.codprod to c(it.controle)) to (pend[it.codprod to c(it.controle)] ?: BigDecimal.ZERO) }.filterValues { it.signum() > 0 }
        if (escolhidos.isEmpty()) throw PaleteException("Nada lido sem palete: bipe os produtos antes de fechar o palete.")
        val misto = permitirMisto(tenantId)
        val grupos: List<Map<Pair<Int, String>, BigDecimal>> = when {
            escolhidos.size == 1 || misto -> listOf(escolhidos)
            req.umPorProduto -> escolhidos.map { mapOf(it.key to it.value) }
            else -> throw PaleteException("Palete misto não está ligado: escolha o produto do palete.", HttpStatusCode.Conflict)
        }
        val metas = metas(tenantId, sessaoId)
        var seq = RecebimentoPaletesTable.selectAll()
            .where { (RecebimentoPaletesTable.tenantId eq tenantId) and (RecebimentoPaletesTable.nunota eq s[SeparacaoSessoesTable.nunota]) }
            .count().toInt()
        val criados = ArrayList<UUID>()
        for (g in grupos) {
            val numero = TransactionManager.current().exec("select nextval('app.palete_numero_seq')") { rs -> rs.next(); rs.getLong(1) }!!
            val id = UUID.randomUUID()
            seq++
            RecebimentoPaletesTable.insert {
                it[RecebimentoPaletesTable.id] = id
                it[RecebimentoPaletesTable.tenantId] = tenantId
                it[RecebimentoPaletesTable.sessaoId] = sessaoId
                it[nunota] = s[SeparacaoSessoesTable.nunota]
                it[RecebimentoPaletesTable.numero] = numero
                it[uma] = uma(numero)
                it[RecebimentoPaletesTable.seq] = seq
                it[tipo] = if (g.size == 1) "NORMAL" else "MISTO"
                it[impresso] = false
                it[criadoPorNome] = nomeUsuario
                it[criadoEm] = Instant.now()
            }
            for ((k, q) in g) {
                val m = metas[k]
                RecebimentoPaleteItensTable.insert {
                    it[RecebimentoPaleteItensTable.id] = UUID.randomUUID()
                    it[RecebimentoPaleteItensTable.tenantId] = tenantId
                    it[paleteId] = id
                    it[codprod] = k.first
                    it[controle] = k.second
                    it[descricao] = m?.descricao
                    it[qtd] = q
                    it[unidade] = m?.unidade
                    it[qtdComercial] = comercial(q, m?.unidade, m?.unidadeComercial, m?.dm, m?.fator)
                    it[unidadeComercial] = m?.unidadeComercial?.takeIf { comercial(q, m.unidade, it, m.dm, m.fator) != null }
                }
            }
            criados += id
        }
        val todos = RecebimentoPaletesTable.selectAll().where { RecebimentoPaletesTable.tenantId eq tenantId }.toList()
        paletesDto(tenantId, { it[RecebimentoPaletesTable.id] in criados }, todos)
    }

    /** Código da etiqueta (UMA): "P" + 7 dígitos. */
    internal fun uma(numero: Long): String = "P" + numero.toString().padStart(7, '0')

    fun porIds(tenantId: UUID, ids: List<UUID>): List<PaleteDto> = TenantTx.run(tenantId) {
        val todos = RecebimentoPaletesTable.selectAll().where { RecebimentoPaletesTable.tenantId eq tenantId }.toList()
        paletesDto(tenantId, { it[RecebimentoPaletesTable.id] in ids.toSet() }, todos)
    }

    fun porNunota(tenantId: UUID, nunota: Int): List<PaleteDto> = TenantTx.run(tenantId) {
        val todos = RecebimentoPaletesTable.selectAll()
            .where { (RecebimentoPaletesTable.tenantId eq tenantId) and (RecebimentoPaletesTable.nunota eq nunota) }
            .orderBy(RecebimentoPaletesTable.seq, SortOrder.ASC).toList()
        paletesDto(tenantId, { it[RecebimentoPaletesTable.canceladoEm] == null }, todos)
    }

    fun marcarImpresso(tenantId: UUID, ids: List<UUID>): Unit = TenantTx.run(tenantId) {
        for (id in ids) RecebimentoPaletesTable.update({ (RecebimentoPaletesTable.tenantId eq tenantId) and (RecebimentoPaletesTable.id eq id) }) {
            it[impresso] = true
        }
    }

    /**
     * Leitura de um produto desfeita (ou a conferência recomeçou): cancela os paletes que têm esse produto — o que
     * estava neles volta a ficar sem palete. Chamado DENTRO da transação de quem desfaz.
     */
    fun cancelarNaTransacao(tenantId: UUID, sessaoId: UUID, codprod: Int? = null, controle: String? = null) {
        val ativos = RecebimentoPaletesTable.selectAll()
            .where { (RecebimentoPaletesTable.tenantId eq tenantId) and (RecebimentoPaletesTable.sessaoId eq sessaoId) and (RecebimentoPaletesTable.canceladoEm.isNull()) }
            .map { it[RecebimentoPaletesTable.id] }.toSet()
        if (ativos.isEmpty()) return
        val alvo = if (codprod == null) ativos else RecebimentoPaleteItensTable.selectAll()
            .where { (RecebimentoPaleteItensTable.tenantId eq tenantId) and (RecebimentoPaleteItensTable.codprod eq codprod) }
            .filter { it[RecebimentoPaleteItensTable.paleteId] in ativos && (controle == null || c(it[RecebimentoPaleteItensTable.controle]) == c(controle)) }
            .map { it[RecebimentoPaleteItensTable.paleteId] }.toSet()
        val agora = Instant.now()
        for (id in alvo) RecebimentoPaletesTable.update({ (RecebimentoPaletesTable.tenantId eq tenantId) and (RecebimentoPaletesTable.id eq id) }) {
            it[canceladoEm] = agora
        }
    }
}

private fun nome(claims: ClaimsToken): String = runCatching { wms.backend.galpao.nomeUsuario(claims) }.getOrDefault("Usuário")

private suspend fun ApplicationCall.paleteErro(e: Exception) = when (e) {
    is PaleteException -> respond(e.status, mapOf("erro" to e.message))
    else -> respond(HttpStatusCode.InternalServerError, mapOf("erro" to (e.message ?: "falha nos paletes")))
}

fun Route.paletesRoutes() {
    route("/api/paletes") {
        get("/sessao/{sessaoId}") {
            val claims = call.exigirAuth() ?: return@get
            val sessaoId = runCatching { UUID.fromString(call.parameters["sessaoId"]) }.getOrNull()
                ?: return@get call.respond(HttpStatusCode.BadRequest, mapOf("erro" to "sessão inválida"))
            try { call.respond(Paletes.resumo(claims.tenantId, sessaoId)) } catch (e: Exception) { call.paleteErro(e) }
        }
        post("/sessao/{sessaoId}/fechar") {
            val claims = call.exigirAuth() ?: return@post
            val sessaoId = runCatching { UUID.fromString(call.parameters["sessaoId"]) }.getOrNull()
                ?: return@post call.respond(HttpStatusCode.BadRequest, mapOf("erro" to "sessão inválida"))
            val body = runCatching { call.receive<FecharPaleteRequest>() }.getOrDefault(FecharPaleteRequest())
            try { call.respond(Paletes.fechar(claims.tenantId, sessaoId, body, nome(claims))) } catch (e: Exception) { call.paleteErro(e) }
        }
        get {
            val claims = call.exigirAuth() ?: return@get
            val ids = call.request.queryParameters["ids"].orEmpty().split(',').mapNotNull { runCatching { UUID.fromString(it.trim()) }.getOrNull() }
            call.respond(Paletes.porIds(claims.tenantId, ids))
        }
        get("/nunota/{nunota}") {
            val claims = call.exigirAuth() ?: return@get
            val nunota = call.parameters["nunota"]?.toIntOrNull() ?: return@get call.respond(HttpStatusCode.BadRequest, mapOf("erro" to "nº único inválido"))
            call.respond(Paletes.porNunota(claims.tenantId, nunota))
        }
        post("/impresso") {
            val claims = call.exigirAuth() ?: return@post
            val ids = call.receive<IdsRequest>().ids.mapNotNull { runCatching { UUID.fromString(it) }.getOrNull() }
            Paletes.marcarImpresso(claims.tenantId, ids)
            call.respond(HttpStatusCode.NoContent)
        }
    }
}
