package wms.backend.separacao

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.selectAll
import wms.backend.configconferencia.ConfigConferenciaRepository
import wms.backend.erp.SankhyaDbExplorerClient
import wms.backend.erp.SankhyaSpClient
import wms.backend.paletes.RecebimentoPaleteItensTable
import wms.backend.paletes.RecebimentoPaletesTable
import wms.backend.tarefas.TarefasRepository
import wms.backend.tenancy.TenantTx
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.UUID

/**
 * Lote da compra para o Sankhya (recebimento na doca, etapa D3 — 28/11/2026; desenho em sankhya-hub
 * docs/projetos/torre-recebimento-doca.md e torre-lote-validade.md §4).
 *
 * Vale só quando os três estão ligados: compra (TIPMOV C) · produto com lote (TGFPRO.TIPCONTEST 'L') · CCO com
 * "Conferência determina o lote" (EXPLODIRLOTE 'S'). Fora disso nada muda.
 * - Tempo 1: o conferente bipa sem lote (item da nota com lote genérico ' ') — ou escolhe entre os lotes que a
 *   importação do XML já criou na nota.
 * - Finalização: o lido sem lote é dividido pelos lotes dos paletes (Tempo 2), proporcional — o Sankhya divide o
 *   item da nota pelo EXPLODIRLOTE (regras-descobertas 1.14b). Falta de lote/palete bloqueia a finalização.
 * - Depois da aprovação: validade e fabricação de cada lote por `CACSP.salvarInformacoesLote` (pode ser depois de
 *   confirmar; vencido e fabricação futura são recusados pelo próprio Sankhya).
 */
object LoteCompra {

    class LoteFaltandoException(msg: String) : Exception(msg)

    data class LinhaLote(val lote: String, val qtd: BigDecimal, val validade: LocalDate?, val fabricacao: LocalDate?)

    private fun branco(s: String?) = s == null || s.isBlank()

    /** A CCO desta compra deixa a conferência determinar o lote? (cópia local da TGFCCO). */
    fun cooExplodeLote(tenantId: UUID, nunota: Long): Boolean = runCatching {
        val nucco = TarefasRepository.buscarNuccoLocal(tenantId, nunota) ?: return false
        ConfigConferenciaRepository.buscarPorNucco(tenantId, nucco)?.campos?.get("EXPLODIRLOTE")?.trim() == "S"
    }.getOrDefault(false)

    fun ehCompra(tenantId: UUID, nunota: Long): Boolean =
        runCatching { TarefasRepository.buscarTipMovLocal(tenantId, nunota)?.trim() == "C" }.getOrDefault(false)

    /** O item (JSON `dados` da sessão) é de produto com lote? Lê o JSON — o texto gravado tem espaços (bug de 28/11). */
    internal fun temLote(dados: String): Boolean = runCatching {
        (kotlinx.serialization.json.Json.parseToJsonElement(dados) as JsonObject)["Produto.TIPCONTEST"]?.jsonPrimitive?.contentOrNull?.trim() == "L"
    }.getOrDefault(false)

    /** Produtos da sessão com lote (TIPCONTEST 'L'). */
    fun produtosComLote(tenantId: UUID, sessaoId: UUID): Set<Int> = TenantTx.run(tenantId) {
        SeparacaoItensTable.selectAll()
            .where { (SeparacaoItensTable.tenantId eq tenantId) and (SeparacaoItensTable.sessaoId eq sessaoId) }
            .filter { temLote(it[SeparacaoItensTable.dados]) }
            .map { it[SeparacaoItensTable.codprod] }
            .toSet()
    }

    /** Linhas de lote dos paletes ativos (só do lido sem lote: controle ' '), por produto. null no lote = pendente. */
    fun lotesDosPaletes(tenantId: UUID, sessaoId: UUID): Map<Int, List<Pair<String?, LinhaLote>>> = TenantTx.run(tenantId) {
        val ativos = RecebimentoPaletesTable.selectAll()
            .where { (RecebimentoPaletesTable.tenantId eq tenantId) and (RecebimentoPaletesTable.sessaoId eq sessaoId) and (RecebimentoPaletesTable.canceladoEm.isNull()) }
            .map { it[RecebimentoPaletesTable.id] to it[RecebimentoPaletesTable.uma] }.toMap()
        if (ativos.isEmpty()) return@run emptyMap()
        RecebimentoPaleteItensTable.selectAll()
            .where { RecebimentoPaleteItensTable.tenantId eq tenantId }
            .filter { it[RecebimentoPaleteItensTable.paleteId] in ativos.keys && branco(it[RecebimentoPaleteItensTable.controle]) }
            .groupBy { it[RecebimentoPaleteItensTable.codprod] }
            .mapValues { (_, ls) ->
                ls.map { r ->
                    ativos[r[RecebimentoPaleteItensTable.paleteId]] to LinhaLote(
                        r[RecebimentoPaleteItensTable.lote]?.trim().orEmpty(),
                        r[RecebimentoPaleteItensTable.qtd],
                        r[RecebimentoPaleteItensTable.validade],
                        r[RecebimentoPaleteItensTable.fabricacao],
                    )
                }
            }
    }

    /** Lido sem lote (controle ' ') por produto, na unidade padrão. */
    fun lidoSemLote(tenantId: UUID, sessaoId: UUID): Map<Int, BigDecimal> = TenantTx.run(tenantId) {
        SeparacaoLeiturasTable.selectAll()
            .where { (SeparacaoLeiturasTable.tenantId eq tenantId) and (SeparacaoLeiturasTable.sessaoId eq sessaoId) }
            .filter { branco(it[SeparacaoLeiturasTable.controle]) }
            .groupBy { it[SeparacaoLeiturasTable.codprod] }
            .mapValues { (_, ls) -> ls.fold(BigDecimal.ZERO) { a, r -> a + r[SeparacaoLeiturasTable.qtd] } }
    }

    /**
     * Divide cada grupo sem lote de produto com lote pelos lotes dos paletes (proporcional à quantidade de cada lote;
     * a última parte leva o resto do arredondamento). Pesável: líquido e bruto divididos na mesma proporção.
     * Lança [LoteFaltandoException] se algum palete do produto está sem lote ou se sobrou lido fora de palete.
     */
    fun dividir(
        grupos: List<SeparacaoRepository.GrupoConferido>,
        comLote: Set<Int>,
        lotes: Map<Int, List<Pair<String?, LinhaLote>>>,
        lido: Map<Int, BigDecimal>,
    ): List<SeparacaoRepository.GrupoConferido> {
        val problemas = mutableListOf<String>()
        val r = grupos.flatMap { g ->
            if (g.codprod !in comLote || !branco(g.controle)) return@flatMap listOf(g)
            val ls = lotes[g.codprod].orEmpty()
            val semLote = ls.filter { it.second.lote.isEmpty() }.mapNotNull { it.first }.distinct()
            val noPalete = ls.fold(BigDecimal.ZERO) { a, l -> a + l.second.qtd }
            val lidoP = lido[g.codprod] ?: BigDecimal.ZERO
            when {
                semLote.isNotEmpty() -> { problemas += "produto ${g.codprod}: lote pendente no palete ${semLote.joinToString(", ")}"; return@flatMap listOf(g) }
                noPalete.signum() == 0 || noPalete < lidoP.setScale(5, RoundingMode.HALF_UP) - BigDecimal("0.00001") -> {
                    problemas += "produto ${g.codprod}: ${(lidoP - noPalete).max(BigDecimal.ZERO).stripTrailingZeros().toPlainString()} lido fora de palete"
                    return@flatMap listOf(g)
                }
            }
            // Soma por lote (o mesmo lote em vários paletes vira uma parte só).
            val porLote = ls.groupBy { it.second.lote }.mapValues { (_, v) -> v.fold(BigDecimal.ZERO) { a, l -> a + l.second.qtd } }
                .entries.sortedBy { it.key }
            if (porLote.size == 1) return@flatMap listOf(g.copy(controle = porLote[0].key, controleOrigem = g.controle))
            val tara = g.pesoLiquido?.let { ((g.pesoBruto ?: it) - it).max(BigDecimal.ZERO) }
            var restoQtd = g.qtdTotal
            var restoTara = tara
            porLote.mapIndexed { i, (lote, q) ->
                val ultima = i == porLote.lastIndex
                val share = q.divide(noPalete, 10, RoundingMode.HALF_UP)
                val qtd = if (ultima) restoQtd else (g.qtdTotal * share).setScale(5, RoundingMode.HALF_UP)
                val t = tara?.let { if (ultima) restoTara!! else (it * share).setScale(5, RoundingMode.HALF_UP) }
                restoQtd -= qtd
                if (t != null) restoTara = restoTara!! - t
                g.copy(
                    controle = lote, controleOrigem = g.controle, qtdTotal = qtd,
                    pesoLiquido = g.pesoLiquido?.let { qtd }, pesoBruto = t?.let { qtd + it },
                )
            }
        }
        if (problemas.isNotEmpty()) {
            throw LoteFaltandoException("Lote do palete pendente para o Sankhya — " + problemas.joinToString("; ") + ". Complete o Tempo 2 na doca antes de finalizar.")
        }
        return r
    }

    /** Ponto único chamado na finalização: só divide quando compra + CCO EXPLODIRLOTE; senão devolve os grupos. */
    fun aplicar(tenantId: UUID, sessaoId: UUID, nunota: Long, grupos: List<SeparacaoRepository.GrupoConferido>): List<SeparacaoRepository.GrupoConferido> {
        // 28/11/2026: EXPLODIRLOTE é proibido na conferência de entrada (COM_E00028) — o lote vai para a nota pelo addon
        // (dividirNaNota) antes do envio; basta compra + produto com lote.
        if (!ehCompra(tenantId, nunota)) return grupos
        val comLote = produtosComLote(tenantId, sessaoId)
        if (comLote.isEmpty() || grupos.none { it.codprod in comLote && branco(it.controle) }) return grupos
        val r = dividir(grupos, comLote, lotesDosPaletes(tenantId, sessaoId), lidoSemLote(tenantId, sessaoId))
        println("INFO: compra $nunota — lote dos paletes para o Sankhya: ${r.filter { it.controleOrigem != null }.joinToString { "${it.codprod}/${it.controle}=${it.qtdTotal.toPlainString()}" }}")
        return r
    }

    // ─── Opção A: divide o item da nota pelos lotes (addon) ─────────────────────────────────────────────

    /** Corpo do RecebimentoSP.dividirLotes: lote e quantidade (unidade padrão) por produto, dos paletes. */
    internal fun corpoDividir(nunota: Long, lotes: Map<Int, List<Pair<String?, LinhaLote>>>, produtos: Set<Int>): JsonObject = buildJsonObject {
        putJsonObject("request") {
            put("NUNOTA", nunota.toString())
            put("LOTES", buildJsonArray {
                lotes.filterKeys { it in produtos }.toSortedMap().forEach { (cod, ls) ->
                    ls.groupBy { it.second.lote }.toSortedMap().forEach { (lote, v) ->
                        add(buildJsonObject {
                            put("CODPROD", cod.toString())
                            put("LOTE", lote)
                            put("QTD", v.fold(BigDecimal.ZERO) { a, l -> a + l.second.qtd }.toPlainString())
                        })
                    }
                }
            })
        }
    }

    /** Pede ao addon a divisão do item da nota pelos lotes dos produtos que foram divididos para o envio. */
    suspend fun dividirNaNota(tenantSlug: String, tenantId: UUID, sessaoId: UUID, nunota: Long, produtos: Set<Int>) {
        if (produtos.isEmpty()) return
        val corpo = corpoDividir(nunota, lotesDosPaletes(tenantId, sessaoId), produtos)
        val r = SankhyaSpClient.chamarRaw(tenantSlug, "RecebimentoSP.dividirLotes", "torre", corpo, retentar = false)
        println("INFO: compra $nunota — itens da nota divididos por lote: ${r.toString().take(400)}")
        wms.backend.recebimentodoca.RecebimentoFiscal.marcarLotesDivididos(tenantId, nunota.toInt())
    }

    // ─── Depois da aprovação: validade e fabricação ─────────────────────────────────────────────────

    private val BR: DateTimeFormatter = DateTimeFormatter.ofPattern("dd/MM/yyyy")

    /** Corpo do CACSP.salvarInformacoesLote (lista ITENSLOTE/INFOLOTE; CAMPOS é obrigatório no serviço). */
    internal fun corpoValidades(itens: List<Triple<Int, LocalDate?, LocalDate?>>, nunota: Long): JsonObject = buildJsonObject {
        putJsonObject("ITENSLOTE") {
            put("INFOLOTE", buildJsonArray {
                itens.forEach { (seq, val_, fab) ->
                    add(buildJsonObject {
                        put("NUNOTA", nunota.toString())
                        put("SEQUENCIA", seq.toString())
                        val_?.let { put("DTVALID", BR.format(it)) }
                        fab?.let { put("DTFABRICACAO", BR.format(it)) }
                        putJsonObject("CAMPOS") {}
                    })
                }
            })
        }
    }

    /**
     * Grava validade/fabricação de cada lote da compra confirmada (itens da nota com CONTROLE = lote do palete).
     * Falha não desfaz nada: registra e devolve a mensagem.
     */
    suspend fun gravarValidades(tenantSlug: String, tenantId: UUID, nunota: Long): String? {
        val sessao = SeparacaoRepository.buscarSessaoMaisRecentePorNota(tenantId, nunota) ?: return null
        val sid = UUID.fromString(sessao.id)
        val porChave = TenantTx.run(tenantId) {
            val ativos = RecebimentoPaletesTable.selectAll()
                .where { (RecebimentoPaletesTable.tenantId eq tenantId) and (RecebimentoPaletesTable.sessaoId eq sid) and (RecebimentoPaletesTable.canceladoEm.isNull()) }
                .map { it[RecebimentoPaletesTable.id] }.toSet()
            RecebimentoPaleteItensTable.selectAll().where { RecebimentoPaleteItensTable.tenantId eq tenantId }
                .filter { it[RecebimentoPaleteItensTable.paleteId] in ativos && !branco(it[RecebimentoPaleteItensTable.lote]) }
                .filter { it[RecebimentoPaleteItensTable.validade] != null || it[RecebimentoPaleteItensTable.fabricacao] != null }
                .associate { (it[RecebimentoPaleteItensTable.codprod] to it[RecebimentoPaleteItensTable.lote]!!.trim()) to (it[RecebimentoPaleteItensTable.validade] to it[RecebimentoPaleteItensTable.fabricacao]) }
        }
        if (porChave.isEmpty()) return null
        val itens = SankhyaDbExplorerClient.executarQuery(tenantSlug,
            "SELECT SEQUENCIA, CODPROD, CONTROLE FROM TGFITE WHERE NUNOTA = $nunota AND SEQUENCIA > 0")
            .mapNotNull { r ->
                val seq = r["SEQUENCIA"]?.trim()?.substringBefore('.')?.toIntOrNull() ?: return@mapNotNull null
                val cod = r["CODPROD"]?.trim()?.substringBefore('.')?.toIntOrNull() ?: return@mapNotNull null
                val datas = porChave[cod to r["CONTROLE"]?.trim().orEmpty()] ?: return@mapNotNull null
                Triple(seq, datas.first, datas.second)
            }
        if (itens.isEmpty()) return "nenhum item da nota com o lote dos paletes"
        return runCatching {
            SankhyaSpClient.chamarRaw(tenantSlug, "CACSP.salvarInformacoesLote", "mgecom", corpoValidades(itens, nunota), retentar = false)
            println("INFO: compra $nunota — validade/fabricação gravadas em ${itens.size} item(ns) de lote")
            null
        }.getOrElse {
            println("AVISO: compra $nunota — validade dos lotes não gravada: ${it.message}")
            it.message ?: "falha ao gravar a validade"
        }
    }
}
