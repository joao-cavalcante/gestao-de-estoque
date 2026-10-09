package wms.backend.liberacaocorte

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.insertIgnore
import org.jetbrains.exposed.sql.javatime.timestamp
import org.jetbrains.exposed.sql.selectAll
import wms.backend.erp.SankhyaDbExplorerClient
import wms.backend.tenancy.TenantTx
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID

/** app.separacao_corte_vinculos (V55) — SEQUENCIA da liberação de corte -> produto. */
object SeparacaoCorteVinculosTable : Table("app.separacao_corte_vinculos") {
    val tenantId = uuid("tenant_id")
    val nuconf = integer("nuconf")
    val sequencia = integer("sequencia")
    val nunota = integer("nunota")
    val codprod = integer("codprod")
    val controle = text("controle")
    val calculadoEm = timestamp("calculado_em")

    override val primaryKey = PrimaryKey(tenantId, nuconf, sequencia)
}

/**
 * Vínculo liberação de corte (TSILIB ev.64 TABELA 'TGFCOI2') -> produto+controle.
 *
 * O Sankhya NÃO guarda esse vínculo: a linha da TSILIB só tem NUCHAVE (NUCONF) +
 * SEQUENCIA, e o produto aparece apenas no texto da OBSERVACAO. Quem cria a
 * liberação é o ConferenciaHelper.ajustarDemanda (dentro do ConferenciaSP.cortar,
 * mgecom-model 5.17.3 — confirmado no Monitoramento do Sankhya da Negri), e quando
 * precisa achar o produto de novo (reprocessar após liberar) ele RECALCULA a
 * SEQUENCIA com a mesma conta. A conta (engenharia reversa + validada contra as
 * 454 liberações gravadas em 02/10/2026, 449/450 batendo):
 *
 *  1. Query de divergência agrupada por CODPROD+CONTROLE+CODVOL: a parte do pedido
 *     (TGFITE) vem com MINSEQ 0, a da conferência (TGFCOI2) com MINSEQ = MIN(SEQCONF);
 *     mesma unidade nas duas -> uma linha só (MINSEQ = SEQCONF).
 *  2. ORDER BY MINSEQ DESC, CODPROD; o helper junta por CODPROD+CONTROLE (ordem da
 *     1ª aparição) somando as quantidades e ficando com o MINSEQ da ÚLTIMA linha.
 *  3. Só os divergentes (pedido != conferido), com item na nota, e não ignorados
 *     pela CCO (GERARPEDCOMPL/PROCEDCORTE = 'N' -> "não ajustar", sem liberação):
 *     MINSEQ 0 -> SEQUENCIA = ++contador; MINSEQ > contador -> contador = MINSEQ;
 *     SEQUENCIA = MINSEQ.
 *
 * Na prática: pedido e conferência na mesma unidade -> SEQUENCIA = SEQCONF;
 * unidades diferentes (pesável: pedido em PC/CX, conferido em KG) -> contador.
 *
 * A conta só vale com os dados do momento em que o Sankhya numerou — depois o
 * corte muda QTDNEG/remove item da nota. Por isso o WMS calcula logo depois do
 * `cortar` (e de cada liberarNegarLimites, que pode reprocessar e criar SEQUENCIA
 * nova) e GRAVA. Liberação que já nasce decidida (recontagem: o Sankhya herda a
 * decisão da conferência anterior pela SEQUENCIA e já aplica o corte dela) entra
 * como divergente forçada, com o produto do vínculo anterior.
 *
 * Nada é gravado sem passar por duas checagens: o conjunto de SEQUENCIAs previsto
 * tem que ser IGUAL ao gravado na TSILIB, e o produto previsto tem que bater com o
 * "Prod.:" da OBSERVACAO. Falhou qualquer uma -> não grava (quem lê cai na
 * descrição) e loga AVISO.
 */
object VinculoCorte {

    data class Chave(val codprod: Int, val controle: String)

    /** Linha da query de divergência: 'P' pedido (TGFITE), 'C' conferido (TGFCOI2), 'N' item existe na nota. */
    data class LinhaDetalhe(
        val origem: Char,
        val codprod: Int,
        val controle: String,
        val codvol: String?,
        val qtd: BigDecimal,
        val minseq: Int,
    )

    fun normControle(controle: String?): String = controle?.trim()?.takeIf { it.isNotEmpty() } ?: " "

    /**
     * Texto do produto como o Sankhya escreve na OBSERVACAO
     * (ConferenciaHelper.getDescricaoLiberacaoProduto): "DESCR[, Complem.: X][, Controle: Y]".
     */
    fun textoProduto(descricao: String?, complemento: String?, controle: String?): String? {
        val desc = descricao?.trim()?.takeIf(String::isNotEmpty) ?: return null
        val sb = StringBuilder(desc)
        complemento?.trim()?.takeIf(String::isNotEmpty)?.let { sb.append(", Complem.: ").append(it) }
        controle?.trim()?.takeIf(String::isNotEmpty)?.let { sb.append(", Controle: ").append(it) }
        return sb.toString().uppercase()
    }

    /**
     * Regra pura do ajustarDemanda. Devolve SEQUENCIA -> chaves (mais de uma = colisão,
     * o próprio Sankhya trataria como a mesma liberação).
     *
     * [forcarDivergentes]: produtos que o Sankhya viu divergentes mas que, no dado de
     * agora, podem não estar mais (liberação herdada já cortada na mesma rodada) —
     * entram como divergentes e, se sumiram da nota, com uma linha de pedido MINSEQ 0.
     */
    fun preverSequencias(
        linhas: List<LinhaDetalhe>,
        forcarDivergentes: Set<Chave> = emptySet(),
        ignorarAMaior: Boolean = false,
        ignorarAMenor: Boolean = false,
    ): Map<Int, List<Chave>> {
        data class Grupo(var pedido: BigDecimal = BigDecimal.ZERO, var conferido: BigDecimal = BigDecimal.ZERO, var minseq: Int = 0)

        val naNota = linhas.filter { it.origem == 'N' }.map { Chave(it.codprod, normControle(it.controle)) }.toSet()
        val grupos = LinkedHashMap<Triple<Int, String, String?>, Grupo>()
        for (l in linhas) {
            if (l.origem == 'N') continue
            val g = grupos.getOrPut(Triple(l.codprod, normControle(l.controle), l.codvol)) { Grupo() }
            if (l.origem == 'P') g.pedido += l.qtd else { g.conferido += l.qtd; g.minseq += l.minseq }
        }
        for (k in forcarDivergentes) {
            if (grupos.keys.none { it.first == k.codprod && it.second == k.controle }) {
                grupos[Triple(k.codprod, k.controle, null)] = Grupo()
            }
        }

        val porProduto = LinkedHashMap<Chave, Grupo>()
        grupos.entries
            .sortedWith(compareByDescending<Map.Entry<Triple<Int, String, String?>, Grupo>> { it.value.minseq }.thenBy { it.key.first })
            .forEach { (k, g) ->
                val acc = porProduto.getOrPut(Chave(k.first, k.second)) { Grupo() }
                acc.pedido += g.pedido
                acc.conferido += g.conferido
                acc.minseq = g.minseq // fica o da ÚLTIMA linha (igual ao helper do Sankhya)
            }

        var aux = 0
        val previsto = LinkedHashMap<Int, MutableList<Chave>>()
        for ((chave, g) in porProduto) {
            val forcado = chave in forcarDivergentes
            if (!forcado) {
                val cmp = g.pedido.compareTo(g.conferido)
                if (cmp == 0) continue
                if (cmp < 0 && ignorarAMaior) continue
                if (cmp > 0 && ignorarAMenor) continue
                if (chave !in naNota) continue // sem item na nota o Sankhya não pede liberação
            }
            var seq = g.minseq
            if (seq == 0) {
                aux += 1
                seq = aux
            } else if (seq > aux) {
                aux = seq
            }
            previsto.getOrPut(seq) { mutableListOf() }.add(chave)
        }
        return previsto
    }

    // ─── persistência ────────────────────────────────────────────────────

    fun buscar(tenantId: UUID, nuconf: Int): Map<Int, Chave> = TenantTx.run(tenantId) {
        SeparacaoCorteVinculosTable.selectAll()
            .where { (SeparacaoCorteVinculosTable.tenantId eq tenantId) and (SeparacaoCorteVinculosTable.nuconf eq nuconf) }
            .associate { it[SeparacaoCorteVinculosTable.sequencia] to Chave(it[SeparacaoCorteVinculosTable.codprod], it[SeparacaoCorteVinculosTable.controle]) }
    }

    /** Vínculo da mesma SEQUENCIA na conferência anterior mais recente da nota (decisão herdada na recontagem). */
    private fun buscarAnterior(tenantId: UUID, nunota: Long, nuconf: Int, sequencia: Int): Chave? = TenantTx.run(tenantId) {
        SeparacaoCorteVinculosTable.selectAll()
            .where {
                (SeparacaoCorteVinculosTable.tenantId eq tenantId) and
                    (SeparacaoCorteVinculosTable.nunota eq nunota.toInt()) and
                    (SeparacaoCorteVinculosTable.nuconf less nuconf) and
                    (SeparacaoCorteVinculosTable.sequencia eq sequencia)
            }
            .orderBy(SeparacaoCorteVinculosTable.nuconf to SortOrder.DESC)
            .firstOrNull()
            ?.let { Chave(it[SeparacaoCorteVinculosTable.codprod], it[SeparacaoCorteVinculosTable.controle]) }
    }

    private fun gravar(tenantId: UUID, nunota: Long, nuconf: Int, vinculos: Map<Int, Chave>) = TenantTx.run(tenantId) {
        val agora = Instant.now()
        vinculos.forEach { (seq, chave) ->
            SeparacaoCorteVinculosTable.insertIgnore {
                it[SeparacaoCorteVinculosTable.tenantId] = tenantId
                it[SeparacaoCorteVinculosTable.nuconf] = nuconf
                it[SeparacaoCorteVinculosTable.sequencia] = seq
                it[SeparacaoCorteVinculosTable.nunota] = nunota.toInt()
                it[SeparacaoCorteVinculosTable.codprod] = chave.codprod
                it[SeparacaoCorteVinculosTable.controle] = chave.controle
                it[calculadoEm] = agora
            }
        }
    }

    // ─── cálculo ─────────────────────────────────────────────────────────

    private data class LinhaLiberacao(val sequencia: Int, val observacao: String?, val decidida: Boolean)

    /** nuconf -> instante da última tentativa que não fechou (evita repetir a cada refresh da tela). */
    private val tentativasFalhas = java.util.concurrent.ConcurrentHashMap<Int, Long>()
    private const val ESPERA_APOS_FALHA_MS = 5 * 60_000L

    /**
     * Calcula e grava o vínculo das SEQUENCIAs desta conferência que ainda não têm.
     * Seguro chamar várias vezes (só acrescenta). [origem] só vai pro log.
     * Nunca lança: falha = log + quem lê cai na descrição.
     */
    suspend fun calcular(tenantSlug: String, tenantId: UUID, nunota: Long, nuconf: Int, origem: String, forcar: Boolean = true) {
        if (!forcar) {
            val ultima = tentativasFalhas[nuconf]
            if (ultima != null && System.currentTimeMillis() - ultima < ESPERA_APOS_FALHA_MS) return
        }
        try {
            val ok = calcularInterno(tenantSlug, tenantId, nunota, nuconf, origem)
            if (ok) tentativasFalhas.remove(nuconf) else tentativasFalhas[nuconf] = System.currentTimeMillis()
        } catch (e: Exception) {
            tentativasFalhas[nuconf] = System.currentTimeMillis()
            println("AVISO: vínculo de corte nuconf $nuconf ($origem) falhou: ${e.message}")
        }
    }

    private suspend fun calcularInterno(tenantSlug: String, tenantId: UUID, nunota: Long, nuconf: Int, origem: String): Boolean {
        val liberacoes = SankhyaDbExplorerClient.executarQuery(
            tenantSlug,
            "SELECT L.SEQUENCIA, L.OBSERVACAO, CASE WHEN L.DHLIB IS NULL THEN 'N' ELSE 'S' END AS DECIDIDA " +
                "FROM TSILIB L WHERE L.NUCHAVE = $nuconf AND L.TABELA = 'TGFCOI2' AND L.EVENTO = 64",
        ).mapNotNull { r ->
            r["SEQUENCIA"]?.toBigDecimalOrNull()?.toInt()?.let { LinhaLiberacao(it, r["OBSERVACAO"], r["DECIDIDA"] == "S") }
        }
        if (liberacoes.isEmpty()) return true

        val existentes = withContext(Dispatchers.IO) { buscar(tenantId, nuconf) }
        val novas = liberacoes.filter { it.sequencia !in existentes }
        if (novas.isEmpty()) return true
        val primeiraVez = existentes.isEmpty()

        val linhas = SankhyaDbExplorerClient.executarQuery(tenantSlug, sqlDetalhe(nunota, nuconf)).mapNotNull { r ->
            val cp = r["CODPROD"]?.toBigDecimalOrNull()?.toInt() ?: return@mapNotNull null
            LinhaDetalhe(
                origem = r["ORIGEM"]?.firstOrNull() ?: return@mapNotNull null,
                codprod = cp,
                controle = normControle(r["CONTROLE"]),
                codvol = r["CODVOL"]?.trim(),
                qtd = r["QTD"]?.toBigDecimalOrNull() ?: BigDecimal.ZERO,
                minseq = r["MINSEQ"]?.toBigDecimalOrNull()?.toInt() ?: 0,
            )
        }

        val codprodsConferencia = linhas.map { it.codprod }.toSet()
        val descricoes = descricoesProdutos(tenantSlug, codprodsConferencia + existentes.values.map { it.codprod })

        // Divergentes forçados: os que já têm vínculo + os que nasceram decididos (herdados da
        // conferência anterior — o Sankhya já aplicou o corte deles nesta mesma rodada).
        val forcados = existentes.values.toMutableSet()
        for (lib in novas.filter { it.decidida }) {
            val anterior = withContext(Dispatchers.IO) { buscarAnterior(tenantId, nunota, nuconf, lib.sequencia) }
            val chave = anterior ?: porDescricao(lib.observacao, descricoes, linhas)
            if (chave == null) {
                println("AVISO: vínculo de corte nuconf $nuconf ($origem): liberação herdada seq ${lib.sequencia} sem produto identificável — vínculo não gravado")
                return false
            }
            forcados += chave
        }

        val (ignorarAMaior, ignorarAMenor) = withContext(Dispatchers.IO) { procedimentosCco(tenantId, nunota) }
        val previsto = preverSequencias(linhas, forcados, ignorarAMaior, ignorarAMenor)

        val colisoes = previsto.filterValues { it.size > 1 }
        if (colisoes.isNotEmpty()) {
            println("AVISO: vínculo de corte nuconf $nuconf ($origem): colisão de SEQUENCIA $colisoes — vínculo não gravado")
            return false
        }
        if (primeiraVez && previsto.keys != liberacoes.map { it.sequencia }.toSet()) {
            println(
                "AVISO: vínculo de corte nuconf $nuconf ($origem): regra previu SEQUENCIAs ${previsto.keys.sorted()} " +
                    "mas a TSILIB tem ${liberacoes.map { it.sequencia }.sorted()} — vínculo não gravado (fica a descrição)",
            )
            return false
        }

        val aceitos = LinkedHashMap<Int, Chave>()
        for (lib in novas) {
            val chave = previsto[lib.sequencia]?.single()
            if (chave == null) {
                println("AVISO: vínculo de corte nuconf $nuconf ($origem): regra não gerou a seq ${lib.sequencia} — fica a descrição")
                if (primeiraVez) return false else continue
            }
            val esperado = descricoes[chave.codprod]?.let { (d, c) -> textoProduto(d, c, chave.controle.takeIf { it != " " }) }
            val naObs = produtoDaObservacao(lib.observacao)
            if (esperado != null && naObs != null && esperado != naObs) {
                println(
                    "AVISO: vínculo de corte nuconf $nuconf ($origem): seq ${lib.sequencia} regra=${chave.codprod} '$esperado' " +
                        "mas OBSERVACAO='$naObs' — vínculo não gravado (fica a descrição)",
                )
                if (primeiraVez) return false else continue
            }
            aceitos[lib.sequencia] = chave
        }
        if (aceitos.isEmpty()) return false
        withContext(Dispatchers.IO) { gravar(tenantId, nunota, nuconf, aceitos) }
        println("INFO: vínculo de corte nuconf $nuconf ($origem): gravado ${aceitos.entries.joinToString { "${it.key}->${it.value.codprod}" }}")
        return true
    }

    /** "Prod.: X, ... Qtd. total conf." -> "X, ..." em maiúsculas (inclui Complem./Controle). */
    fun produtoDaObservacao(observacao: String?): String? =
        observacao?.let { REGEX_PRODUTO.find(it.trim())?.groupValues?.get(1)?.trim()?.uppercase() }

    private val REGEX_PRODUTO = Regex("""^Prod\.:\s*(.+?),\s*Qtd\.\s*total\s*conf\.:""", RegexOption.IGNORE_CASE)

    /** Produto da conferência cujo texto bate com a OBSERVACAO — só se for um único. */
    private fun porDescricao(observacao: String?, descricoes: Map<Int, Pair<String?, String?>>, linhas: List<LinhaDetalhe>): Chave? {
        val alvo = produtoDaObservacao(observacao) ?: return null
        val candidatos = linhas.map { Chave(it.codprod, it.controle) }.toSet().filter { k ->
            descricoes[k.codprod]?.let { (d, c) -> textoProduto(d, c, k.controle.takeIf { it != " " }) } == alvo
        }
        return candidatos.singleOrNull()
    }

    private suspend fun descricoesProdutos(tenantSlug: String, codprods: Set<Int>): Map<Int, Pair<String?, String?>> {
        if (codprods.isEmpty()) return emptyMap()
        return codprods.chunked(500).flatMap { lote ->
            SankhyaDbExplorerClient.executarQuery(
                tenantSlug,
                "SELECT CODPROD, DESCRPROD, COMPLDESC FROM TGFPRO WHERE CODPROD IN (${lote.joinToString(",")})",
            ).mapNotNull { r -> r["CODPROD"]?.toBigDecimalOrNull()?.toInt()?.let { it to (r["DESCRPROD"] to r["COMPLDESC"]) } }
        }.toMap()
    }

    /**
     * CCO da nota: GERARPEDCOMPL / PROCEDCORTE = 'N' ("não ajustar") fazem o Sankhya pular
     * o item SEM pedir liberação (e sem consumir o contador). Config ainda não sincronizada
     * -> não pula (a validação do conjunto de SEQUENCIAs pega se isso fizer diferença).
     */
    private fun procedimentosCco(tenantId: UUID, nunota: Long): Pair<Boolean, Boolean> {
        val nucco = wms.backend.tarefas.TarefasRepository.buscarNuccoLocal(tenantId, nunota) ?: return false to false
        val campos = wms.backend.configconferencia.ConfigConferenciaRepository.buscarPorNucco(tenantId, nucco)?.campos
            ?: return false to false
        val ajusteMaior = campos["GERARPEDCOMPL"]?.trim()?.uppercase()
        val corte = campos["PROCEDCORTE"]?.trim()?.uppercase()
        return (ajusteMaior !in setOf("A", "S")) to (corte !in setOf("A", "G", "I"))
    }

    /**
     * Mesma query que o ConferenciaHelper roda (copiada do Monitoramento do Sankhya,
     * service ConferenciaSP.cortar), separando pedido ('P') e conferência ('C') pra
     * agrupar aqui, + os itens existentes na nota ('N') — o Sankhya só pede liberação
     * de produto que tem item na nota.
     */
    private fun sqlDetalhe(nunota: Long, nuconf: Int): String = """
        SELECT 'P' AS ORIGEM, ITE.CODPROD, ITE.CONTROLE, ITE.CODVOL,
               ROUND(SUM((ITE.QTDNEG - ITE.QTDENTREGUE) - ITE.QTDCONFERIDA), CASE WHEN ITE.CODVOL <> PRO.CODVOL THEN COALESCE(PAR.INTEIRO, COALESCE(PRO.DECQTD, 0)) ELSE COALESCE(PRO.DECQTD, 0) END) AS QTD,
               0 AS MINSEQ
          FROM TGFITE ITE
          INNER JOIN TGFCAB CAB ON CAB.NUNOTA = ITE.NUNOTA
          INNER JOIN TGFTOP TPO ON TPO.CODTIPOPER = CAB.CODTIPOPER AND TPO.DHALTER = CAB.DHTIPOPER
          INNER JOIN TGFEMP EMP ON EMP.CODEMP = CAB.CODEMP
          INNER JOIN TGFPRO PRO ON PRO.CODPROD = ITE.CODPROD
          LEFT JOIN TSIPAR PAR ON PAR.CHAVE = 'DECVLRVOLALT'
         WHERE ITE.NUNOTA = $nunota
           AND (PRO.EXCLUIRCONF IS NULL OR PRO.EXCLUIRCONF = 'N')
           AND ITE.SEQUENCIA > 0
           AND (ITE.PENDENTE = 'S' OR EXISTS (SELECT 1 FROM TGFCAB CB1 WHERE CB1.NUNOTA = ITE.NUNOTA AND CB1.TIPMOV IN ('V', 'C', 'D', 'E', 'T', 'Q', 'L')))
           AND (COALESCE(EMP.UTILIZAWMS, 'N') = 'N' OR COALESCE(TPO.UTILIZAWMS, 'N') = 'N'
                OR EXISTS (SELECT 1 FROM TGFLOC WHERE CODLOCAL = ITE.CODLOCALORIG AND UTILIZAWMS = 'N')
                OR COALESCE(EMP.WMSDOCAREP, 'N') = 'S')
         GROUP BY ITE.CODPROD, ITE.CONTROLE, ITE.CODVOL, PRO.TIPCONTEST, CASE WHEN ITE.CODVOL <> PRO.CODVOL THEN COALESCE(PAR.INTEIRO, COALESCE(PRO.DECQTD, 0)) ELSE COALESCE(PRO.DECQTD, 0) END
        UNION ALL
        SELECT 'C', COI2.CODPROD, COI2.CONTROLE, COI2.CODVOL,
               ROUND(SUM(COI2.QTDCONFVOLPAD), CASE WHEN COI2.CODVOL <> PRO.CODVOL THEN COALESCE(PAR.INTEIRO, COALESCE(PRO.DECQTD, 0)) ELSE COALESCE(PRO.DECQTD, 0) END),
               MIN(COI2.SEQCONF)
          FROM TGFCOI2 COI2
          INNER JOIN TGFPRO PRO ON PRO.CODPROD = COI2.CODPROD
          LEFT JOIN TSIPAR PAR ON PAR.CHAVE = 'DECVLRVOLALT'
         WHERE COI2.NUCONF = $nuconf
         GROUP BY COI2.CODPROD, COI2.CONTROLE, COI2.CODVOL, PRO.TIPCONTEST, CASE WHEN COI2.CODVOL <> PRO.CODVOL THEN COALESCE(PAR.INTEIRO, COALESCE(PRO.DECQTD, 0)) ELSE COALESCE(PRO.DECQTD, 0) END
        UNION ALL
        SELECT DISTINCT 'N', ITE.CODPROD, ITE.CONTROLE, NULL, 0, 0
          FROM TGFITE ITE
         WHERE ITE.NUNOTA = $nunota AND ITE.SEQUENCIA > 0
    """.trimIndent()
}
