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
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.javatime.date
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
    /** V82 — temperatura medida no palete (frios/congelados), opcional. */
    val temperatura = decimal("temperatura", 6, 2).nullable()
    /** V84 — conferente dono do palete (o que ele leu sai do bolso dele). */
    val criadoPorUser = uuid("criado_por_user").nullable()
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
    /** V82 — Tempo 2 da doca: lote/validade do item (null = lote pendente) e avaria. */
    val lote = text("lote").nullable()
    val validade = date("validade").nullable()
    val fabricacao = date("fabricacao").nullable()
    val origemLote = text("origem_lote").nullable()
    val qtdAvaria = decimal("qtd_avaria", 15, 5).nullable()
    val motivoAvaria = text("motivo_avaria").nullable()
    /** V83 — amostragem de embalagem: unidades contadas numa caixa × o esperado pelo cadastro (fator da unidade). */
    val amostraUnidades = decimal("amostra_unidades", 15, 5).nullable()
    val amostraEsperado = decimal("amostra_esperado", 15, 5).nullable()
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
    val lote: String? = null,
    /** ISO yyyy-MM-dd. */
    val validade: String? = null,
    val fabricacao: String? = null,
    val origemLote: String? = null,
    val qtdAvaria: Double? = null,
    val motivoAvaria: String? = null,
    val amostraUnidades: Double? = null,
    val amostraEsperado: Double? = null,
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
    val temperatura: Double? = null,
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

@Serializable
data class LoteLinha(val lote: String? = null, val validade: String? = null, val fabricacao: String? = null, val origem: String? = null, val qtd: Double? = null)

@Serializable
data class LotesItem(val codprod: Int, val linhas: List<LoteLinha>)

@Serializable
data class LotesPaleteRequest(val itens: List<LotesItem> = emptyList(), val temperatura: Double? = null)

@Serializable
data class AmostraRequest(val codprod: Int, val unidades: Double)

@Serializable
data class AvariaPaleteRequest(val codprod: Int, val qtd: Double, val motivo: String? = null)

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
    /**
     * V84 — o que está sem palete, por conferente. Cada leitura e cada palete têm dono; leituras antigas (sem dono)
     * formam um bolso comum que todos enxergam. Palete de um conferente acima do que ele leu consome do bolso comum.
     */
    internal fun bolsos(
        lidas: List<Triple<UUID?, Pair<Int, String>, BigDecimal>>,
        paletizadas: List<Triple<UUID?, Pair<Int, String>, BigDecimal>>,
    ): Pair<Map<UUID, Map<Pair<Int, String>, BigDecimal>>, Map<Pair<Int, String>, BigDecimal>> {
        val lido = HashMap<UUID?, HashMap<Pair<Int, String>, BigDecimal>>()
        for ((u, k, q) in lidas) lido.getOrPut(u) { HashMap() }.merge(k, q, BigDecimal::add)
        val pal = HashMap<UUID?, HashMap<Pair<Int, String>, BigDecimal>>()
        for ((u, k, q) in paletizadas) pal.getOrPut(u) { HashMap() }.merge(k, q, BigDecimal::add)
        val comum = HashMap<Pair<Int, String>, BigDecimal>()
        lido[null]?.forEach { (k, q) -> comum.merge(k, q, BigDecimal::add) }
        pal[null]?.forEach { (k, q) -> comum.merge(k, q.negate(), BigDecimal::add) }
        val donos = (lido.keys + pal.keys).filterNotNull().toSet()
        val proprio = HashMap<UUID, Map<Pair<Int, String>, BigDecimal>>()
        for (u in donos) {
            val m = HashMap<Pair<Int, String>, BigDecimal>()
            lido[u]?.forEach { (k, q) -> m.merge(k, q, BigDecimal::add) }
            pal[u]?.forEach { (k, q) -> m.merge(k, q.negate(), BigDecimal::add) }
            for ((k, q) in m) if (q.signum() < 0) comum.merge(k, q, BigDecimal::add)
            proprio[u] = m.filterValues { it.signum() > 0 }
        }
        return proprio to comum.filterValues { it.signum() > 0 }
    }

    private fun bolsosDaSessao(tenantId: UUID, sessaoId: UUID): Pair<Map<UUID, Map<Pair<Int, String>, BigDecimal>>, Map<Pair<Int, String>, BigDecimal>> {
        val lidas = SeparacaoLeiturasTable.selectAll()
            .where { (SeparacaoLeiturasTable.tenantId eq tenantId) and (SeparacaoLeiturasTable.sessaoId eq sessaoId) }
            .map { Triple(it[SeparacaoLeiturasTable.conferenteUserId], it[SeparacaoLeiturasTable.codprod] to c(it[SeparacaoLeiturasTable.controle]), it[SeparacaoLeiturasTable.qtd]) }
        val donoPalete = RecebimentoPaletesTable.selectAll()
            .where { (RecebimentoPaletesTable.tenantId eq tenantId) and (RecebimentoPaletesTable.sessaoId eq sessaoId) and (RecebimentoPaletesTable.canceladoEm.isNull()) }
            .associate { it[RecebimentoPaletesTable.id] to it[RecebimentoPaletesTable.criadoPorUser] }
        val paletizadas = if (donoPalete.isEmpty()) emptyList() else RecebimentoPaleteItensTable.selectAll()
            .where { RecebimentoPaleteItensTable.tenantId eq tenantId }
            .filter { it[RecebimentoPaleteItensTable.paleteId] in donoPalete.keys }
            .map { Triple(donoPalete[it[RecebimentoPaleteItensTable.paleteId]], it[RecebimentoPaleteItensTable.codprod] to c(it[RecebimentoPaleteItensTable.controle]), it[RecebimentoPaleteItensTable.qtd]) }
        return bolsos(lidas, paletizadas)
    }

    /** Sem palete para [userId]: o bolso dele + o comum. userId null = a sessão inteira (comportamento anterior). */
    private fun pendentesDe(tenantId: UUID, sessaoId: UUID, userId: UUID?): Map<Pair<Int, String>, BigDecimal> {
        if (userId == null) return pendentes(tenantId, sessaoId)
        val (proprio, comum) = bolsosDaSessao(tenantId, sessaoId)
        val r = HashMap<Pair<Int, String>, BigDecimal>(comum)
        proprio[userId]?.forEach { (k, q) -> r.merge(k, q, BigDecimal::add) }
        return r
    }

    /** Descrição do produto na conferência (null = não está nela). Usado pela fila de pesagem. */
    fun descricao(tenantId: UUID, sessaoId: UUID, codprod: Int, controle: String): String? = TenantTx.run(tenantId) {
        val m = metas(tenantId, sessaoId)
        if ((codprod to c(controle)) !in m && m.keys.none { it.first == codprod }) null
        else (m[codprod to c(controle)] ?: m.entries.first { it.key.first == codprod }.value).descricao ?: "Produto $codprod"
    }

    /** Para finalizar: outros conferentes com leitura fora de palete (nome → quantidade). */
    fun semPaleteDeOutros(tenantId: UUID, sessaoId: UUID, eu: UUID): List<Pair<String, BigDecimal>> = TenantTx.run(tenantId) {
        val temPalete = RecebimentoPaletesTable.selectAll()
            .where { (RecebimentoPaletesTable.tenantId eq tenantId) and (RecebimentoPaletesTable.sessaoId eq sessaoId) and (RecebimentoPaletesTable.canceladoEm.isNull()) }
            .any()
        if (!temPalete) return@run emptyList()
        val (proprio, _) = bolsosDaSessao(tenantId, sessaoId)
        val nomes = SeparacaoLeiturasTable.selectAll()
            .where { (SeparacaoLeiturasTable.tenantId eq tenantId) and (SeparacaoLeiturasTable.sessaoId eq sessaoId) }
            .mapNotNull { r -> r[SeparacaoLeiturasTable.conferenteUserId]?.let { it to (r[SeparacaoLeiturasTable.conferenteNome] ?: "outro conferente") } }
            .toMap()
        proprio.filterKeys { it != eu }.mapNotNull { (u, m) ->
            val t = m.values.fold(BigDecimal.ZERO, BigDecimal::add)
            if (t.signum() > 0) (nomes[u] ?: "outro conferente") to t else null
        }
    }

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
                temperatura = p[RecebimentoPaletesTable.temperatura]?.toDouble(),
                itens = (itens[p[RecebimentoPaletesTable.id]] ?: emptyList()).map { i ->
                    PaleteItemDto(
                        i[RecebimentoPaleteItensTable.codprod], i[RecebimentoPaleteItensTable.controle], i[RecebimentoPaleteItensTable.descricao],
                        i[RecebimentoPaleteItensTable.qtd].toDouble(), i[RecebimentoPaleteItensTable.unidade],
                        i[RecebimentoPaleteItensTable.qtdComercial]?.toDouble(), i[RecebimentoPaleteItensTable.unidadeComercial],
                        lote = i[RecebimentoPaleteItensTable.lote],
                        validade = i[RecebimentoPaleteItensTable.validade]?.toString(),
                        fabricacao = i[RecebimentoPaleteItensTable.fabricacao]?.toString(),
                        origemLote = i[RecebimentoPaleteItensTable.origemLote],
                        qtdAvaria = i[RecebimentoPaleteItensTable.qtdAvaria]?.toDouble(),
                        motivoAvaria = i[RecebimentoPaleteItensTable.motivoAvaria],
                        amostraUnidades = i[RecebimentoPaleteItensTable.amostraUnidades]?.toDouble(),
                        amostraEsperado = i[RecebimentoPaleteItensTable.amostraEsperado]?.toDouble(),
                    )
                }.sortedBy { it.codprod },
            )
        }.sortedBy { it.seq }
    }

    private fun sessao(tenantId: UUID, sessaoId: UUID) =
        SeparacaoSessoesTable.selectAll().where { (SeparacaoSessoesTable.tenantId eq tenantId) and (SeparacaoSessoesTable.id eq sessaoId) }.singleOrNull()
            ?: throw PaleteException("Conferência não encontrada.", HttpStatusCode.NotFound)

    fun resumo(tenantId: UUID, sessaoId: UUID, userId: UUID? = null): ResumoPaletesDto = TenantTx.run(tenantId) {
        sessao(tenantId, sessaoId)
        val metas = metas(tenantId, sessaoId)
        val pend = pendentesDe(tenantId, sessaoId, userId).map { (k, q) -> item(k.first, k.second, q, metas[k]) }.sortedBy { it.codprod }
        val todos = RecebimentoPaletesTable.selectAll()
            .where { (RecebimentoPaletesTable.tenantId eq tenantId) and (RecebimentoPaletesTable.sessaoId eq sessaoId) }.toList()
        ResumoPaletesDto(permitirMisto(tenantId), pend, paletesDto(tenantId, { it[RecebimentoPaletesTable.canceladoEm] == null }, todos))
    }

    /** Fecha palete(s) com o que está sem palete. Devolve os paletes criados (para imprimir). */
    fun fechar(
        tenantId: UUID,
        sessaoId: UUID,
        req: FecharPaleteRequest,
        nomeUsuario: String,
        userId: UUID? = null,
        /** V84 — pesagem: fecha exatamente esta quantidade (a da leitura pesada), não tudo o que estiver sem palete. */
        qtdExata: Pair<Pair<Int, String>, BigDecimal>? = null,
    ): List<PaleteDto> = TenantTx.run(tenantId) {
        val s = sessao(tenantId, sessaoId)
        val pend = pendentesDe(tenantId, sessaoId, userId)
        val escolhidos = if (qtdExata != null) {
            val disp = pend[qtdExata.first] ?: BigDecimal.ZERO
            if (disp < qtdExata.second) throw PaleteException("A quantidade pesada já está em outro palete.")
            mapOf(qtdExata)
        } else if (req.itens.isEmpty()) pend else req.itens.associate { (it.codprod to c(it.controle)) to (pend[it.codprod to c(it.controle)] ?: BigDecimal.ZERO) }.filterValues { it.signum() > 0 }
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
                it[criadoPorUser] = userId
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
                    // D3 — lido já com lote (nota dividida por lote na importação do XML): o palete nasce com ele.
                    k.second.trim().takeIf { l -> l.isNotEmpty() }?.let { l ->
                        it[RecebimentoPaleteItensTable.lote] = l
                        it[RecebimentoPaleteItensTable.origemLote] = "XML"
                    }
                }
            }
            criados += id
        }
        val todos = RecebimentoPaletesTable.selectAll().where { RecebimentoPaletesTable.tenantId eq tenantId }.toList()
        paletesDto(tenantId, { it[RecebimentoPaletesTable.id] in criados }, todos)
    }

    /**
     * Tempo 2 da doca (V82): grava lote/validade dos itens do palete. Cada produto pode virar N linhas ("outro lote
     * neste palete") — as quantidades das linhas têm de somar a quantidade do item (na unidade padrão); a quantidade
     * comercial é rateada. Linha sem qtd = o item inteiro.
     */
    fun definirLotes(tenantId: UUID, paleteId: UUID, req: LotesPaleteRequest): PaleteDto = TenantTx.run(tenantId) {
        val p = RecebimentoPaletesTable.selectAll()
            .where { (RecebimentoPaletesTable.tenantId eq tenantId) and (RecebimentoPaletesTable.id eq paleteId) }.singleOrNull()
            ?: throw PaleteException("Palete não encontrado.", HttpStatusCode.NotFound)
        if (p[RecebimentoPaletesTable.canceladoEm] != null) throw PaleteException("Este palete foi cancelado.")
        val itens = RecebimentoPaleteItensTable.selectAll()
            .where { (RecebimentoPaleteItensTable.tenantId eq tenantId) and (RecebimentoPaleteItensTable.paleteId eq paleteId) }.toList()
        for (r in req.itens) {
            val doProduto = itens.filter { it[RecebimentoPaleteItensTable.codprod] == r.codprod }
            if (doProduto.isEmpty()) throw PaleteException("O produto ${r.codprod} não está neste palete.")
            if (r.linhas.isEmpty()) continue
            val total = doProduto.fold(BigDecimal.ZERO) { a, i -> a + i[RecebimentoPaleteItensTable.qtd] }
            val totalCom = doProduto.mapNotNull { it[RecebimentoPaleteItensTable.qtdComercial] }.takeIf { it.size == doProduto.size }?.fold(BigDecimal.ZERO) { a, b -> a + b }
            val base = doProduto.first()
            r.linhas.forEach { l ->
                if (l.lote.isNullOrBlank()) throw PaleteException("Informe o lote (ou \"Sem lote na caixa\").")
                l.validade?.let { v -> runCatching { java.time.LocalDate.parse(v) }.getOrElse { throw PaleteException("Validade inválida: $v") } }
            }
            val qtds: List<BigDecimal> = if (r.linhas.size == 1 && r.linhas[0].qtd == null) listOf(total)
            else r.linhas.map { l -> l.qtd?.toBigDecimal() ?: throw PaleteException("Com mais de um lote, informe a quantidade de cada um.") }
            val soma = qtds.fold(BigDecimal.ZERO) { a, b -> a + b }
            if ((soma - total).abs() > BigDecimal("0.001")) {
                throw PaleteException("As quantidades dos lotes (${soma.stripTrailingZeros().toPlainString()}) não somam o palete (${total.stripTrailingZeros().toPlainString()}).")
            }
            // Avaria existente fica na 1ª linha (o lote não muda a avaria do item).
            val avaria = doProduto.mapNotNull { it[RecebimentoPaleteItensTable.qtdAvaria] }.fold(BigDecimal.ZERO) { a, b -> a + b }.takeIf { it.signum() > 0 }
            val motivo = doProduto.firstNotNullOfOrNull { it[RecebimentoPaleteItensTable.motivoAvaria] }
            for (i in doProduto) {
                val idItem = i[RecebimentoPaleteItensTable.id]
                RecebimentoPaleteItensTable.deleteWhere { (RecebimentoPaleteItensTable.tenantId eq tenantId) and (RecebimentoPaleteItensTable.id eq idItem) }
            }
            r.linhas.forEachIndexed { idx, l ->
                val q = qtds[idx]
                RecebimentoPaleteItensTable.insert {
                    it[RecebimentoPaleteItensTable.id] = UUID.randomUUID()
                    it[RecebimentoPaleteItensTable.tenantId] = tenantId
                    it[RecebimentoPaleteItensTable.paleteId] = paleteId
                    it[codprod] = r.codprod
                    it[controle] = base[RecebimentoPaleteItensTable.controle]
                    it[descricao] = base[RecebimentoPaleteItensTable.descricao]
                    it[qtd] = q
                    it[unidade] = base[RecebimentoPaleteItensTable.unidade]
                    it[qtdComercial] = totalCom?.let { tc -> if (total.signum() == 0) tc else tc.multiply(q).divide(total, 3, RoundingMode.HALF_UP) }
                    it[unidadeComercial] = base[RecebimentoPaleteItensTable.unidadeComercial]
                    it[lote] = l.lote!!.trim().uppercase().take(20)
                    it[validade] = l.validade?.let { v -> java.time.LocalDate.parse(v) }
                    it[fabricacao] = l.fabricacao?.let { v -> java.time.LocalDate.parse(v) }
                    it[origemLote] = l.origem?.takeIf { o -> o in ORIGENS_LOTE } ?: "DIGITADO"
                    if (idx == 0) {
                        it[qtdAvaria] = avaria
                        it[motivoAvaria] = motivo
                    }
                }
            }
        }
        req.temperatura?.let { t -> RecebimentoPaletesTable.update({ (RecebimentoPaletesTable.tenantId eq tenantId) and (RecebimentoPaletesTable.id eq paleteId) }) { it[temperatura] = t.toBigDecimal() } }
        val todos = RecebimentoPaletesTable.selectAll().where { RecebimentoPaletesTable.tenantId eq tenantId }.toList()
        paletesDto(tenantId, { it[RecebimentoPaletesTable.id] == paleteId }, todos).single()
    }

    private val ORIGENS_LOTE = setOf("GS1", "XML", "DIGITADO", "AUTOMATICO", "ANTERIOR")

    /** Avaria no palete (V82): quantidade avariada do produto (unidade padrão) + motivo. Zero apaga. */
    fun definirAvaria(tenantId: UUID, paleteId: UUID, req: AvariaPaleteRequest): PaleteDto = TenantTx.run(tenantId) {
        val itens = RecebimentoPaleteItensTable.selectAll()
            .where { (RecebimentoPaleteItensTable.tenantId eq tenantId) and (RecebimentoPaleteItensTable.paleteId eq paleteId) and (RecebimentoPaleteItensTable.codprod eq req.codprod) }
            .toList()
        val alvo = itens.firstOrNull() ?: throw PaleteException("O produto ${req.codprod} não está neste palete.")
        val total = itens.fold(BigDecimal.ZERO) { a, i -> a + i[RecebimentoPaleteItensTable.qtd] }
        val q = req.qtd.toBigDecimal()
        if (q.signum() < 0 || q > total) throw PaleteException("A avaria tem de ficar entre 0 e a quantidade do palete.")
        if (q.signum() > 0 && req.motivo.isNullOrBlank()) throw PaleteException("Informe o motivo da avaria.")
        for (i in itens) RecebimentoPaleteItensTable.update({ RecebimentoPaleteItensTable.id eq i[RecebimentoPaleteItensTable.id] }) {
            it[qtdAvaria] = if (i == alvo && q.signum() > 0) q else null
            it[motivoAvaria] = if (i == alvo && q.signum() > 0) req.motivo!!.trim().take(200) else null
        }
        val todos = RecebimentoPaletesTable.selectAll().where { RecebimentoPaletesTable.tenantId eq tenantId }.toList()
        paletesDto(tenantId, { it[RecebimentoPaletesTable.id] == paleteId }, todos).single()
    }

    /**
     * Amostragem de embalagem (V83): o conferente abre uma caixa do palete e conta as unidades. O esperado é o fator da
     * unidade de compra no cadastro (TGFVOA via item da conferência: 'M' = unidades por embalagem). Diferença = o
     * fornecedor mudou a embalagem e a conversão do estoque vai errar — vira alerta na doca.
     */
    fun registrarAmostra(tenantId: UUID, paleteId: UUID, req: AmostraRequest): PaleteDto = TenantTx.run(tenantId) {
        val p = RecebimentoPaletesTable.selectAll()
            .where { (RecebimentoPaletesTable.tenantId eq tenantId) and (RecebimentoPaletesTable.id eq paleteId) }.singleOrNull()
            ?: throw PaleteException("Palete não encontrado.", HttpStatusCode.NotFound)
        if (req.unidades <= 0) throw PaleteException("Informe quantas unidades havia na caixa.")
        val m = metas(tenantId, p[RecebimentoPaletesTable.sessaoId]).entries.firstOrNull { it.key.first == req.codprod }?.value
        val esperado = if (m?.fator != null && m.fator.signum() > 0 && m.dm?.trim() == "M") m.fator else null
        val n = RecebimentoPaleteItensTable.update({
            (RecebimentoPaleteItensTable.tenantId eq tenantId) and (RecebimentoPaleteItensTable.paleteId eq paleteId) and (RecebimentoPaleteItensTable.codprod eq req.codprod)
        }) {
            it[amostraUnidades] = req.unidades.toBigDecimal()
            it[amostraEsperado] = esperado
        }
        if (n == 0) throw PaleteException("O produto ${req.codprod} não está neste palete.")
        val todos = RecebimentoPaletesTable.selectAll().where { RecebimentoPaletesTable.tenantId eq tenantId }.toList()
        paletesDto(tenantId, { it[RecebimentoPaletesTable.id] == paleteId }, todos).single()
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
            try { call.respond(Paletes.resumo(claims.tenantId, sessaoId, claims.userId)) } catch (e: Exception) { call.paleteErro(e) }
        }
        post("/sessao/{sessaoId}/fechar") {
            val claims = call.exigirAuth() ?: return@post
            val sessaoId = runCatching { UUID.fromString(call.parameters["sessaoId"]) }.getOrNull()
                ?: return@post call.respond(HttpStatusCode.BadRequest, mapOf("erro" to "sessão inválida"))
            val body = runCatching { call.receive<FecharPaleteRequest>() }.getOrDefault(FecharPaleteRequest())
            try { call.respond(Paletes.fechar(claims.tenantId, sessaoId, body, nome(claims), claims.userId)) } catch (e: Exception) { call.paleteErro(e) }
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
        put("/{id}/lotes") {
            val claims = call.exigirAuth() ?: return@put
            val id = runCatching { UUID.fromString(call.parameters["id"]) }.getOrNull() ?: return@put call.respond(HttpStatusCode.BadRequest, mapOf("erro" to "palete inválido"))
            try { call.respond(Paletes.definirLotes(claims.tenantId, id, call.receive())) } catch (e: Exception) { call.paleteErro(e) }
        }
        put("/{id}/avaria") {
            val claims = call.exigirAuth() ?: return@put
            val id = runCatching { UUID.fromString(call.parameters["id"]) }.getOrNull() ?: return@put call.respond(HttpStatusCode.BadRequest, mapOf("erro" to "palete inválido"))
            try { call.respond(Paletes.definirAvaria(claims.tenantId, id, call.receive())) } catch (e: Exception) { call.paleteErro(e) }
        }
        put("/{id}/amostra") {
            val claims = call.exigirAuth() ?: return@put
            val id = runCatching { UUID.fromString(call.parameters["id"]) }.getOrNull() ?: return@put call.respond(HttpStatusCode.BadRequest, mapOf("erro" to "palete inválido"))
            try { call.respond(Paletes.registrarAmostra(claims.tenantId, id, call.receive())) } catch (e: Exception) { call.paleteErro(e) }
        }
        post("/impresso") {
            val claims = call.exigirAuth() ?: return@post
            val ids = call.receive<IdsRequest>().ids.mapNotNull { runCatching { UUID.fromString(it) }.getOrNull() }
            Paletes.marcarImpresso(claims.tenantId, ids)
            call.respond(HttpStatusCode.NoContent)
        }
    }
}
