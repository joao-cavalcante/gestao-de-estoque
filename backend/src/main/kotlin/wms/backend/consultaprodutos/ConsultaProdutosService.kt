package wms.backend.consultaprodutos

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import wms.backend.erp.LoadRecordsRequest
import wms.backend.erp.SankhyaLoadRecordsClient
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Consulta de Produtos: o catálogo inteiro (espelho local) com o saldo da instância `Estoque` do
 * Sankhya (TGFEST) via loadRecords. Ligações da instância (dicionário do Sankhya): Produto,
 * Empresa, LocalFinanceiro.
 *
 * Saldo de TODOS os produtos = varredura completa da instância — CRUDServiceProvider, que pagina
 * de verdade (o DatasetSP corta em useDefaultRowsLimit sem avisar). Por ser pesada, a leitura fica
 * em memória por [TTL_ESTOQUE] por tenant; "Atualizar" na tela força uma nova.
 */
object ConsultaProdutosService {

    private val TTL_ESTOQUE: Duration = Duration.ofMinutes(3)

    /** Só colunas da própria instância — no CRUDServiceProvider campo ligado volta com outro nome. */
    private val CAMPOS_ESTOQUE = listOf("CODPROD", "CODEMP", "CODLOCAL", "CONTROLE", "ESTOQUE", "RESERVADO")

    private data class LinhaEstoque(
        val codprod: Int,
        val codemp: Int?,
        val codlocal: Int?,
        val controle: String?,
        val estoque: Double,
        val reservado: Double,
    )

    private class Leitura(
        val linhas: List<LinhaEstoque>,
        val empresas: Map<Int, String>,
        val locais: Map<Int, String>,
        val lidoEm: Instant,
    )

    private val cache = ConcurrentHashMap<UUID, Leitura>()
    private val travas = ConcurrentHashMap<UUID, Mutex>()

    suspend fun consultar(tenantSlug: String, tenantId: UUID, forcar: Boolean): ConsultaProdutosRespostaDto {
        val (catalogo, barras) = withContext(Dispatchers.IO) {
            ConsultaProdutosRepository.listarTodos(tenantId) to ConsultaProdutosRepository.codigosBarraPorProduto(tenantId)
        }

        val (leitura, erro) = try {
            lerEstoque(tenantSlug, tenantId, forcar) to null
        } catch (e: Exception) {
            println("AVISO: consulta de produtos — falha ao ler a instância Estoque (tenant $tenantSlug): ${e.message}")
            // Sankhya fora: devolve a última leitura boa, se houver, junto com o aviso.
            cache[tenantId] to (e.message ?: "falha ao consultar o estoque no Sankhya")
        }
        val porProduto = leitura?.linhas.orEmpty().groupBy { it.codprod }

        return ConsultaProdutosRespostaDto(
            produtos = catalogo.map { p ->
                val locais = porProduto[p.codprod].orEmpty()
                    .map { r ->
                        EstoqueLocalDto(
                            codemp = r.codemp,
                            empresa = r.codemp?.let { leitura?.empresas?.get(it) },
                            codlocal = r.codlocal,
                            local = r.codlocal?.let { leitura?.locais?.get(it) },
                            controle = r.controle,
                            estoque = r.estoque,
                            reservado = r.reservado,
                            disponivel = r.estoque - r.reservado,
                        )
                    }
                    .sortedWith(compareBy({ it.codemp }, { it.codlocal }, { it.controle }))
                ProdutoEstoqueDto(
                    codprod = p.codprod,
                    descricao = p.descricao,
                    complemento = p.complemento,
                    marca = p.marca,
                    referencia = p.referencia,
                    unidade = p.unidade,
                    codigosBarra = barras[p.codprod].orEmpty(),
                    estoque = locais.sumOf { it.estoque },
                    reservado = locais.sumOf { it.reservado },
                    disponivel = locais.sumOf { it.disponivel },
                    locais = locais,
                )
            },
            estoqueLidoEm = leitura?.lidoEm?.toString(),
            erroEstoque = erro,
        )
    }

    /** Leitura em cache se recente; senão varre a instância (uma varredura por tenant por vez). */
    private suspend fun lerEstoque(tenantSlug: String, tenantId: UUID, forcar: Boolean): Leitura {
        fun fresca() = cache[tenantId]?.takeIf { Duration.between(it.lidoEm, Instant.now()) < TTL_ESTOQUE }
        if (!forcar) fresca()?.let { return it }
        val inicioEspera = Instant.now()
        return travas.computeIfAbsent(tenantId) { Mutex() }.withLock {
            // Outra requisição varreu enquanto esta esperava a trava — aproveita.
            cache[tenantId]?.takeIf { it.lidoEm >= inicioEspera || (!forcar && fresca() != null) }?.let { return@withLock it }
            val linhas = varrerEstoque(tenantSlug)
            val leitura = Leitura(
                linhas = linhas,
                empresas = runCatching { nomes(tenantSlug, "Empresa", "CODEMP", "NOMEFANTASIA", linhas.mapNotNull { it.codemp }) }
                    .onFailure { println("AVISO: consulta de produtos — nomes de empresa: ${it.message}") }.getOrDefault(emptyMap()),
                locais = runCatching { nomes(tenantSlug, "LocalFinanceiro", "CODLOCAL", "DESCRLOCAL", linhas.mapNotNull { it.codlocal }) }
                    .onFailure { println("AVISO: consulta de produtos — nomes de local: ${it.message}") }.getOrDefault(emptyMap()),
                lidoEm = Instant.now(),
            )
            cache[tenantId] = leitura
            leitura
        }
    }

    /** Estoque PRÓPRIO (TIPO='P', sem parceiro) com algum saldo — terceiro não é saldo do armazém. */
    private suspend fun varrerEstoque(tenantSlug: String): List<LinhaEstoque> {
        val inicio = System.currentTimeMillis()
        val linhas = mutableListOf<LinhaEstoque>()
        var pagina = 0
        while (true) {
            val raw = SankhyaLoadRecordsClient.loadRecords(
                tenantSlug,
                LoadRecordsRequest(
                    entityName = "Estoque",
                    fields = CAMPOS_ESTOQUE,
                    criteriaExpression = "this.CODPARC = 0 AND this.TIPO = 'P' AND (this.ESTOQUE <> 0 OR this.RESERVADO <> 0)",
                    usarCrudServiceProvider = true,
                    offsetPage = pagina,
                ),
            )
            SankhyaLoadRecordsClient.parseRows(raw, CAMPOS_ESTOQUE).forEach { r ->
                val codprod = r["CODPROD"]?.toIntOrNull() ?: return@forEach
                linhas += LinhaEstoque(
                    codprod = codprod,
                    codemp = r["CODEMP"]?.toIntOrNull(),
                    codlocal = r["CODLOCAL"]?.toIntOrNull(),
                    controle = r["CONTROLE"],
                    estoque = r["ESTOQUE"].numero(),
                    reservado = r["RESERVADO"].numero(),
                )
            }
            if (!SankhyaLoadRecordsClient.hasMoreResult(raw)) break
            pagina++
        }
        println("INFO: consulta de produtos — Estoque varrido (tenant $tenantSlug): ${linhas.size} linha(s), ${pagina + 1} página(s), ${System.currentTimeMillis() - inicio} ms")
        return linhas
    }

    /** Código -> descrição de uma instância pequena (Empresa / LocalFinanceiro). */
    private suspend fun nomes(tenantSlug: String, instancia: String, chave: String, campo: String, codigos: List<Int>): Map<Int, String> {
        val distintos = codigos.distinct()
        if (distintos.isEmpty()) return emptyMap()
        val fields = listOf(chave, campo)
        return distintos.chunked(500).flatMap { lote ->
            val raw = SankhyaLoadRecordsClient.loadRecords(
                tenantSlug,
                LoadRecordsRequest(entityName = instancia, fields = fields, criteriaExpression = "$chave IN (${lote.joinToString(",")})"),
            )
            SankhyaLoadRecordsClient.parseRows(raw, fields).mapNotNull { r ->
                val cod = r[chave]?.toIntOrNull() ?: return@mapNotNull null
                val nome = r[campo]?.trim()?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
                cod to nome
            }
        }.toMap()
    }

    private fun String?.numero(): Double = this?.trim()?.replace(",", ".")?.toDoubleOrNull() ?: 0.0
}
