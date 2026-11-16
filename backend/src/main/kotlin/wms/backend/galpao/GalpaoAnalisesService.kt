package wms.backend.galpao

import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import wms.backend.erp.SankhyaDbExplorerClient
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Fotografia analítica da planta (SyncService.refresh/analyse do Planta Log): lê produtos, estoque, vendas e
 * últimas compras do Sankhya — SÓ LEITURA (DbExplorer) — e cruza com os saldos endereçados da planta.
 * Fica em memória por [VALIDADE_CACHE]; "Atualizar" força nova leitura.
 */
object GalpaoAnalisesService {
    private val VALIDADE_CACHE: Duration = Duration.ofMinutes(10)
    private val ZONA = ZoneId.of("America/Sao_Paulo")

    /** Só o que vem do Sankhya fica em cache; o endereçado (muda a cada movimentação) é lido a cada pedido. */
    data class Leitura(
        val produtos: List<LinhaProduto>,
        val estoque: List<LinhaEstoque>,
        val vendas: List<GalpaoAnalises.LinhaVenda>,
        val compras: List<LinhaCompra>,
        val parametros: ParametrosAnaliseDto,
        val lidoEm: Instant,
    )
    private val cache = ConcurrentHashMap<String, Leitura>()

    class PlantaSemLocal : Exception("Planta sem empresa/local cadastrados")

    /** Parâmetros em uso. Hoje os padrões do Planta Log; a Administração da planta passa a gravá-los. */
    fun parametros(@Suppress("UNUSED_PARAMETER") tenantId: UUID, @Suppress("UNUSED_PARAMETER") plantaId: UUID) = ParametrosAnaliseDto()

    suspend fun analises(tenantId: UUID, slug: String, planta: PlantaDto, forcar: Boolean = false): AnalisesPlantaDto =
        fotografia(tenantId, slug, planta, forcar).second

    /** Leitura bruta do Sankhya (estoque por lote etc.) + a análise montada — base dos alertas. */
    suspend fun fotografia(tenantId: UUID, slug: String, planta: PlantaDto, forcar: Boolean = false): Pair<Leitura, AnalisesPlantaDto> {
        val codemp = planta.codemp ?: throw PlantaSemLocal()
        val codlocal = planta.codlocal ?: throw PlantaSemLocal()
        val plantaId = UUID.fromString(planta.id)
        val p = parametros(tenantId, plantaId)
        val chave = "$tenantId:$plantaId"
        val atual = cache[chave]
        val leitura = if (!forcar && atual != null && atual.parametros == p && Duration.between(atual.lidoEm, Instant.now()) < VALIDADE_CACHE) {
            atual
        } else {
            coroutineScope {
                val produtos = async { lerProdutos(slug) }
                val estoque = async { lerEstoque(slug, codemp, codlocal) }
                val vendas = async { lerVendas(slug, codemp, codlocal, p.dias) }
                val compras = async { lerUltimasCompras(slug, codemp, codlocal, p.tipmovCompra) }
                Leitura(produtos.await(), estoque.await(), vendas.await(), compras.await(), p, Instant.now())
            }.also { cache[chave] = it }
        }
        return leitura to montar(
            leitura.produtos, leitura.estoque, leitura.vendas, leitura.compras,
            GalpaoRepository.enderecadoPorProduto(tenantId, plantaId), p, LocalDate.now(ZONA), leitura.lidoEm,
        )
    }

    fun esquecer(tenantId: UUID, plantaId: UUID) {
        cache.remove("$tenantId:$plantaId")
    }

    // ─── Leituras do Sankhya (só números/listas fixas entram no SQL) ─────────

    data class LinhaProduto(
        val codprod: Int, val descricao: String, val ativo: Boolean, val codvol: String?, val codgrupo: Int?, val grupo: String?,
        val setor: Int?, val setorGrupo: Int?, val pesoBruto: Double?, val m3: Double?,
    )

    data class LinhaEstoque(val codprod: Int, val controle: String, val estoque: Double, val reservado: Double, val dtval: LocalDate?, val dtentrada: LocalDate?)

    data class LinhaCompra(val codprod: Int, val controle: String, val data: LocalDate)

    private suspend fun lerProdutos(slug: String): List<LinhaProduto> {
        val setor = { col: String -> "CASE WHEN TRIM(TO_CHAR($col)) IN ('1','2','3') THEN TO_NUMBER($col) END" }
        val sql = "SELECT P.CODPROD, P.DESCRPROD, P.COMPLDESC, P.ATIVO, P.CODVOL, P.CODGRUPOPROD, G.DESCRGRUPOPROD, " +
            "${setor("P.AD_TIPOSEPARACAO")} AS SETOR, ${setor("G.AD_TIPOSEPARACAO")} AS SETORGRUPO, P.PESOBRUTO, P.M3 " +
            "FROM TGFPRO P LEFT JOIN TGFGRU G ON G.CODGRUPOPROD = P.CODGRUPOPROD"
        return consultar(slug, sql).mapNotNull { r ->
            val cod = int(r["CODPROD"]) ?: return@mapNotNull null
            val descr = r["DESCRPROD"]?.trim().orEmpty()
            val compl = r["COMPLDESC"]?.trim().orEmpty()
            LinhaProduto(
                codprod = cod,
                descricao = if (compl.isEmpty()) descr else "$descr - $compl",
                ativo = r["ATIVO"]?.trim() == "S",
                codvol = r["CODVOL"]?.trim(),
                codgrupo = int(r["CODGRUPOPROD"]),
                grupo = r["DESCRGRUPOPROD"]?.trim(),
                setor = int(r["SETOR"]),
                setorGrupo = int(r["SETORGRUPO"]),
                pesoBruto = dec(r["PESOBRUTO"])?.takeIf { it > 0 },
                m3 = dec(r["M3"])?.takeIf { it > 0 },
            )
        }
    }

    /** Estoque próprio (TIPO 'P', sem parceiro) — o mesmo que a planta concilia. */
    private suspend fun lerEstoque(slug: String, codemp: Int, codlocal: Int): List<LinhaEstoque> {
        val sql = "SELECT CODPROD, CONTROLE, ESTOQUE, RESERVADO, TO_CHAR(DTVAL, 'YYYY-MM-DD') AS DTVAL, " +
            "TO_CHAR(DTENTRADA, 'YYYY-MM-DD') AS DTENTRADA FROM TGFEST " +
            "WHERE CODEMP = $codemp AND CODLOCAL = $codlocal AND TIPO = 'P' AND CODPARC = 0"
        return consultar(slug, sql).mapNotNull { r ->
            LinhaEstoque(
                codprod = int(r["CODPROD"]) ?: return@mapNotNull null,
                controle = GalpaoErp.normalizarControle(r["CONTROLE"]),
                estoque = dec(r["ESTOQUE"]) ?: 0.0,
                reservado = dec(r["RESERVADO"]) ?: 0.0,
                dtval = data(r["DTVAL"]),
                dtentrada = data(r["DTENTRADA"]),
            )
        }
    }

    /** Vendas confirmadas (TIPMOV 'V', liberadas, não pendentes nem canceladas) saídas do local nos últimos [dias]. */
    private suspend fun lerVendas(slug: String, codemp: Int, codlocal: Int, dias: Int): List<GalpaoAnalises.LinhaVenda> {
        val sql = "SELECT I.CODPROD, SUM(I.VLRTOT - NVL(I.VLRDESC, 0)) AS VALOR, SUM(I.QTDNEG) AS QUANTIDADE, " +
            "COUNT(*) AS FREQUENCIA, TO_CHAR(MAX(C.DTNEG), 'YYYY-MM-DD') AS ULTIMAVENDA " +
            "FROM TGFCAB C JOIN TGFITE I ON I.NUNOTA = C.NUNOTA " +
            "WHERE C.CODEMP = $codemp AND I.CODLOCALORIG = $codlocal AND C.TIPMOV = 'V' AND C.STATUSNOTA = 'L' " +
            "AND C.PENDENTE = 'N' AND NVL(C.CANCELADO, 'N') <> 'S' " +
            "AND C.DTNEG >= TRUNC(SYSDATE) - ${dias - 1} AND C.DTNEG < TRUNC(SYSDATE) + 1 GROUP BY I.CODPROD"
        return consultar(slug, sql).mapNotNull { r ->
            GalpaoAnalises.LinhaVenda(
                codprod = int(r["CODPROD"]) ?: return@mapNotNull null,
                valor = dec(r["VALOR"]) ?: 0.0,
                quantidade = dec(r["QUANTIDADE"]) ?: 0.0,
                frequencia = int(r["FREQUENCIA"]) ?: 0,
                ultimaVenda = data(r["ULTIMAVENDA"]),
            )
        }
    }

    private suspend fun lerUltimasCompras(slug: String, codemp: Int, codlocal: Int, tipmovs: List<String>): List<LinhaCompra> {
        val tipos = tipmovs.filter { it.matches(Regex("^[A-Z]$")) }
        if (tipos.isEmpty()) return emptyList()
        val sql = "SELECT I.CODPROD, I.CONTROLE, TO_CHAR(MAX(C.DTNEG), 'YYYY-MM-DD') AS ULTIMA FROM TGFCAB C " +
            "JOIN TGFITE I ON I.NUNOTA = C.NUNOTA WHERE C.CODEMP = $codemp AND I.CODLOCALORIG = $codlocal " +
            "AND C.TIPMOV IN (${tipos.joinToString(",") { "'$it'" }}) AND C.STATUSNOTA = 'L' AND C.PENDENTE = 'N' " +
            "AND NVL(C.CANCELADO, 'N') <> 'S' GROUP BY I.CODPROD, I.CONTROLE"
        return consultar(slug, sql).mapNotNull { r ->
            LinhaCompra(
                codprod = int(r["CODPROD"]) ?: return@mapNotNull null,
                controle = GalpaoErp.normalizarControle(r["CONTROLE"]),
                data = data(r["ULTIMA"]) ?: return@mapNotNull null,
            )
        }
    }

    // ─── Montagem (pura, testável) ───────────────────────────────────────────

    fun montar(
        produtos: List<LinhaProduto>,
        estoque: List<LinhaEstoque>,
        vendas: List<GalpaoAnalises.LinhaVenda>,
        compras: List<LinhaCompra>,
        enderecado: Map<Int, Double>,
        p: ParametrosAnaliseDto,
        hoje: LocalDate,
        lidoEm: Instant = Instant.now(),
    ): AnalisesPlantaDto {
        val porVenda = vendas.associateBy { it.codprod }
        val abcValor = GalpaoAnalises.abc(vendas, GalpaoAnalises.MetricaAbc.VALOR, p.limiteA, p.limiteB)
        val abcQtd = GalpaoAnalises.abc(vendas, GalpaoAnalises.MetricaAbc.QUANTIDADE, p.limiteA, p.limiteB)
        val abcFreq = GalpaoAnalises.abc(vendas, GalpaoAnalises.MetricaAbc.FREQUENCIA, p.limiteA, p.limiteB)
        val estoquePorProduto = estoque.groupBy { it.codprod }
        val compraPorLote = compras.associate { (it.codprod to it.controle) to it.data }
        val nd = GalpaoAnalises.ResultadoAbc("N/D", null, null)

        val linhas = produtos.map { pr ->
            val lotes = estoquePorProduto[pr.codprod].orEmpty()
            val qtd = lotes.sumOf { it.estoque }
            val res = lotes.sumOf { it.reservado }
            val end = enderecado[pr.codprod] ?: 0.0
            val venda = porVenda[pr.codprod]
            val vendido = venda?.quantidade ?: 0.0
            val cob = GalpaoAnalises.cobertura(qtd, res, vendido, p.dias)
            val vencimento = lotes.mapNotNull { it.dtval }.minOrNull()
            val entrada = lotes.mapNotNull { it.dtentrada }.minOrNull()
            val ultimaCompra = lotes.mapNotNull { compraPorLote[pr.codprod to it.controle] }.minOrNull()
            val idade = GalpaoAnalises.idade(entrada, ultimaCompra, hoje)
            val (setor, origemSetor) = when {
                pr.setor != null -> pr.setor to "PRODUTO"
                p.setorPeloGrupo && pr.setorGrupo != null -> pr.setorGrupo to "GRUPO"
                else -> null to "N/D"
            }
            val consistente = kotlin.math.abs(qtd - end) < 1e-6 && qtd >= 0 && res <= qtd + 1e-9
            val saude = GalpaoAnalises.saude(
                mapOf(
                    "CONSISTENCIA" to if (consistente) 1.0 else 0.0,
                    "COBERTURA" to GalpaoAnalises.qualidadeCobertura(cob.dias, p.coberturaMinDias, p.coberturaMaxDias),
                    "RECENCIA" to venda?.ultimaVenda?.let { 1.0 },
                    "VALIDADE" to GalpaoAnalises.qualidadeValidade(vencimento, hoje),
                    "ENDERECO" to if (setor == null) null else if (end > 0) 1.0 else 0.0,
                ),
            )
            val av = abcValor[pr.codprod] ?: nd
            val aq = abcQtd[pr.codprod] ?: nd
            val af = abcFreq[pr.codprod] ?: nd
            ProdutoAnaliseDto(
                codprod = pr.codprod, descricao = pr.descricao, ativo = pr.ativo, codvol = pr.codvol,
                codgrupo = pr.codgrupo, grupo = pr.grupo, setor = setor, setorOrigem = origemSetor,
                pesoBruto = pr.pesoBruto, m3 = pr.m3,
                valorVendido = venda?.valor ?: 0.0, quantidadeVendida = vendido, frequencia = venda?.frequencia ?: 0,
                ultimaVenda = venda?.ultimaVenda?.toString(),
                abc = av.classe, participacao = av.participacao, acumulado = av.acumulado,
                abcQtd = aq.classe, participacaoQtd = aq.participacao, acumuladoQtd = aq.acumulado,
                abcFreq = af.classe, participacaoFreq = af.participacao, acumuladoFreq = af.acumulado,
                estoque = qtd, reservado = res, disponivel = qtd - res, enderecado = end,
                demandaDia = cob.demandaDia, cobertura = cob.dias, coberturaOrigem = cob.origem,
                dtval = vencimento?.toString(), validade = GalpaoAnalises.validade(vencimento, hoje),
                parado = qtd > 0 && vendido == 0.0,
                idadeDias = idade.dias, idadeOrigem = idade.origem, saude = saude,
            )
        }
        return AnalisesPlantaDto(
            lidoEm = lidoEm.toString(),
            parametros = p,
            produtos = linhas,
            validadesLote = estoque.filter { it.dtval != null }.map { ValidadeLoteDto(it.codprod, it.controle, it.dtval.toString()) },
        )
    }

    private suspend fun consultar(slug: String, sql: String) =
        SankhyaDbExplorerClient.executarQuery(slug, sql).map { r -> r.mapKeys { it.key.uppercase() } }

    private fun int(v: String?): Int? = v?.trim()?.toDoubleOrNull()?.toInt()
    private fun dec(v: String?): Double? = v?.trim()?.replace(",", ".")?.toDoubleOrNull()
    private fun data(v: String?): LocalDate? = v?.trim()?.takeIf { it.length == 10 }?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
}
