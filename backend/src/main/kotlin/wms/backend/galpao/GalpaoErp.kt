package wms.backend.galpao

import wms.backend.erp.SankhyaDbExplorerClient
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

/** Saldo contábil próprio (TGFEST, TIPO='P', sem parceiro) por produto/controle no empresa/local da planta. */
data class LinhaContabil(val codprod: Int, val controle: String, val estoque: Double, val reservado: Double)

/**
 * Leituras do Sankhya usadas pela movimentação do galpão — SÓ LEITURA (DbExplorer, SQL ANSI; só números
 * entram no texto do SQL). Peso/volume do produto ficam em memória por [TTL_FISICO].
 */
object GalpaoErp {
    private val TTL_FISICO: Duration = Duration.ofMinutes(10)
    private const val PRODUTOS_POR_CONSULTA = 500

    private data class Cacheado(val fisico: FisicoProduto, val lidoEm: Instant)
    private val cacheFisico = ConcurrentHashMap<String, ConcurrentHashMap<Int, Cacheado>>()

    /** Controle vazio do Sankhya vem como ' ' — no WMS é ''. */
    fun normalizarControle(c: String?): String = c?.trim().orEmpty()

    suspend fun estoqueContabil(tenantSlug: String, codemp: Int, codlocal: Int, codprod: Int? = null): List<LinhaContabil> {
        val filtroProduto = codprod?.let { " AND CODPROD = $it" }.orEmpty()
        val sql = "SELECT CODPROD, CONTROLE, SUM(ESTOQUE) AS ESTOQUE, SUM(RESERVADO) AS RESERVADO FROM TGFEST " +
            "WHERE CODEMP = $codemp AND CODLOCAL = $codlocal AND TIPO = 'P' AND CODPARC = 0$filtroProduto " +
            "GROUP BY CODPROD, CONTROLE"
        return SankhyaDbExplorerClient.executarQuery(tenantSlug, sql).mapNotNull { r ->
            val linha = r.mapKeys { it.key.uppercase() }
            val cod = linha["CODPROD"]?.trim()?.toDoubleOrNull()?.toInt() ?: return@mapNotNull null
            LinhaContabil(cod, normalizarControle(linha["CONTROLE"]), numero(linha["ESTOQUE"]), numero(linha["RESERVADO"]))
        }
            // Duas linhas com controle ' ' e '' viram uma só.
            .groupBy { it.codprod to it.controle }
            .map { (k, v) -> LinhaContabil(k.first, k.second, v.sumOf { it.estoque }, v.sumOf { it.reservado }) }
    }

    suspend fun fisicos(tenantSlug: String, codprods: Collection<Int>): Map<Int, FisicoProduto> {
        val cache = cacheFisico.getOrPut(tenantSlug) { ConcurrentHashMap() }
        val agora = Instant.now()
        val faltando = codprods.distinct().filter { c -> cache[c]?.let { Duration.between(it.lidoEm, agora) > TTL_FISICO } ?: true }
        for (lote in faltando.chunked(PRODUTOS_POR_CONSULTA)) {
            val sql = "SELECT CODPROD, PESOBRUTO, M3 FROM TGFPRO WHERE CODPROD IN (${lote.joinToString(",")})"
            val lidos = SankhyaDbExplorerClient.executarQuery(tenantSlug, sql).mapNotNull { r ->
                val linha = r.mapKeys { it.key.uppercase() }
                val cod = linha["CODPROD"]?.trim()?.toDoubleOrNull()?.toInt() ?: return@mapNotNull null
                cod to FisicoProduto(numeroOuNulo(linha["PESOBRUTO"]), numeroOuNulo(linha["M3"]))
            }.toMap()
            // Produto sem linha (excluído no Sankhya) fica sem peso/volume — não zero.
            lote.forEach { c -> cache[c] = Cacheado(lidos[c] ?: FisicoProduto(null, null), agora) }
        }
        return codprods.associateWith { cache[it]?.fisico ?: FisicoProduto(null, null) }
    }

    /**
     * Documento por número: NUNOTA, ou NUMNOTA de pedido/venda no empresa da planta. Vários achados = o mais
     * recente. null = não existe.
     */
    suspend fun documento(tenantSlug: String, numero: Int, codemp: Int?): DocumentoBuscaDto? {
        val porNumnota = codemp?.let { " OR (C.NUMNOTA = $numero AND C.CODEMP = $it AND C.TIPMOV IN ('P','V'))" }.orEmpty()
        val sql = "SELECT C.NUNOTA, C.NUMNOTA, C.TIPMOV, I.CODPROD FROM TGFCAB C JOIN TGFITE I ON I.NUNOTA = C.NUNOTA " +
            "WHERE C.NUNOTA = $numero$porNumnota"
        val linhas = SankhyaDbExplorerClient.executarQuery(tenantSlug, sql).map { r -> r.mapKeys { it.key.uppercase() } }
        val int = { v: String? -> v?.trim()?.toDoubleOrNull()?.toInt() }
        val porNota = linhas.groupBy { int(it["NUNOTA"]) }.filterKeys { it != null }
        // NUNOTA exato ganha; senão o mais recente.
        val (nunota, itens) = porNota.entries.firstOrNull { it.key == numero }?.toPair()
            ?: porNota.maxByOrNull { it.key!! }?.toPair() ?: return null
        return DocumentoBuscaDto(
            nunota = nunota!!,
            numnota = int(itens.first()["NUMNOTA"]),
            tipmov = itens.first()["TIPMOV"]?.trim(),
            codprods = itens.mapNotNull { int(it["CODPROD"]) }.distinct(),
        )
    }

    private fun numero(v: String?): Double = numeroOuNulo(v) ?: 0.0
    private fun numeroOuNulo(v: String?): Double? = v?.trim()?.replace(",", ".")?.toDoubleOrNull()?.takeIf { it > 0 || it < 0 }
}
