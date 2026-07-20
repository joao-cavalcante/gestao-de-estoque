package wms.backend.separacao

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import wms.backend.erp.LoadRecordsRequest
import wms.backend.erp.SankhyaDbExplorerClient
import wms.backend.erp.SankhyaLoadRecordsClient
import wms.backend.erp.SankhyaSpClient
import wms.backend.configconferencia.ConfigConferenciaRepository
import wms.backend.produtos.ProdutoCatalogoRepository
import wms.backend.tarefas.TarefaSyncService
import wms.backend.tarefas.TarefasRepository
import java.math.BigDecimal
import java.security.MessageDigest
import java.util.UUID

data class IniciarSeparacaoResultado(val sessaoId: UUID, val status: String)

/**
 * Orquestra o início da separação: responde IMEDIATAMENTE com o id da
 * sessão (criação local, sem tocar o Sankhya) e carrega os itens em
 * background — resolve o gargalo relatado no projeto base, onde a tela
 * inteira ficava esperando de 8 a 11 idas ao Sankhya, várias encadeadas.
 *
 * ItemNota busca primeiro (precisa da lista de CODPROD da nota); BAR e VOA
 * rodam em paralelo entre si logo depois — ambos agora são CACHE-FIRST (ver
 * wms.backend.produtos.ProdutoCatalogoRepository): só batem no Sankhya pro
 * que faltou no cache local, não pra nota inteira. BAR tem sync incremental
 * periódico (DTALTER/DHALTER, ProdutoCatalogoSyncWorker); VOA não tem campo
 * de auditoria, então é cache sob demanda sem TTL (mesmo espírito de
 * ProdutoImagemService). O NUCCO (só pra resolver o cache de config) NÃO
 * bate mais no Sankhya — já vem sincronizado localmente pela Fila de Tarefas
 * (TarefaSyncService.FIELDS inclui TipoOperacao.NUCCO desde que o espelho de
 * Tipos de Operação passou a depender dele — ver TarefasRepository.buscarNuccoLocal),
 * então é uma leitura local instantânea. A ConfiguracaoConferencia também é
 * leitura local — vem do mirror completo que ConfigConferenciaSyncWorker já
 * mantém fresco (ver wms.backend.configconferencia).
 *
 * Código de barras (VOA + BAR + EST, mesma lógica do projeto base) é
 * resolvido junto — ver [montarCodigosBarra]. UMAs de peso continuam de
 * fora por ora — não fazem parte do que foi pedido.
 */
object SeparacaoService {
    private val escopo = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val FIELDS_ITEM = listOf(
        "SEQUENCIA", "CODPROD", "CODVOL", "CONTROLE", "QTDNEG", "QTDENTREGUE",
        "Produto.DESCRPROD", "Produto.COMPLDESC", "Produto.MARCA", "Produto.REFERENCIA",
        // TIPCONTEST='L' = lote (digitação livre); LISCONTEST = lista de
        // controles pré-cadastrados (separados por linha) pro produto —
        // define se o campo de controle na bipagem vira <select> ou <input>.
        "Produto.TIPCONTEST", "Produto.LISCONTEST",
    )
    private val FIELDS_VOA = listOf("CODPROD", "CODVOL", "CONTROLE", "DIVIDEMULTIPLICA", "QUANTIDADE", "CODBARRA")
    private val FIELDS_BAR = listOf("CODPROD", "CODVOL", "CODBARRA")

    fun iniciar(tenantSlug: String, tenantId: UUID, nunota: Long): IniciarSeparacaoResultado {
        val sessaoId = try {
            SeparacaoRepository.criarSessao(tenantId, nunota)
        } catch (e: SessaoJaAtivaException) {
            return IniciarSeparacaoResultado(e.sessaoId, SeparacaoStatus.CARREGANDO)
        }

        escopo.launch { carregarEmBackground(tenantSlug, tenantId, sessaoId, nunota) }

        return IniciarSeparacaoResultado(sessaoId, SeparacaoStatus.CARREGANDO)
    }

    private suspend fun carregarEmBackground(tenantSlug: String, tenantId: UUID, sessaoId: UUID, nunota: Long) {
        try {
            // Abre o cabeçalho de conferência DE VERDADE no Sankhya — mesma SP
            // nativa usada pelo projeto base (`ConferenciaSP.salvarCabecalhoConferencia`,
            // contrato lido do código-fonte real, não adivinhado). Sem isto, o
            // Sankhya nunca sai de STATUSCONFERENCIA vazio/AC, e o job de sync
            // (TarefaSyncService) mantém a tarefa em 'aguardando' pra sempre.
            SankhyaSpClient.chamar(
                tenantSlug,
                "ConferenciaSP.salvarCabecalhoConferencia",
                mapOf(
                    "nuNota" to JsonPrimitive(nunota),
                    "iniciarRecontagem" to JsonPrimitive(false),
                ),
            )

            // Itens primeiro — BAR/VOA agora são cache-first (ver
            // ProdutoCatalogoRepository) e precisam da lista de CODPROD da nota
            // pra checar o cache local em lote, então não dá mais pra rodar em
            // paralelo com buscarItens como antes. NUCCO é leitura local, roda
            // no meio sem bloquear nada.
            val itens = buscarItens(tenantSlug, nunota)
            val nucco = withContext(Dispatchers.IO) { TarefasRepository.buscarNuccoLocal(tenantId, nunota) }
            val codprods = itens.map { it.codprod }.distinct()
            val voaJob = escopo.async { buscarVoa(tenantSlug, tenantId, codprods) }
            val barJob = escopo.async { buscarBar(tenantSlug, tenantId, codprods) }
            val voaRows = voaJob.await()
            val barRows = barJob.await()

            // Config já sincronizada localmente (ver ConfigConferenciaSyncWorker,
            // atualiza todo NUCCO do tenant a cada 15min) — leitura local, sem
            // chamada própria ao Sankhya (substitui o antigo ConfigConferenciaCacheRepository,
            // que mantinha um segundo cache com só 2 campos e seu próprio TTL/refresh).
            val configDetalhe = nucco?.let { n -> withContext(Dispatchers.IO) { ConfigConferenciaRepository.buscarPorNucco(tenantId, n) } }
            if (nucco != null && configDetalhe == null) {
                // Mirror (ConfigConferenciaSyncWorker) ainda não sincronizou este NUCCO — cai no
                // default "A", mas isso PRECISA aparecer no log: sem isto é um erro silencioso
                // (comportamento de busca de código de barras errado sem nenhum aviso).
                println("AVISO: Config Conferência do NUCCO $nucco não encontrada no mirror local (tenant $tenantId) — usando default BUSCARCODBARRAPOR=A")
            }
            val buscarCodigoBarraPor = configDetalhe?.campos?.get("BUSCARCODBARRAPOR")?.trim()?.takeIf { it.isNotEmpty() } ?: "A"

            // EST é sempre ao vivo (estoque muda o tempo todo, nunca cacheado)
            // e só é buscado quando a config realmente pede busca por
            // endereço/estoque — mesma condição do projeto base.
            val estRows = if (buscarCodigoBarraPor == "A" || buscarCodigoBarraPor == "E") {
                buscarEst(tenantSlug, nunota)
            } else {
                emptyList()
            }

            val codigosBarra = montarCodigosBarra(barRows, voaRows, estRows)
            val fingerprint = calcularFingerprint(itens)

            withContext(Dispatchers.IO) {
                SeparacaoRepository.salvarItens(tenantId, sessaoId, itens)
                SeparacaoRepository.salvarCodigosBarra(tenantId, sessaoId, codigosBarra)
                SeparacaoRepository.marcarPronta(tenantId, sessaoId, fingerprint, buscarCodigoBarraPor)
            }

            // Resync imediato (não espera o próximo ciclo do worker pool) —
            // é o que faz app.tarefas.status_operacional virar 'andamento' na
            // hora, refletindo que a conferência já foi aberta no Sankhya.
            try {
                TarefaSyncService.sincronizarTenant(tenantSlug, tenantId)
            } catch (e: Exception) {
                // Não falha a sessão de separação por isto — o worker pool
                // (ciclo normal) pega essa reconciliação de qualquer forma.
            }
        } catch (e: Throwable) {
            // Throwable (não só Exception) de propósito: um StackOverflowError
            // ou qualquer outro Error aqui NÃO PODE deixar a sessão presa em
            // 'carregando' pra sempre — o operador ficaria vendo o loading
            // eternamente sem nenhum diagnóstico. Sempre marca erro.
            withContext(Dispatchers.IO) {
                SeparacaoRepository.marcarErro(tenantId, sessaoId, e.message ?: e::class.simpleName ?: "erro desconhecido")
            }
        }
    }

    private suspend fun buscarItens(tenantSlug: String, nunota: Long): List<ItemParaSalvar> {
        val raw = SankhyaLoadRecordsClient.loadRecords(
            tenantSlug,
            LoadRecordsRequest(
                entityName = "ItemNota",
                fields = FIELDS_ITEM,
                criteriaExpression = "NUNOTA = $nunota",
                orderByExpression = "SEQUENCIA ASC",
            ),
        )
        val rows = SankhyaLoadRecordsClient.parseRows(raw, FIELDS_ITEM)
        return rows.mapNotNull { r ->
            val sequencia = r["SEQUENCIA"]?.toIntOrNull() ?: return@mapNotNull null
            val codprod = r["CODPROD"]?.toIntOrNull() ?: return@mapNotNull null
            val dadosJson = buildJsonObject {
                FIELDS_ITEM.forEach { campo -> put(campo, r[campo]) }
            }.toString()
            ItemParaSalvar(
                sequencia = sequencia,
                codprod = codprod,
                controle = r["CONTROLE"]?.trim()?.takeIf { it.isNotEmpty() } ?: " ",
                codvol = r["CODVOL"],
                qtdNeg = r["QTDNEG"].parseBigDecimalBr() ?: BigDecimal.ZERO,
                qtdEntregue = r["QTDENTREGUE"].parseBigDecimalBr() ?: BigDecimal.ZERO,
                dadosJson = dadosJson,
            )
        }
    }

    /**
     * VOA não tem campo de auditoria (TGFVOA sem DHALTER) — cache pra sempre, sem TTL,
     * populado sob demanda (mesmo espírito de ProdutoImagemService). Sem sync periódico:
     * "não achou local" é sempre tratado como "nunca foi buscado", nunca "confirmado vazio" —
     * aceito, é dado opcional (nem todo produto tem unidade alternativa) e a busca ao vivo já é
     * escopada só pelos produtos da nota, igual sempre foi.
     */
    private suspend fun buscarVoa(tenantSlug: String, tenantId: UUID, codprods: List<Int>): List<Map<String, String?>> {
        if (codprods.isEmpty()) return emptyList()

        val cache = withContext(Dispatchers.IO) { ProdutoCatalogoRepository.buscarVoaPorCodprods(tenantId, codprods) }
        val faltando = codprods - cache.mapNotNull { it["CODPROD"]?.toIntOrNull() }.toSet()
        if (faltando.isEmpty()) return cache

        val raw = SankhyaLoadRecordsClient.loadRecords(
            tenantSlug,
            LoadRecordsRequest(
                entityName = "VolumeAlternativo",
                fields = FIELDS_VOA,
                criteriaExpression = "CODPROD IN (${faltando.joinToString(",")})",
            ),
        )
        val aoVivo = SankhyaLoadRecordsClient.parseRows(raw, FIELDS_VOA)
        if (aoVivo.isNotEmpty()) withContext(Dispatchers.IO) { ProdutoCatalogoRepository.upsertVoa(tenantId, aoVivo) }
        return cache + aoVivo
    }

    /**
     * BAR tem sync periódico (ver ProdutoCatalogoSyncWorker, DHALTER) — diferente da VOA, "não
     * achou local" só é tratado como miss de verdade pra CODPROD que o sync ainda nem viu (produto
     * criado há pouco, antes do último ciclo). Pra CODPROD que o Produto-cache já conhece, ausência
     * de código de barras é confiável (o sync já teria trazido se existisse) — evita ficar batendo
     * ao vivo pra sempre em produto que genuinamente não tem código de barras cadastrado.
     */
    private suspend fun buscarBar(tenantSlug: String, tenantId: UUID, codprods: List<Int>): List<Map<String, String?>> {
        if (codprods.isEmpty()) return emptyList()

        val (cache, produtosConhecidos) = withContext(Dispatchers.IO) {
            ProdutoCatalogoRepository.buscarBarPorCodprods(tenantId, codprods) to
                ProdutoCatalogoRepository.mapaDtalterProdutos(tenantId).keys
        }
        val comBarrasCacheadas = cache.mapNotNull { it["CODPROD"]?.toIntOrNull() }.toSet()
        val faltando = codprods.filter { it !in comBarrasCacheadas && it !in produtosConhecidos }
        if (faltando.isEmpty()) return cache

        val raw = SankhyaLoadRecordsClient.loadRecords(
            tenantSlug,
            LoadRecordsRequest(
                entityName = "CodigoBarras",
                fields = FIELDS_BAR,
                criteriaExpression = "CODPROD IN (${faltando.joinToString(",")})",
            ),
        )
        val aoVivo = SankhyaLoadRecordsClient.parseRows(raw, FIELDS_BAR)
        if (aoVivo.isNotEmpty()) withContext(Dispatchers.IO) { ProdutoCatalogoRepository.upsertBar(tenantId, aoVivo) }
        return cache + aoVivo
    }

    private val PRODUTOS_DA_NOTA = "CODPROD IN (SELECT CODPROD FROM TGFITE WHERE NUNOTA = %d)"

    /** EST é sempre ao vivo (SQL direto, nunca via loadRecords/cache) — estoque muda o tempo todo. */
    private suspend fun buscarEst(tenantSlug: String, nunota: Long): List<Map<String, String?>> {
        val sql = "SELECT DISTINCT CODPROD, CONTROLE, CODBARRA FROM TGFEST " +
            "WHERE ${PRODUTOS_DA_NOTA.format(nunota)} AND CODBARRA IS NOT NULL"
        return SankhyaDbExplorerClient.executarQuery(tenantSlug, sql)
    }

    /**
     * Monta a lista final de códigos de barras — mesma prioridade/ordem do
     * projeto base: BAR (genérico do produto, controle=' ' vale pra
     * qualquer lote), depois VOA (específico por unidade/lote), depois EST
     * (visto no estoque físico, deduplicado por produto+controle+código).
     */
    private fun montarCodigosBarra(
        barRows: List<Map<String, String?>>,
        voaRows: List<Map<String, String?>>,
        estRows: List<Map<String, String?>>,
    ): List<CodigoBarraParaSalvar> {
        val codigos = mutableListOf<CodigoBarraParaSalvar>()

        for (b in barRows) {
            val codigoBarra = b["CODBARRA"]?.trim()?.takeIf { it.isNotEmpty() } ?: continue
            val codprod = b["CODPROD"]?.toIntOrNull() ?: continue
            codigos += CodigoBarraParaSalvar(
                codigoBarra = codigoBarra,
                codprod = codprod,
                codvol = b["CODVOL"]?.trim()?.takeIf { it.isNotEmpty() },
                controle = " ",
                origem = "BAR",
            )
        }

        for (v in voaRows) {
            val codigoBarra = v["CODBARRA"]?.trim()?.takeIf { it.isNotEmpty() } ?: continue
            val codprod = v["CODPROD"]?.toIntOrNull() ?: continue
            codigos += CodigoBarraParaSalvar(
                codigoBarra = codigoBarra,
                codprod = codprod,
                codvol = v["CODVOL"]?.trim()?.takeIf { it.isNotEmpty() },
                controle = v["CONTROLE"]?.trim()?.takeIf { it.isNotEmpty() } ?: " ",
                origem = "VOA",
                quantidade = v["QUANTIDADE"].parseBigDecimalBr(),
                divideMultiplica = v["DIVIDEMULTIPLICA"]?.trim()?.takeIf { it.isNotEmpty() },
            )
        }

        val vistos = mutableSetOf<Triple<Int, String, String>>()
        for (e in estRows) {
            val codigoBarra = e["CODBARRA"]?.trim()?.takeIf { it.isNotEmpty() } ?: continue
            val codprod = e["CODPROD"]?.toIntOrNull() ?: continue
            val controle = e["CONTROLE"]?.trim()?.takeIf { it.isNotEmpty() } ?: " "
            if (!vistos.add(Triple(codprod, controle, codigoBarra))) continue
            codigos += CodigoBarraParaSalvar(
                codigoBarra = codigoBarra,
                codprod = codprod,
                codvol = null,
                controle = controle,
                origem = "EST",
            )
        }

        return codigos
    }

    /** Hash estável dos itens (ordenados) — detecta divergência sem precisar comparar campo a campo. */
    private fun calcularFingerprint(itens: List<ItemParaSalvar>): String {
        val base = itens
            .sortedBy { it.sequencia }
            .joinToString("\n") { "${it.sequencia}|${it.codprod}|${it.controle}|${it.qtdNeg.toPlainString()}" }
        val digest = MessageDigest.getInstance("SHA-256").digest(base.toByteArray())
        return digest.joinToString("") { "%02x".format(it) }
    }

    private fun String?.parseBigDecimalBr(): BigDecimal? = this?.trim()?.replace(",", ".")?.toBigDecimalOrNull()
}
