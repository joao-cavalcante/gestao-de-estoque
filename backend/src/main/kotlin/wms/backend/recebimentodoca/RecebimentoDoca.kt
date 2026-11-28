package wms.backend.recebimentodoca

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.Serializable
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.javatime.timestamp
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.update
import wms.backend.auth.ClaimsToken
import wms.backend.auth.exigirAuth
import wms.backend.erp.SankhyaDbExplorerClient
import wms.backend.paletes.PaleteDto
import wms.backend.paletes.Paletes
import wms.backend.tenancy.TenantRepository
import wms.backend.tenancy.TenantTx
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Recebimento na doca — etapa D1 (27/11/2026). Desenho: sankhya-hub docs/projetos/torre-recebimento-doca.md.
 * Chegada do caminhão (+ checklist OPCIONAL), o que a NF-e do fornecedor diz (volumes, peso bruto e lotes do grupo
 * `rastro`, lidos do XML importado no Sankhya — TGFIXN) e os paletes da compra (Tempo 2 = lote/validade).
 * Só leitura no Sankhya nesta etapa.
 */
object RecebimentoChegadasTable : Table("app.recebimento_chegadas") {
    val tenantId = uuid("tenant_id")
    val nunota = integer("nunota")
    val chegadaEm = timestamp("chegada_em")
    val placa = text("placa").nullable()
    val lacre = text("lacre").nullable()
    val bau = text("bau").nullable()
    val temperaturaBau = decimal("temperatura_bau", 6, 2).nullable()
    val observacao = text("observacao").nullable()
    val registradoPor = text("registrado_por").nullable()
    val liberadoEm = timestamp("liberado_em").nullable()
    val liberadoPor = text("liberado_por").nullable()
    override val primaryKey = PrimaryKey(tenantId, nunota)
}

@Serializable
data class ChegadaDto(
    val chegadaEm: String,
    val placa: String?,
    val lacre: String?,
    val bau: String?,
    val temperaturaBau: Double?,
    val observacao: String?,
    val registradoPor: String?,
    val liberadoEm: String?,
    val liberadoPor: String?,
)

@Serializable
data class ChegadaRequest(
    val placa: String? = null,
    val lacre: String? = null,
    val bau: String? = null,
    val temperaturaBau: Double? = null,
    val observacao: String? = null,
)

@Serializable
data class LoteXmlDto(val lote: String, val qtd: Double?, val fabricacao: String?, val validade: String?)

@Serializable
data class ItemXmlDto(val nItem: Int, val codprod: Int?, val cProd: String?, val descricao: String?, val lotes: List<LoteXmlDto>)

@Serializable
data class NotaXmlDto(
    val encontrado: Boolean,
    val volumes: Double? = null,
    val especie: String? = null,
    val pesoBruto: Double? = null,
    val pesoLiquido: Double? = null,
    val placa: String? = null,
    val itens: List<ItemXmlDto> = emptyList(),
)

@Serializable
data class DocaDto(
    val nunota: Int,
    val chegada: ChegadaDto?,
    val nota: NotaXmlDto,
    val paletes: List<PaleteDto>,
    /** Soma das caixas/volumes dos paletes fechados (quantidade comercial; sem ela, a padrão). */
    val volumesFechados: Double,
    val paletesLotePendente: Int,
)

class DocaException(msg: String, val status: HttpStatusCode = HttpStatusCode.BadRequest) : Exception(msg)

object RecebimentoDoca {

    // ─── XML da NF-e (TGFIXN) ───────────────────────────────────────────────────────────────────────────

    private data class Cache(val dto: NotaXmlDto, val em: Long)
    private val cache = ConcurrentHashMap<String, Cache>()
    private const val TTL_MS = 10 * 60_000L

    private fun tag(t: String, nome: String): String? =
        Regex("<$nome>([^<]*)</$nome>").find(t)?.groupValues?.get(1)?.trim()?.takeIf { it.isNotEmpty() }

    private fun num(v: String?) = v?.replace(',', '.')?.toDoubleOrNull()

    /** Trecho de um `<det>`: item da nota (cProd, xProd) e os lotes do grupo `rastro`. Testável sem Sankhya. */
    internal fun lerDet(nItem: Int, t: String): ItemXmlDto {
        val prod = t.substringBefore("</prod>")
        val lotes = Regex("<rastro>(.*?)</rastro>").findAll(prod).map { m ->
            val r = m.groupValues[1]
            LoteXmlDto(tag(r, "nLote") ?: "", num(tag(r, "qLote")), tag(r, "dFab"), tag(r, "dVal"))
        }.filter { it.lote.isNotEmpty() }.toList()
        return ItemXmlDto(nItem, null, tag(prod, "cProd"), tag(prod, "xProd"), lotes)
    }

    /** Trecho do `<transp>`: volumes (somados), espécie, pesos e placa. */
    internal fun lerTransp(t: String): NotaXmlDto {
        val vols = Regex("<vol>(.*?)</vol>").findAll(t).map { it.groupValues[1] }.toList()
        fun soma(nome: String) = vols.mapNotNull { num(tag(it, nome)) }.takeIf { it.isNotEmpty() }?.sum()
        return NotaXmlDto(
            encontrado = true,
            volumes = soma("qVol"),
            especie = vols.firstNotNullOfOrNull { tag(it, "esp") },
            pesoBruto = soma("pesoB"),
            pesoLiquido = soma("pesoL"),
            placa = tag(t, "placa"),
        )
    }

    suspend fun notaXml(slug: String, nunota: Int): NotaXmlDto {
        val chave = "$slug|$nunota"
        cache[chave]?.takeIf { System.currentTimeMillis() - it.em < TTL_MS }?.let { return it.dto }
        val arq = SankhyaDbExplorerClient.executarQuery(slug,
            "SELECT * FROM (SELECT NUARQUIVO, CODPARC, REGEXP_COUNT(XML, '<det ') DETS FROM TGFIXN WHERE NUNOTA = $nunota AND TIPO = 'N' " +
                "ORDER BY NUARQUIVO DESC) WHERE ROWNUM = 1").firstOrNull()
            ?: return NotaXmlDto(false).also { cache[chave] = Cache(it, System.currentTimeMillis()) }
        val nu = arq["NUARQUIVO"]?.trim()?.substringBefore('.') ?: return NotaXmlDto(false)
        val codparc = arq["CODPARC"]?.trim()?.substringBefore('.')
        val dets = arq["DETS"]?.trim()?.substringBefore('.')?.toIntOrNull() ?: 0
        val transp = SankhyaDbExplorerClient.executarQuery(slug,
            "SELECT DBMS_LOB.SUBSTR(XML, 1800, GREATEST(1, DBMS_LOB.INSTR(XML, '<transp>'))) T FROM TGFIXN WHERE NUARQUIVO = $nu")
            .firstOrNull()?.get("T").orEmpty()
        val base = if (transp.contains("<transp>")) lerTransp(transp) else NotaXmlDto(true)
        val itens = if (dets == 0) emptyList() else SankhyaDbExplorerClient.executarQuery(slug,
            // Gerador de linhas em DUAL (CONNECT BY direto na TGFIXN não responde pelo DbExplorer — testado na sandbox).
            "SELECT G.N, DBMS_LOB.SUBSTR(X.XML, 1800, DBMS_LOB.INSTR(X.XML, '<det ', 1, G.N)) T FROM TGFIXN X, " +
                "(SELECT LEVEL N FROM DUAL CONNECT BY LEVEL <= ${dets.coerceAtMost(300)}) G WHERE X.NUARQUIVO = $nu ORDER BY G.N")
            .mapNotNull { r -> r["N"]?.trim()?.substringBefore('.')?.toIntOrNull()?.let { n -> lerDet(n, r["T"].orEmpty()) } }
        // Código do fornecedor → produto do Sankhya (TGFPAP); sem cadastro, pela sequência da nota.
        val porCodigo = if (codparc == null || itens.isEmpty()) emptyMap() else runCatching {
            val cods = itens.mapNotNull { it.cProd }.distinct().joinToString(",") { "'" + it.replace("'", "''") + "'" }
            if (cods.isEmpty()) emptyMap() else SankhyaDbExplorerClient.executarQuery(slug,
                "SELECT CODPROPARC, MIN(CODPROD) CODPROD FROM TGFPAP WHERE CODPARC = $codparc AND CODPROPARC IN ($cods) GROUP BY CODPROPARC")
                .mapNotNull { r -> r["CODPROPARC"]?.trim()?.let { k -> k to r["CODPROD"]?.trim()?.substringBefore('.')?.toIntOrNull() } }
                .toMap()
        }.getOrDefault(emptyMap())
        val porSeq = runCatching {
            SankhyaDbExplorerClient.executarQuery(slug, "SELECT SEQUENCIA, CODPROD FROM TGFITE WHERE NUNOTA = $nunota AND SEQUENCIA > 0")
                .mapNotNull { r -> r["SEQUENCIA"]?.trim()?.substringBefore('.')?.toIntOrNull()?.let { s -> s to r["CODPROD"]?.trim()?.substringBefore('.')?.toIntOrNull() } }
                .toMap()
        }.getOrDefault(emptyMap())
        val dto = base.copy(itens = itens.map { it.copy(codprod = porCodigo[it.cProd] ?: porSeq[it.nItem]) })
        cache[chave] = Cache(dto, System.currentTimeMillis())
        return dto
    }

    // ─── Chegada ───────────────────────────────────────────────────────────────────────────────────────

    private fun chegadaDto(r: org.jetbrains.exposed.sql.ResultRow) = ChegadaDto(
        r[RecebimentoChegadasTable.chegadaEm].toString(), r[RecebimentoChegadasTable.placa], r[RecebimentoChegadasTable.lacre],
        r[RecebimentoChegadasTable.bau], r[RecebimentoChegadasTable.temperaturaBau]?.toDouble(), r[RecebimentoChegadasTable.observacao],
        r[RecebimentoChegadasTable.registradoPor], r[RecebimentoChegadasTable.liberadoEm]?.toString(), r[RecebimentoChegadasTable.liberadoPor],
    )

    fun chegada(tenantId: UUID, nunota: Int): ChegadaDto? = TenantTx.run(tenantId) {
        RecebimentoChegadasTable.selectAll()
            .where { (RecebimentoChegadasTable.tenantId eq tenantId) and (RecebimentoChegadasTable.nunota eq nunota) }
            .singleOrNull()?.let(::chegadaDto)
    }

    private val LACRES = setOf("OK", "VIOLADO", "SEM_LACRE")
    private val BAUS = setOf("OK", "SUJO", "AVARIADO")

    /** Registra (ou corrige) a chegada; o checklist é opcional — campo vazio = não verificado. */
    fun registrarChegada(tenantId: UUID, nunota: Int, req: ChegadaRequest, nome: String): ChegadaDto = TenantTx.run(tenantId) {
        req.lacre?.let { if (it !in LACRES) throw DocaException("Lacre inválido: $it") }
        req.bau?.let { if (it !in BAUS) throw DocaException("Baú inválido: $it") }
        req.temperaturaBau?.let { if (it < -40 || it > 40) throw DocaException("Temperatura do baú fora do razoável: $it °C") }
        val existe = RecebimentoChegadasTable.selectAll()
            .where { (RecebimentoChegadasTable.tenantId eq tenantId) and (RecebimentoChegadasTable.nunota eq nunota) }.singleOrNull()
        if (existe == null) {
            RecebimentoChegadasTable.insert {
                it[RecebimentoChegadasTable.tenantId] = tenantId
                it[RecebimentoChegadasTable.nunota] = nunota
                it[chegadaEm] = Instant.now()
                it[placa] = req.placa?.trim()?.uppercase()?.takeIf { p -> p.isNotEmpty() }
                it[lacre] = req.lacre
                it[bau] = req.bau
                it[temperaturaBau] = req.temperaturaBau?.toBigDecimal()
                it[observacao] = req.observacao?.trim()?.takeIf { o -> o.isNotEmpty() }
                it[registradoPor] = nome
            }
        } else {
            RecebimentoChegadasTable.update({ (RecebimentoChegadasTable.tenantId eq tenantId) and (RecebimentoChegadasTable.nunota eq nunota) }) {
                it[placa] = req.placa?.trim()?.uppercase()?.takeIf { p -> p.isNotEmpty() }
                it[lacre] = req.lacre
                it[bau] = req.bau
                it[temperaturaBau] = req.temperaturaBau?.toBigDecimal()
                it[observacao] = req.observacao?.trim()?.takeIf { o -> o.isNotEmpty() }
            }
        }
        RecebimentoChegadasTable.selectAll()
            .where { (RecebimentoChegadasTable.tenantId eq tenantId) and (RecebimentoChegadasTable.nunota eq nunota) }.single().let(::chegadaDto)
    }

    /** Motorista liberado (comprovante entregue). Exige a chegada registrada. */
    fun liberarMotorista(tenantId: UUID, nunota: Int, nome: String): ChegadaDto = TenantTx.run(tenantId) {
        val n = RecebimentoChegadasTable.update({ (RecebimentoChegadasTable.tenantId eq tenantId) and (RecebimentoChegadasTable.nunota eq nunota) }) {
            it[liberadoEm] = Instant.now()
            it[liberadoPor] = nome
        }
        if (n == 0) throw DocaException("Registre a chegada do caminhão antes de liberar o motorista.")
        RecebimentoChegadasTable.selectAll()
            .where { (RecebimentoChegadasTable.tenantId eq tenantId) and (RecebimentoChegadasTable.nunota eq nunota) }.single().let(::chegadaDto)
    }

    // ─── Doca (tudo junto) ─────────────────────────────────────────────────────────────────────────────

    suspend fun doca(claims: ClaimsToken, nunota: Int): DocaDto {
        val slug = TenantRepository.buscarPorId(claims.tenantId)?.slug ?: throw DocaException("Cliente não encontrado.", HttpStatusCode.NotFound)
        val nota = runCatching { notaXml(slug, nunota) }.getOrElse {
            println("AVISO: XML da nota $nunota não lido: ${it.message}")
            NotaXmlDto(false)
        }
        val paletes = Paletes.porNunota(claims.tenantId, nunota)
        val volumes = paletes.sumOf { p -> p.itens.sumOf { i -> i.qtdComercial ?: i.qtd } }
        val pendentes = paletes.count { p -> p.itens.any { it.lote == null } }
        return DocaDto(nunota, chegada(claims.tenantId, nunota), nota, paletes, volumes, pendentes)
    }
}

private fun nome(claims: ClaimsToken): String = runCatching { wms.backend.galpao.nomeUsuario(claims) }.getOrDefault("Usuário")

private suspend fun ApplicationCall.docaErro(e: Exception) = when (e) {
    is DocaException -> respond(e.status, mapOf("erro" to e.message))
    else -> respond(HttpStatusCode.BadGateway, mapOf("erro" to (e.message ?: "falha no recebimento")))
}

fun Route.recebimentoDocaRoutes() {
    route("/api/recebimento/doca/{nunota}") {
        get {
            val claims = call.exigirAuth() ?: return@get
            val nunota = call.parameters["nunota"]?.toIntOrNull() ?: return@get call.respond(HttpStatusCode.BadRequest, mapOf("erro" to "nº único inválido"))
            try { call.respond(RecebimentoDoca.doca(claims, nunota)) } catch (e: Exception) { call.docaErro(e) }
        }
        put("/chegada") {
            val claims = call.exigirAuth() ?: return@put
            val nunota = call.parameters["nunota"]?.toIntOrNull() ?: return@put call.respond(HttpStatusCode.BadRequest, mapOf("erro" to "nº único inválido"))
            try { call.respond(RecebimentoDoca.registrarChegada(claims.tenantId, nunota, call.receive(), nome(claims))) } catch (e: Exception) { call.docaErro(e) }
        }
        post("/liberar-motorista") {
            val claims = call.exigirAuth() ?: return@post
            val nunota = call.parameters["nunota"]?.toIntOrNull() ?: return@post call.respond(HttpStatusCode.BadRequest, mapOf("erro" to "nº único inválido"))
            try { call.respond(RecebimentoDoca.liberarMotorista(claims.tenantId, nunota, nome(claims))) } catch (e: Exception) { call.docaErro(e) }
        }
    }
}
