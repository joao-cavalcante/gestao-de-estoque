package wms.backend.separacao

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.selectAll
import wms.backend.erp.SankhyaDbExplorerClient
import wms.backend.erp.SankhyaSpClient
import wms.backend.tarefas.TarefasRepository
import wms.backend.tenancy.TenantTx
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Duration
import java.time.Instant
import java.util.UUID

/**
 * Motivo na OBSERVAÇÃO das notas que a conferência de COMPRA gera (usuário, 10/11/2026: "subir na observação o motivo
 * para guiar o usuário"):
 *  - notas de ajuste de peso da Torre (1495/1195) — [textoAjuste], usado por AjustePesoCompra na criação;
 *  - devolução (divergência a menor) e nota complementar (a maior), que o PRÓPRIO Sankhya gera pela CCO depois da
 *    liberação do corte — [anotarDerivadas], no ciclo de sync: acha as notas ligadas à compra (TGFVAR) ainda sem a
 *    marca [MARCA] e acrescenta o motivo e o próximo passo ao que já estiver na observação.
 */
object MotivoDivergenciaCompra {
    /** Marca na observação — nota já anotada não é anotada de novo. */
    const val MARCA = "[TO-DIV"

    /** Compras concluídas há até [JANELA] entram na procura das notas derivadas. */
    private val JANELA: Duration = Duration.ofDays(3)

    data class LinhaItem(val codprod: Int, val unidade: String, val qtdNota: BigDecimal, val conferido: BigDecimal)

    private fun num(v: BigDecimal, casas: Int = 3): String =
        v.setScale(casas, RoundingMode.HALF_UP).toPlainString().replace('.', ',')

    private fun pct(conferido: BigDecimal, nota: BigDecimal): String {
        if (nota.signum() == 0) return ""
        val p = conferido.subtract(nota).multiply(BigDecimal(100)).divide(nota, 1, RoundingMode.HALF_UP)
        return " (${if (p.signum() > 0) "+" else ""}${num(p, 1)}%)"
    }

    private fun tolerancia(acima: BigDecimal?, abaixo: BigDecimal?): String =
        "tolerância de peso: acima ${acima?.let { num(it, 1) + "%" } ?: "sem limite"}, abaixo ${abaixo?.let { num(it, 1) + "%" } ?: "sem limite"}"

    /** "337 PEITO PERU DEFUMADO SEARA: nota 10,000 KG, conferido 8,500 KG (-15,0%)". */
    fun linha(item: LinhaItem, descricao: String?): String {
        val un = item.unidade.ifBlank { "UN" }
        return "${item.codprod}${descricao?.let { " $it" } ?: ""}: nota ${num(item.qtdNota)} $un, conferido ${num(item.conferido)} $un" +
            pct(item.conferido, item.qtdNota)
    }

    /** Descrição - Complemento de cada produto (memória: produto-nome-e-ordem). Falha = sem descrição, nunca trava. */
    suspend fun descricoes(tenantSlug: String, codprods: Collection<Int>): Map<Int, String> {
        if (codprods.isEmpty()) return emptyMap()
        return runCatching {
            SankhyaDbExplorerClient.executarQuery(
                tenantSlug,
                "SELECT CODPROD, DESCRPROD, COMPLEMENTO FROM TGFPRO WHERE CODPROD IN (${codprods.distinct().joinToString()})",
            ).mapNotNull { r ->
                val cod = r["CODPROD"]?.trim()?.substringBefore('.')?.toIntOrNull() ?: return@mapNotNull null
                val nome = listOfNotNull(r["DESCRPROD"]?.trim()?.takeIf { it.isNotEmpty() }, r["COMPLEMENTO"]?.trim()?.takeIf { it.isNotEmpty() })
                    .joinToString(" - ")
                nome.takeIf { it.isNotEmpty() }?.let { cod to it }
            }.toMap()
        }.getOrDefault(emptyMap())
    }

    /** Observação da nota de ajuste: o que foi pesado, a tolerância e por que a diferença virou nota de ajuste. */
    fun textoAjuste(
        nunotaCompra: Int,
        nuconf: Int,
        entrada: Boolean,
        itens: List<Pair<LinhaItem, String?>>,
        tolAcima: BigDecimal?,
        tolAbaixo: BigDecimal?,
        marcador: String,
    ): String =
        "Ajuste de peso da nota $nunotaCompra — conferência $nuconf (Torre de Operação). " +
            itens.joinToString("; ") { (i, d) -> linha(i, d) } + ". " +
            "Dentro da ${tolerancia(tolAcima, tolAbaixo)}: a nota do fornecedor ficou com o peso do XML e a diferença " +
            "${if (entrada) "a maior ENTRA" else "a menor SAI"} do estoque por esta nota. $marcador"

    private fun textoDerivada(
        devolucao: Boolean,
        nunotaCompra: Long,
        nuconf: Int?,
        itens: List<Pair<LinhaItem, String?>>,
        tolAcima: BigDecimal?,
        tolAbaixo: BigDecimal?,
    ): String {
        val oQue = if (devolucao) "Devolução por divergência a MENOR" else "Nota complementar por divergência a MAIOR"
        val conf = nuconf?.let { " na conferência $it" } ?: ""
        val detalhe = if (itens.isEmpty()) "" else " " + itens.joinToString("; ") { (i, d) -> linha(i, d) } + "."
        return "$oQue$conf da nota $nunotaCompra (Torre de Operação).$detalhe " +
            "Fora da ${tolerancia(tolAcima, tolAbaixo)} — divergência liberada no corte. " +
            "Próximo passo: confirmar a nota de compra $nunotaCompra e depois esta ${if (devolucao) "devolução" else "nota complementar"}. " +
            "$MARCA ${nuconf ?: nunotaCompra}]"
    }

    private data class SessaoCompra(
        val id: UUID, val nunota: Long, val nuconf: Int?, val tolAcima: BigDecimal?, val tolAbaixo: BigDecimal?,
    )

    /**
     * Ciclo de sync: compras concluídas recentemente com item divergente (fora do ajuste) → notas derivadas no Sankhya
     * (TGFVAR com origem na compra, menos as de ajuste da Torre) ainda sem [MARCA] recebem o motivo na observação.
     */
    suspend fun anotarDerivadas(tenantSlug: String, tenantId: UUID, topsAjuste: Collection<Int>) {
        val desde = Instant.now().minus(JANELA)
        val sessoes = withContext(Dispatchers.IO) {
            TenantTx.run(tenantId) {
                SeparacaoSessoesTable.selectAll()
                    .where {
                        (SeparacaoSessoesTable.tenantId eq tenantId) and (SeparacaoSessoesTable.status eq SeparacaoStatus.CONCLUIDA)
                    }
                    .orderBy(SeparacaoSessoesTable.atualizadoEm, SortOrder.DESC)
                    .filter { it[SeparacaoSessoesTable.atualizadoEm].isAfter(desde) }
                    .map {
                        SessaoCompra(
                            it[SeparacaoSessoesTable.id], it[SeparacaoSessoesTable.nunota].toLong(), it[SeparacaoSessoesTable.nuconf],
                            it[SeparacaoSessoesTable.tolPesoAcimaPct], it[SeparacaoSessoesTable.tolPesoAbaixoPct],
                        )
                    }
                    .distinctBy { it.nunota } // a mais recente de cada nota
            }
        }.let { todas ->
            val compras = withContext(Dispatchers.IO) { TarefasRepository.nunotasComTipMov(tenantId, todas.map { it.nunota }, "C") }
            todas.filter { it.nunota in compras }
        }
        if (sessoes.isEmpty()) return

        // Itens divergentes de cada compra: conferido ≠ nota, menos os que viraram ajuste de peso (dentro da tolerância).
        val divergentes = sessoes.associateWith { s -> withContext(Dispatchers.IO) { itensDivergentes(tenantId, s.id) } }
            .filterValues { it.isNotEmpty() }
        if (divergentes.isEmpty()) return

        val porNunota = divergentes.keys.associateBy { it.nunota }
        val porNuconf = divergentes.keys.filter { it.nuconf != null }.associateBy { it.nuconf!! }
        val semMarca = "(C.OBSERVACAO IS NULL OR C.OBSERVACAO NOT LIKE '%[TO-DIV%')"
        val semAjuste = if (topsAjuste.isEmpty()) "" else " AND C.CODTIPOPER NOT IN (${topsAjuste.joinToString()})"
        // nota derivada -> (compra, devolução?)
        val alvo = linkedMapOf<Long, Pair<SessaoCompra, Boolean>>()
        fun longo(v: String?) = v?.trim()?.substringBefore('.')?.toLongOrNull()

        // 1. Devolução (a menor): o Sankhya grava em TGFCON2.NUNOTADEV; e qualquer nota ligada à compra pela TGFVAR.
        if (porNuconf.isNotEmpty()) {
            SankhyaDbExplorerClient.executarQuery(
                tenantSlug,
                "SELECT K.NUCONF, C.NUNOTA FROM TGFCON2 K JOIN TGFCAB C ON C.NUNOTA = K.NUNOTADEV " +
                    "WHERE K.NUCONF IN (${porNuconf.keys.joinToString()}) AND $semMarca",
            ).forEach { r ->
                val s = r["NUCONF"]?.trim()?.substringBefore('.')?.toIntOrNull()?.let { porNuconf[it] } ?: return@forEach
                longo(r["NUNOTA"])?.let { alvo[it] = s to true }
            }
        }
        SankhyaDbExplorerClient.executarQuery(
            tenantSlug,
            "SELECT DISTINCT V.NUNOTAORIG, C.NUNOTA, C.TIPMOV FROM TGFVAR V JOIN TGFCAB C ON C.NUNOTA = V.NUNOTA " +
                "WHERE V.NUNOTAORIG IN (${porNunota.keys.joinToString()}) AND V.NUNOTA <> V.NUNOTAORIG$semAjuste AND $semMarca",
        ).forEach { r ->
            val s = longo(r["NUNOTAORIG"])?.let { porNunota[it] } ?: return@forEach
            longo(r["NUNOTA"])?.let { n -> alvo.putIfAbsent(n, s to (r["TIPMOV"]?.trim() == "E")) }
        }

        // 2. Complementar (a maior): o Sankhya NÃO liga à compra (nota 83698, 10/11/2026: sem TGFVAR). É a nota na TOP do
        //    modelo de complemento da CCO (TGFCCO.MODNOTAPEDCOMPL), do mesmo fornecedor, com o produto que veio a maior.
        val aMaior = divergentes.filter { (_, itens) -> itens.any { it.conferido > it.qtdNota } }
        if (aMaior.isNotEmpty()) {
            val parcPorCompra = aMaior.keys.associateWith { s -> withContext(Dispatchers.IO) { TarefasRepository.buscarCodParcLocal(tenantId, s.nunota) } }
                .filterValues { it != null }
            if (parcPorCompra.isNotEmpty()) {
                val produtos = aMaior.values.flatten().map { it.codprod }.distinct()
                SankhyaDbExplorerClient.executarQuery(
                    tenantSlug,
                    "SELECT DISTINCT C.NUNOTA, C.CODPARC, I.CODPROD FROM TGFCAB C JOIN TGFITE I ON I.NUNOTA = C.NUNOTA " +
                        "WHERE C.CODTIPOPER IN (SELECT M.CODTIPOPER FROM TGFCAB M JOIN TGFCCO O ON O.MODNOTAPEDCOMPL = M.NUNOTA) " +
                        "AND C.CODPARC IN (${parcPorCompra.values.distinct().joinToString()}) AND I.CODPROD IN (${produtos.joinToString()}) " +
                        "AND C.DTNEG >= TRUNC(SYSDATE) - 3 AND C.NUNOTA NOT IN (${porNunota.keys.joinToString()}) AND $semMarca",
                ).forEach { r ->
                    val n = longo(r["NUNOTA"]) ?: return@forEach
                    val parc = r["CODPARC"]?.trim()?.substringBefore('.')?.toIntOrNull()
                    val prod = r["CODPROD"]?.trim()?.substringBefore('.')?.toIntOrNull()
                    // A compra mais recente desse fornecedor com esse produto a maior.
                    val s = parcPorCompra.entries.firstOrNull { (sc, p) ->
                        p == parc && aMaior.getValue(sc).any { it.codprod == prod && it.conferido > it.qtdNota }
                    }?.key ?: return@forEach
                    alvo.putIfAbsent(n, s to false)
                }
            }
        }
        if (alvo.isEmpty()) return
        val nomes = descricoes(tenantSlug, divergentes.values.flatten().map { it.codprod })

        for ((nunota, par) in alvo) {
            val (s, devolucao) = par
            // Devolução = itens a menor; complementar = a maior.
            val itens = divergentes.getValue(s)
                .filter { if (devolucao) it.conferido < it.qtdNota else it.conferido > it.qtdNota }
                .map { it to nomes[it.codprod] }
            val texto = textoDerivada(devolucao, s.nunota, s.nuconf, itens, s.tolAcima, s.tolAbaixo)
            runCatching { acrescentarObservacao(tenantSlug, nunota, texto) }
                .onSuccess { println("INFO: observação da nota $nunota (${if (devolucao) "devolução" else "complementar"} da compra ${s.nunota}) gravada") }
                .onFailure { println("AVISO: observação da nota $nunota (derivada da compra ${s.nunota}) não gravada: ${it.message}") }
        }
    }

    private fun itensDivergentes(tenantId: UUID, sessaoId: UUID): List<LinhaItem> = TenantTx.run(tenantId) {
        val ajustados = CompraAjustePesoItensTable.selectAll()
            .where { (CompraAjustePesoItensTable.tenantId eq tenantId) and (CompraAjustePesoItensTable.sessaoId eq sessaoId) }
            .map { it[CompraAjustePesoItensTable.codprod] to it[CompraAjustePesoItensTable.controle] }
            .toSet()
        SeparacaoItensTable.selectAll()
            .where { (SeparacaoItensTable.tenantId eq tenantId) and (SeparacaoItensTable.sessaoId eq sessaoId) }
            .filter { !it[SeparacaoItensTable.foraPedido] }
            .filter { (it[SeparacaoItensTable.codprod] to it[SeparacaoItensTable.controle]) !in ajustados }
            .groupBy { it[SeparacaoItensTable.codprod] }
            .mapNotNull { (codprod, linhas) ->
                val nota = linhas.fold(BigDecimal.ZERO) { a, l -> a + l[SeparacaoItensTable.qtdNeg] }
                val conf = linhas.fold(BigDecimal.ZERO) { a, l -> a + l[SeparacaoItensTable.qtdConferidaLocal] }
                if (nota.compareTo(conf) == 0) null
                else LinhaItem(codprod, linhas.first()[SeparacaoItensTable.codvol]?.trim().orEmpty(), nota, conf)
            }
    }

    /** OBSERVACAO = a atual + o motivo (até 4000 caracteres). */
    private suspend fun acrescentarObservacao(tenantSlug: String, nunota: Long, texto: String) {
        val atual = SankhyaDbExplorerClient.executarQuery(tenantSlug, "SELECT OBSERVACAO FROM TGFCAB WHERE NUNOTA = $nunota")
            .firstOrNull()?.get("OBSERVACAO")?.trim().orEmpty()
        val nova = (if (atual.isEmpty()) texto else "$atual\n$texto").take(4000)
        SankhyaSpClient.chamarRaw(
            tenantSlug, "CRUDServiceProvider.saveRecord", "mge",
            buildJsonObject {
                putJsonObject("dataSet") {
                    put("rootEntity", "CabecalhoNota")
                    put("includePresentationFields", "N")
                    putJsonObject("dataRow") {
                        putJsonObject("localFields") { putJsonObject("OBSERVACAO") { put("\$", nova) } }
                        putJsonObject("key") { putJsonObject("NUNOTA") { put("\$", nunota.toString()) } }
                    }
                    putJsonObject("entity") { putJsonObject("fieldset") { put("list", "NUNOTA,OBSERVACAO") } }
                }
            },
            retentar = false,
        )
    }
}
