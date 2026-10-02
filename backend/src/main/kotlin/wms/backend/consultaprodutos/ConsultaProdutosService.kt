package wms.backend.consultaprodutos

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
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
 * Saldo de todos os produtos = DatasetSP.loadRecords por FAIXA de CODPROD, faixas em paralelo.
 * Não CRUDServiceProvider: ele pagina de ~50 em ~50 linhas, uma página atrás da outra — medido em
 * produção (Negri, 02/10/2026): 1270 linhas, 26 páginas, 28 s. Faixa (e não uma chamada só) porque
 * o DatasetSP corta em useDefaultRowsLimit sem avisar — cada faixa fica bem abaixo do limite.
 *
 * A leitura fica em memória por tenant: dentro de [TTL_ESTOQUE] é reaproveitada; depois disso a
 * tela recebe a leitura anterior NA HORA e uma nova é feita por trás. "Atualizar" espera a nova.
 */
object ConsultaProdutosService {

    private val TTL_ESTOQUE: Duration = Duration.ofMinutes(3)

    /** Produtos do catálogo por faixa (cada faixa = uma chamada). */
    private const val PRODUTOS_POR_FAIXA = 1500
    private const val FAIXAS_EM_PARALELO = 4

    private val CAMPOS_BASE = listOf("CODPROD", "CODEMP", "CODLOCAL", "CONTROLE", "ESTOQUE", "RESERVADO")
    /** Nomes pelas ligações da própria instância — o usuário de integração não acessa LocalFinanceiro direto. */
    private val CAMPOS_COM_NOMES = CAMPOS_BASE + listOf("Empresa.NOMEFANTASIA", "LocalFinanceiro.DESCRLOCAL")

    private data class LinhaEstoque(
        val codprod: Int,
        val codemp: Int?,
        val empresa: String?,
        val codlocal: Int?,
        val local: String?,
        val controle: String?,
        val estoque: Double,
        val reservado: Double,
    )

    /** [pesaveis] null = a regra de pesável não pôde ser lida (a tela mostra "—", não "Não"). */
    private class Leitura(val linhas: List<LinhaEstoque>, val pesaveis: Set<Int>?, val lidoEm: Instant)

    private val escopo = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val cache = ConcurrentHashMap<UUID, Leitura>()
    private val travas = ConcurrentHashMap<UUID, Mutex>()
    /** Tenants em que a leitura com os campos ligados foi recusada — passam a ler só os códigos. */
    private val semNomes = ConcurrentHashMap.newKeySet<String>()

    suspend fun consultar(tenantSlug: String, tenantId: UUID, forcar: Boolean): ConsultaProdutosRespostaDto {
        val (catalogo, barras) = withContext(Dispatchers.IO) {
            ConsultaProdutosRepository.listarTodos(tenantId) to ConsultaProdutosRepository.codigosBarraPorProduto(tenantId)
        }
        val codprods = catalogo.map { it.codprod }

        val (leitura, erro) = try {
            lerEstoque(tenantSlug, tenantId, codprods, forcar) to null
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
                            empresa = r.empresa,
                            codlocal = r.codlocal,
                            local = r.local,
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
                    pesavel = leitura?.pesaveis?.let { p.codprod in it },
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

    private suspend fun lerEstoque(tenantSlug: String, tenantId: UUID, codprods: List<Int>, forcar: Boolean): Leitura {
        val atual = cache[tenantId]
        if (!forcar && atual != null) {
            if (Duration.between(atual.lidoEm, Instant.now()) >= TTL_ESTOQUE) {
                // Velha: entrega já e atualiza por trás (se ninguém já estiver atualizando).
                val trava = travas.computeIfAbsent(tenantId) { Mutex() }
                if (!trava.isLocked) {
                    escopo.launch {
                        runCatching { atualizar(tenantSlug, tenantId, codprods, desde = atual.lidoEm) }
                            .onFailure { println("AVISO: consulta de produtos — atualização em segundo plano falhou (tenant $tenantSlug): ${it.message}") }
                    }
                }
            }
            return atual
        }
        return atualizar(tenantSlug, tenantId, codprods, desde = if (forcar) Instant.now() else Instant.EPOCH)
    }

    /** Uma leitura por tenant por vez; quem esperou a trava aproveita a leitura feita depois de [desde]. */
    private suspend fun atualizar(tenantSlug: String, tenantId: UUID, codprods: List<Int>, desde: Instant): Leitura =
        travas.computeIfAbsent(tenantId) { Mutex() }.withLock {
            cache[tenantId]?.takeIf { it.lidoEm > desde }?.let { return@withLock it }
            coroutineScope {
                val pesaveis = async { lerPesaveis(tenantSlug, tenantId, codprods) }
                val linhas = lerFaixas(tenantSlug, codprods)
                Leitura(linhas, pesaveis.await(), Instant.now()).also { cache[tenantId] = it }
            }
        }

    /** Mesma regra da conferência/Mapa (RegraPesavel) — uma consulta só pro catálogo inteiro. */
    private suspend fun lerPesaveis(tenantSlug: String, tenantId: UUID, codprods: List<Int>): Set<Int>? = runCatching {
        val decisor = wms.backend.produtos.RegraPesavel.decisor(tenantSlug, tenantId, "CODPROD > 0")
        codprods.filter { decisor.pesavel(it, null) }.toSet()
    }.onFailure { println("AVISO: consulta de produtos — falha ao decidir pesáveis (tenant $tenantSlug): ${it.message}") }
        .getOrNull()

    private suspend fun lerFaixas(tenantSlug: String, codprods: List<Int>): List<LinhaEstoque> {
        val inicio = System.currentTimeMillis()
        val faixas = codprods.distinct().sorted().chunked(PRODUTOS_POR_FAIXA).map { it.first() to it.last() }
        val semaforo = Semaphore(FAIXAS_EM_PARALELO)
        val linhas = coroutineScope {
            faixas.map { (de, ate) -> async { semaforo.withPermit { lerFaixa(tenantSlug, de, ate) } } }.awaitAll().flatten()
        }
        println("INFO: consulta de produtos — Estoque lido (tenant $tenantSlug): ${linhas.size} linha(s), ${faixas.size} faixa(s), ${System.currentTimeMillis() - inicio} ms")
        return linhas
    }

    /** Estoque PRÓPRIO (TIPO='P', sem parceiro) com algum saldo — terceiro não é saldo do armazém. */
    private suspend fun lerFaixa(tenantSlug: String, de: Int, ate: Int): List<LinhaEstoque> {
        val criterio = "CODPROD >= $de AND CODPROD <= $ate AND CODPARC = 0 AND TIPO = 'P' AND (ESTOQUE <> 0 OR RESERVADO <> 0)"
        suspend fun ler(campos: List<String>) = SankhyaLoadRecordsClient.parseRows(
            SankhyaLoadRecordsClient.loadRecords(tenantSlug, LoadRecordsRequest(entityName = "Estoque", fields = campos, criteriaExpression = criterio)),
            campos,
        )
        val rows = if (tenantSlug in semNomes) {
            ler(CAMPOS_BASE)
        } else {
            try {
                ler(CAMPOS_COM_NOMES)
            } catch (e: Exception) {
                // Sem permissão nas ligações: segue só com os códigos de empresa/local.
                println("AVISO: consulta de produtos — Estoque com nomes recusado (tenant $tenantSlug), seguindo só com códigos: ${e.message}")
                semNomes += tenantSlug
                ler(CAMPOS_BASE)
            }
        }
        return rows.mapNotNull { r ->
            LinhaEstoque(
                codprod = r["CODPROD"]?.toIntOrNull() ?: return@mapNotNull null,
                codemp = r["CODEMP"]?.toIntOrNull(),
                empresa = r["Empresa.NOMEFANTASIA"]?.trim()?.takeIf { it.isNotEmpty() },
                codlocal = r["CODLOCAL"]?.toIntOrNull(),
                local = r["LocalFinanceiro.DESCRLOCAL"]?.trim()?.takeIf { it.isNotEmpty() },
                controle = r["CONTROLE"],
                estoque = r["ESTOQUE"].numero(),
                reservado = r["RESERVADO"].numero(),
            )
        }
    }

    private fun String?.numero(): Double = this?.trim()?.replace(",", ".")?.toDoubleOrNull() ?: 0.0
}
