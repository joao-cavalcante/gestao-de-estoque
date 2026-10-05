package wms.backend.separacao

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import wms.backend.erp.LoadRecordsRequest
import wms.backend.erp.SankhyaDbExplorerClient
import wms.backend.erp.SankhyaLoadRecordsClient
import wms.backend.erp.SankhyaSpClient
import wms.backend.configconferencia.ConfigConferenciaRepository
import wms.backend.liberacaocorte.LiberacaoCorteService
import wms.backend.produtos.ProdutoCatalogoRepository
import wms.backend.tarefas.TarefaSyncService
import wms.backend.tarefas.TarefasRepository
import java.math.BigDecimal
import java.security.MessageDigest
import java.time.Duration
import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger
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
/** Etapa atual do `finalizar` por sessão, em memória — lida por GET /sessoes/{id}/finalizacao-progresso. */
object FinalizacaoProgresso {
    data class Estado(val fase: String, val feitos: Int, val total: Int)
    private val estados = java.util.concurrent.ConcurrentHashMap<UUID, Estado>()
    fun atualizar(sessaoId: UUID, fase: String, feitos: Int = 0, total: Int = 0) { estados[sessaoId] = Estado(fase, feitos, total) }
    fun limpar(sessaoId: UUID) { estados.remove(sessaoId) }
    fun obter(sessaoId: UUID): Estado? = estados[sessaoId]

    /**
     * Último resultado de conclusão por sessão, guardado por 15 min. Bug real (nota 58213): o servidor
     * finalizou tudo no Sankhya, mas a resposta não chegou ao tablet (queda de conexão) — a tela ficou
     * presa em "Enviando…" e o operador reabriu a nota achando que não tinha ido.
     */
    private data class Guardado(val conclusao: ConclusaoDto, val em: Instant)
    private val resultados = java.util.concurrent.ConcurrentHashMap<UUID, Guardado>()
    private val RETENCAO: Duration = Duration.ofMinutes(15)

    fun registrarConclusao(sessaoId: UUID, conclusao: ConclusaoDto) {
        val limite = Instant.now().minus(RETENCAO)
        resultados.entries.removeIf { it.value.em.isBefore(limite) }
        resultados[sessaoId] = Guardado(conclusao, Instant.now())
    }

    fun conclusao(sessaoId: UUID): ConclusaoDto? =
        resultados[sessaoId]?.takeIf { it.em.isAfter(Instant.now().minus(RETENCAO)) }?.conclusao
}

/** Quantos salvarItemConferido em voo ao mesmo tempo no finalizar. */
private const val CONCORRENCIA_ENVIO_ITENS = 3

object SeparacaoService {
    private val escopo = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val FIELDS_ITEM = listOf(
        "SEQUENCIA", "CODPROD", "CODVOL", "CONTROLE", "QTDNEG", "QTDENTREGUE", "QTDCONFERIDA",
        // PENDENTE sozinho NÃO é o critério real de "precisa conferência" — numa
        // nota de venda ele some atrás do próprio OR do critério nativo
        // (this.PENDENTE = 'S' OR EXISTS(...TIPMOV IN ('V','C','D','E','T','Q','L')...)),
        // que é quase sempre verdadeiro. O filtro de verdade, capturado ao vivo
        // da tela nativa (DatasetSP.loadRecords, ItensPedidoConferenciaCrudListener,
        // nota 57500): (QTDNEG - QTDENTREGUE - QTDCONFERIDA) > 0 — é isso que
        // distingue item já resolvido (liberado/conferido) de item que ainda
        // precisa de recontagem de verdade. Mantido como dado auxiliar.
        "PENDENTE",
        "Produto.DESCRPROD", "Produto.COMPLDESC", "Produto.MARCA", "Produto.REFERENCIA",
        // TIPCONTEST='L' = lote (digitação livre); LISCONTEST = lista de
        // controles pré-cadastrados (separados por linha) pro produto —
        // define se o campo de controle na bipagem vira <select> ou <input>.
        "Produto.TIPCONTEST", "Produto.LISCONTEST",
        // Conferência por etapa (V29) — 1 Secos | 2 Resfriados | 3 Congelados.
        "Produto.AD_TIPOSEPARACAO",
        // Mesmo filtro do critério nativo: produto marcado EXCLUIRCONF='S' nunca
        // entra na conferência.
        "Produto.EXCLUIRCONF",
    )

    /** AD_TIPOSEPARACAO cru ("2" / "2.0" / vazio) → 1..3, default 1 (Secos). */
    private fun parseTipoSeparacao(raw: String?): Short {
        val n = raw?.trim()?.takeIf { it.isNotEmpty() }
            ?.let { it.toIntOrNull() ?: it.toDoubleOrNull()?.toInt() }
        return if (n != null && n in 1..3) n.toShort() else 1
    }
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

    /**
     * Modo simplificado de volume (sem dimensão nenhuma) — confirmado ao vivo
     * nesta sessão contra o tenant Negri: ConferenciaSP.buscarVolumes só
     * enxerga o modo detalhado (TGFVCF), fica sempre vazio aqui. A leitura
     * do total é uma consulta comum via DatasetSP em CabecalhoConferencia.
     */
    suspend fun buscarQtdVolumes(tenantSlug: String, nuconf: Int): Int {
        val fields = listOf("NUCONF", "QTDVOL")
        val raw = SankhyaLoadRecordsClient.loadRecords(
            tenantSlug,
            LoadRecordsRequest(entityName = "CabecalhoConferencia", fields = fields, criteriaExpression = "NUCONF = $nuconf"),
        )
        return SankhyaLoadRecordsClient.parseRows(raw, fields).firstOrNull()?.get("QTDVOL")?.toIntOrNull() ?: 0
    }


    private suspend fun carregarEmBackground(tenantSlug: String, tenantId: UUID, sessaoId: UUID, nunota: Long) {
        try {
            // Abre o cabeçalho de conferência DE VERDADE no Sankhya — mesma SP
            // nativa usada pelo projeto base (`ConferenciaSP.salvarCabecalhoConferencia`,
            // contrato lido do código-fonte real, não adivinhado). Sem isto, o
            // Sankhya nunca sai de STATUSCONFERENCIA vazio/AC, e o job de sync
            // (TarefaSyncService) mantém a tarefa em 'aguardando' pra sempre.
            // iniciarRecontagem=true quando já existe uma sessão CONCLUIDA
            // anterior pra essa nota — sinaliza pro Sankhya que isto é uma
            // recontagem (item negado voltando), não a primeira conferência.
            // Confirmado ao vivo (nota 57500): mandando sempre `false`, o
            // Sankhya tratava a reabertura como conferência do zero e não
            // aplicava os ajustes de QTDCONFERIDA/QTDNEG do corte — a
            // recontagem vinha com TODOS os itens da nota como pendentes,
            // não só o que precisava ser reconferido.
            val ehRecontagem = withContext(Dispatchers.IO) { SeparacaoRepository.houveSessaoAnteriorConcluida(tenantId, nunota) }
            // "Recontagem" só porque houve sessão concluída NÃO basta: confere no Sankhya se a última
            // conferência da nota está mesmo esperando recontagem. Bug real (nota 58291, 25/09): a nota
            // foi reaberta 5 s depois de finalizada (toque duplo / tela sem atualizar) e o
            // salvarCabecalhoConferencia(iniciarRecontagem=true) abaixo criou a conferência 582 SEM
            // item nenhum, que ficou "em andamento" no Sankhya e prendeu a nota na fila.
            if (ehRecontagem) {
                // Falha na consulta NÃO bloqueia (segue como antes) — só não dá pra proteger desta vez.
                val (ultimoNuconf, statusUltima) = runCatching {
                    val n = buscarNuconf(tenantSlug, nunota)
                    n to n?.let { statusConferencia(tenantSlug, it)?.trim() }
                }.onFailure { println("AVISO: não deu pra conferir o status da última conferência (nunota $nunota): ${it.message}") }
                    .getOrDefault(null to null)
                when (statusUltima) {
                    "F", "D", "RF", "RD" -> throw IllegalStateException(
                        "Conferência já finalizada no Sankhya (NUCONF $ultimoNuconf) — não há o que recontar. Volte à fila.",
                    )
                    "C" -> throw IllegalStateException(
                        "Conferência aguardando liberação de corte (NUCONF $ultimoNuconf) — resolva na tela Liberação de Corte.",
                    )
                }
            }
            // Recontagem continua a numeração de volumes da conferência anterior (V44). Tem que ser
            // calculado ANTES do salvarCabecalhoConferencia: ele cria o NUCONF novo e o sync passa a
            // apontar pra ele, e a base sai da sessão que a tarefa ainda aponta.
            if (ehRecontagem) {
                withContext(Dispatchers.IO) {
                    SeparacaoRepository.marcarRecontagem(tenantId, sessaoId, SeparacaoRepository.volumeBaseParaRecontagem(tenantId, nunota))
                }
            }
            // Conferência do zero (nota nova, ou excluída e reenviada no Sankhya): decisões
            // de liberação de corte de uma conferência anterior NÃO valem mais. Sem isto, se o
            // sync não pegou a exclusão a tempo (limparDecisoesLiberacao só roda lá), os itens
            // pesáveis liberados em silêncio antes viram "silenciosos" e somem dos pendentes
            // — caso real, nota 57529 (Brie e Mussarela não apareciam pro operador).
            if (!ehRecontagem) {
                withContext(Dispatchers.IO) { SeparacaoRepository.limparDecisoesLiberacao(tenantId, nunota) }
            }
            SankhyaSpClient.chamar(
                tenantSlug,
                "ConferenciaSP.salvarCabecalhoConferencia",
                mapOf(
                    "nuNota" to JsonPrimitive(nunota),
                    "iniciarRecontagem" to JsonPrimitive(ehRecontagem),
                ),
            )

            // NUCONF — necessário pra rotinas nativas usadas depois (volume,
            // finalização): ConferenciaSP.salvarVolumeSimplificado e
            // ConferenciaSP.salvarItemConferido exigem esse número, e ele só é
            // atribuído pelo Sankhya na chamada acima. Confirmado ao vivo nesta
            // sessão: CabecalhoConferencia.NUNOTAORIG é a forma de resolvê-lo
            // (mesma técnica que TarefaSyncService já usa, mas persistindo em
            // vez de descartar).
            val nuconf = buscarNuconf(tenantSlug, nunota)
            if (nuconf != null) {
                withContext(Dispatchers.IO) { SeparacaoRepository.salvarNuconf(tenantId, sessaoId, nuconf) }
            }

            // Itens primeiro — BAR/VOA agora são cache-first (ver
            // ProdutoCatalogoRepository) e precisam da lista de CODPROD da nota
            // pra checar o cache local em lote, então não dá mais pra rodar em
            // paralelo com buscarItens como antes. NUCCO é leitura local, roda
            // no meio sem bloquear nada.
            val codprodsNegados = withContext(Dispatchers.IO) { SeparacaoRepository.buscarCodprodsNegados(tenantId, nunota) }
            val carga = buscarItensDaNota(tenantSlug, nunota, nuconf, codprodsNegados, recontagem = ehRecontagem)
            registrarDiagnostico(tenantId, sessaoId, nunota, nuconf, "abertura", ehRecontagem, carga)
            val itensBrutos = carga.itens.filter { it.sequencia in carga.pendentes }

            // Item já LIBERADO numa rodada de corte anterior: o Sankhya devolve
            // ele na recontagem com o QTDNEG original (15), não com o que foi
            // aceito (5). Sobrescreve QTDNEG pela quantidade aceita e marca
            // silencioso=true — esse item nunca aparece pro operador (nem
            // Pendentes, nem Conferidos, ver SeparacaoRepository.listarItens),
            // só entra no finalizar() por trás (leitura já gravada abaixo) pra
            // subir a quantidade aceita pro Sankhya. O item negado (o que
            // precisa de ação de verdade) não é afetado por isto.
            val decisoesLiberadas = withContext(Dispatchers.IO) { SeparacaoRepository.buscarDecisoesLiberadasComQtd(tenantId, nunota) }
            val qtdLiberadaPorChave = decisoesLiberadas.associate { (it.codprod to it.controle) to it.qtdLiberada }
            val itens = itensBrutos.map { item ->
                val qtdLiberada = qtdLiberadaPorChave[item.codprod to item.controle]
                if (qtdLiberada != null) item.copy(qtdNeg = qtdLiberada, silencioso = true) else item
            }
            val nucco = withContext(Dispatchers.IO) { TarefasRepository.buscarNuccoLocal(tenantId, nunota) }
            // V50 — tolerância de peso do NUCCO congelada na sessão (tela + auto-liberação de corte usam
            // esta cópia). Ex.: venda = a maior sem limite / a menor 5%; compra = 0% nos dois sentidos.
            withContext(Dispatchers.IO) {
                val tol = wms.backend.configconferencia.ConferenciaToleranciaRepository.buscar(tenantId, nucco)
                SeparacaoRepository.salvarToleranciaPeso(
                    tenantId, sessaoId, tol.acimaPct?.let { BigDecimal.valueOf(it) }, tol.abaixoPct?.let { BigDecimal.valueOf(it) },
                )
            }
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
            // 'D' = permitido (fica divergente); qualquer outro valor/ausência = bloqueia o excesso —
            // fail-safe pro lado mais restritivo quando a config ainda não sincronizou.
            val qtdAmaior = configDetalhe?.campos?.get("QTDAMAIOR")?.trim()?.takeIf { it.isNotEmpty() }
            // 'N' = não obter peso pela balança (default quando ausente — mesmo
            // comportamento do projeto base, que só ativa o fluxo de peso quando
            // a config explicitamente pede).
            val obterQtdBalanca = configDetalhe?.campos?.get("OBTERQTDBALANCA")?.trim()?.takeIf { it.isNotEmpty() } ?: "N"
            // 'D' = permitido (mesma convenção de QTDAMAIOR); ausência/qualquer
            // outro valor = bloqueia produto fora do pedido — fail-safe restritivo.
            val produtosForaPed = configDetalhe?.campos?.get("PRODUTOSFORAPED")?.trim()?.takeIf { it.isNotEmpty() }
            // 'S' = após finalizar a conferência, oferecer faturamento (escolha de TOP) —
            // fluxo portado do fila-de-conferencia. Ausente/qualquer outro valor = sem faturamento.
            val fatAoConcluir = configDetalhe?.campos?.get("FATAOCONCLUIR")?.trim()?.takeIf { it.isNotEmpty() }
            // 'N'/ausente = não usa formação de volumes; 'S'/'T'/'D' = exige volume > 0
            // pra finalizar/concluir etapa (ver SeparacaoRepository.exigeVolume).
            //
            // RECONTAGEM (ehRecontagem, calculado acima) NUNCA exige formação de
            // volumes — regra de negócio própria da recontagem, independente do
            // que a CCO pede pra conferência normal: o volume já foi formado (ou
            // não) na conferência original, recontar não deve travar nisso de
            // novo. Congela `null` aqui, não só esconde na UI — exigeVolume()
            // lê este campo direto do banco.
            val formacaoVolumes = if (ehRecontagem) {
                null
            } else {
                configDetalhe?.campos?.get("FORMACAOVOLUMES")?.trim()?.takeIf { it.isNotEmpty() }
            }

            // CCO "Comportamento da interface" — gateiam painéis da tela de
            // conferência (front). Só 'N' explícito esconde; ausente/'S'/outro
            // = mostra. No legado só EXIBIRPROD/EXIBIRIMGPROD chegavam a gatear;
            // aqui portamos o conjunto que mapeia pra UI que o WMS tem (V28).
            fun campoExibicao(nome: String) = configDetalhe?.campos?.get(nome)?.trim()?.takeIf { it.isNotEmpty() }
            val exibirProd = campoExibicao("EXIBIRPROD")
            val exibirQtd = campoExibicao("EXIBIRQTD")
            val exibirProdConf = campoExibicao("EXIBIRPRODCONF")
            val exibirQtdConf = campoExibicao("EXIBIRQTDCONF")
            val exibirImgProd = campoExibicao("EXIBIRIMGPROD")

            // Conferência segmentada NÃO vem do Sankhya — é um módulo por-tenant
            // (tenancy.erp_connections.modulos), ligado só pela plataforma pro
            // único cliente que usa. Resolvido aqui e congelado na sessão pra
            // ligar/desligar o módulo não mudar a regra de uma conferência já aberta.
            //
            // RECONTAGEM é sempre etapa única, mesmo em tenant com o módulo
            // ligado: não tem Frios/Refrigerados/Secos como etapas independentes,
            // não tem ordem, não tem "última etapa" — é tratada como uma
            // conferência normal de etapa só. Força false aqui (não faz sentido
            // seguir a lógica de progressão de etapas da conferência normal pra
            // recontagem), o que também impede semearEtapas() de criar etapas
            // pra ela mais abaixo, e o guard de "há etapas pendentes" em
            // finalizar() nem entra em jogo (conferenciaSegmentada=false).
            // + por TOP (V49): o TOP da nota pode desligar as etapas (ex.: conferência de ENTRADA/compra).
            val conferenciaSegmentada = if (ehRecontagem) {
                false
            } else {
                withContext(Dispatchers.IO) {
                    wms.backend.tenancy.TenantRepository.modulosHabilitados(tenantId)
                        .contains(wms.backend.tenancy.Modulos.CONFERENCIA_SEGMENTADA) &&
                        wms.backend.tipooperacao.TipoOperacaoRepository.usaConferenciaPorEtapa(
                            tenantId, TarefasRepository.codTipOperPorNunota(tenantId, listOf(nunota))[nunota],
                        )
                }
            }

            val codvolProdutoPorCodprod = runCatching { buscarCodvolProduto(tenantSlug, itens.map { it.codprod }.distinct()) }
                .onFailure { println("AVISO: falha ao ler TGFPRO.CODVOL (tenant $tenantId, nunota $nunota): ${it.message}") }
                .getOrDefault(emptyMap())

            // Pesável (regra central: unidade TGFVOL.UTILICONFPESO, ou TGFPRO.AD_PESAVEL no tenant
            // com o módulo PESAVEL_POR_PRODUTO — ver RegraPesavel) + UMA só dos pesáveis. Falha aqui
            // não derruba a sessão (peso é aditivo, não bloqueia bipagem por quantidade) — só loga e
            // segue sem peso pra essa sessão.
            val decisorPesavel = runCatching {
                wms.backend.produtos.RegraPesavel.decisor(
                    tenantSlug, tenantId, wms.backend.produtos.RegraPesavel.filtroCodprods(itens.map { it.codprod }),
                )
            }.onFailure { println("AVISO: falha ao decidir produtos pesáveis (tenant $tenantId, nunota $nunota): ${it.message}") }
                .getOrDefault(wms.backend.produtos.RegraPesavel.NENHUM)
            fun itemUsaConfPeso(item: ItemParaSalvar): Boolean = decisorPesavel.pesavel(item.codprod, item.codvol)
            val codprodsPesaveis = itens.filter { itemUsaConfPeso(it) }.map { it.codprod }.distinct()
            val umas = if (codprodsPesaveis.isEmpty()) emptyList() else runCatching { buscarUma(tenantSlug, codprodsPesaveis) }
                .onFailure { println("AVISO: falha ao ler UMA (tenant $tenantId, nunota $nunota): ${it.message}") }
                .getOrDefault(emptyList())

            // EST é sempre ao vivo (estoque muda o tempo todo, nunca cacheado)
            // e só é buscado quando a config realmente pede busca por
            // endereço/estoque — mesma condição do projeto base.
            val estRows = if (buscarCodigoBarraPor == "A" || buscarCodigoBarraPor == "E") {
                buscarEst(tenantSlug, nunota)
            } else {
                emptyList()
            }

            val codigosBarra = montarCodigosBarra(barRows, voaRows, estRows, codvolProdutoPorCodprod)
            val fingerprint = calcularFingerprint(itens)

            // Unidades alternativas (TGFVOA) — match POR LINHA: a linha negociada
            // numa unidade != a de cadastro do produto pega o fator do VOA cujo
            // CODVOL == CODVOL da linha (+ fallback lote-livre). NUNCA fallback
            // por produto (conferencia.helper.ts:262-287). É só p/ display.
            val voaPorChave = montarVoaPorChave(voaRows)

            val itensComPeso = itens.map { item ->
                val enriquecido = enriquecerItem(item, voaPorChave, codvolProdutoPorCodprod, itemUsaConfPeso(item))
                // Item liberado (silencioso): a qtd liberada veio na unidade COMERCIAL da linha (OBSERVACAO
                // da liberação: "1 CX"), mas qtd_neg é na unidade PADRÃO (PE) — converte. Bug real (nota
                // 58363, óleo 381): 1 CX (=20 PE) subia como 1 PE → 0,05 CX no Sankhya.
                if (item.silencioso) {
                    enriquecido.copy(qtdNeg = SeparacaoRepository.comercialParaPadrao(item.qtdNeg, enriquecido.divideMultiplica, enriquecido.fatorConversao))
                } else {
                    enriquecido
                }
            }
            // Qtd liberada já na unidade padrão, por (codprod, controle) — usada na auto-conferência abaixo.
            val qtdLiberadaPadraoPorChave = itensComPeso.filter { it.silencioso }.associate { (it.codprod to it.controle) to it.qtdNeg }

            withContext(Dispatchers.IO) {
                SeparacaoRepository.salvarItens(tenantId, sessaoId, itensComPeso)
                SeparacaoRepository.salvarCodigosBarra(tenantId, sessaoId, codigosBarra)
                SeparacaoRepository.salvarUma(tenantId, sessaoId, umas)
                // Conferência por etapa (V29): semeia uma etapa por tipo de separação
                // presente na nota. Só quando o módulo está ligado pro tenant.
                if (conferenciaSegmentada) {
                    SeparacaoRepository.semearEtapas(tenantId, sessaoId, itensComPeso.map { it.tipoSeparacao }.toSet())
                }
                SeparacaoRepository.marcarPronta(tenantId, sessaoId, fingerprint, buscarCodigoBarraPor, qtdAmaior, obterQtdBalanca, produtosForaPed, conferenciaSegmentada, fatAoConcluir, exibirProd, exibirQtd, exibirProdConf, exibirQtdConf, exibirImgProd, formacaoVolumes)

                // Auto-conferência silenciosa de item já LIBERADO numa rodada de
                // corte anterior (ver LiberacaoCorteService.liberarOuNegar) — o
                // QTDNEG dele já foi sobrescrito acima pela quantidade aceita,
                // então confirmar essa mesma quantidade aqui deixa o item 100%
                // completo (nunca aparece em Pendentes). Pula item que não está
                // mais nesta sessão (troca de código/produto).
                val codprodsDaSessao = itensComPeso.map { it.codprod }.toSet()
                decisoesLiberadas
                    .filter { it.codprod in codprodsDaSessao }
                    .forEach { decisao ->
                        runCatching {
                            SeparacaoRepository.conferirItem(
                                tenantId, sessaoId, decisao.codprod, decisao.controle,
                                qtdLiberadaPadraoPorChave[decisao.codprod to decisao.controle] ?: decisao.qtdLiberada,
                                permitirQtdMaior = true,
                            )
                        }.onFailure {
                            println("AVISO: falha ao auto-conferir item liberado (nunota $nunota, codprod ${decisao.codprod}): ${it.message}")
                        }
                    }
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

    /**
     * Envia os grupos produto+controle ao Sankhya (ConferenciaSP.salvarItemConferido), em PARALELO
     * (até CONCORRENCIA_ENVIO_ITENS por vez): cada chamada leva ~0,75s e o total crescia linear
     * com a nota. Cada item é independente e idempotente ("jaExisteProduto"), então a ordem não
     * importa; a 1ª falha cancela as demais e propaga. Cada grupo é marcado como enviado (V43)
     * assim que o Sankhya confirma — um retry só reenvia o que faltou.
     */
    private suspend fun enviarGruposAoSankhya(
        tenantSlug: String,
        tenantId: UUID,
        sessaoId: UUID,
        nunota: Long,
        nuconf: Int,
        grupos: List<SeparacaoRepository.GrupoConferido>,
    ) {
        FinalizacaoProgresso.atualizar(sessaoId, "itens", 0, grupos.size)
        // Diagnóstico (nota 57568, divergência com quantidade certa): dados do item que vão pro Sankhya.
        val itensPorProduto = withContext(Dispatchers.IO) { SeparacaoRepository.listarItens(tenantId, sessaoId, incluirSilenciosos = true) }
            .groupBy { it.codprod }
        grupos.forEach { g ->
            val it0 = itensPorProduto[g.codprod]?.firstOrNull()
            println(
                "INFO: envio nunota=$nunota codprod=${g.codprod} negociado=${g.qtdNegociada?.toPlainString()} lido=${g.qtdLida?.toPlainString()} " +
                    "enviado=${g.qtdTotal.toPlainString()} linhas=${itensPorProduto[g.codprod]?.size} usaPeso=${it0?.usaConfPeso} " +
                    "comercial=${it0?.unidadeComercial} padrao=${it0?.unidadePadrao} qtdComercial=${it0?.quantidadeComercial}",
            )
        }
        val semaforo = Semaphore(CONCORRENCIA_ENVIO_ITENS)
        val enviados = AtomicInteger(0)
        coroutineScope {
        grupos.map { grupo -> async {
        semaforo.withPermit {
            // Contrato real confirmado AO VIVO (nota 57516 — payload capturado da
            // tela nativa do Sankhya conferindo o mesmo item): NÃO existe parâmetro
            // "codVol" nessa chamada — os 3 tentativas anteriores que inventavam um
            // (722d48f/7d7aace/400f98c, todas revertidas) estavam mandando um campo
            // que a SP nem espera. qtdConf vai na unidade COMERCIAL (o que o
            // operador vê como "Pedido: 1 LT" virou qtdConf="1.000000000", não
            // 0.08333 da unidade padrão) — SeparacaoRepository.listarGruposConferidos
            // já faz essa conversão (exceto pesável, que fica em padrão puro — peso
            // nunca combina com fator/divideMultiplica). Os demais campos
            // (substituirProduto/volume/exigeIdentificadores/codUMA) são os defaults
            // vistos no payload nativo — mantidos fixos até termos evidência de que
            // algum caso real precisa de outro valor.
            val params = buildMap<String, kotlinx.serialization.json.JsonElement> {
                put("nuNota", JsonPrimitive(nunota))
                put("numConf", JsonPrimitive(nuconf))
                put("codBarra", JsonPrimitive(grupo.codigoBarra?.takeIf { it.isNotBlank() } ?: grupo.codprod.toString()))
                put("controle", JsonPrimitive(grupo.controle.trim()))
                put("qtdConf", JsonPrimitive(grupo.qtdTotal))
                put("substituirProduto", JsonPrimitive(false))
                put("volume", JsonPrimitive(""))
                put("exigeIdentificadores", JsonPrimitive("N"))
                put("codUMA", JsonPrimitive(""))
            }
            println("ConferenciaSP.salvarItemConferido tenant=$tenantSlug nunota=${nunota} params=$params")
            SankhyaSpClient.chamar(tenantSlug, "ConferenciaSP.salvarItemConferido", params)
            withContext(Dispatchers.IO) { SeparacaoRepository.marcarGrupoEnviado(tenantId, sessaoId, grupo.codprod, grupo.controle) }
            FinalizacaoProgresso.atualizar(sessaoId, "itens", enviados.incrementAndGet(), grupos.size)
        }
        } }.awaitAll()
        }
    }

    class FinalizarSeparacaoException(message: String) : Exception(message)

    /** O pedido mudou no Sankhya desde a abertura — a sessão já foi corrigida; o operador precisa conferir antes. */
    class PedidoAlteradoException(val sincronizacao: SincronizacaoSankhyaDto) :
        Exception("O pedido foi alterado no Sankhya — confira os itens atualizados antes de concluir.")

    /**
     * Antes de concluir etapa/finalizar: mesma comparação do "Atualizar com Sankhya" (itens que entraram,
     * saíram ou mudaram). Achou diferença → a sessão é corrigida e a conclusão PARA ([PedidoAlteradoException]),
     * pro operador conferir o que mudou. Caso real: nota 61514 (itens que não vieram na abertura).
     * Falha ao consultar o Sankhya NÃO trava a operação — segue e registra o aviso.
     */
    private suspend fun verificarPedidoAtualizado(tenantSlug: String, tenantId: UUID, sessaoId: UUID, contexto: String) {
        // Módulo opt-out: o tenant pode desligar a checagem pra ganhar desempenho (ver Modulos.SEM_VERIFICACAO_PEDIDO).
        val desligada = withContext(Dispatchers.IO) {
            wms.backend.tenancy.TenantRepository.modulosHabilitados(tenantId).contains(wms.backend.tenancy.Modulos.SEM_VERIFICACAO_PEDIDO)
        }
        if (desligada) return
        val sinc = runCatching { sincronizarComSankhya(tenantSlug, tenantId, sessaoId) }
            .onFailure { println("AVISO: verificação do pedido antes de $contexto falhou (sessão $sessaoId) — seguindo sem ela: ${it.message}") }
            .getOrNull() ?: return
        if (sinc.correcoes.isNotEmpty()) {
            println("AVISO: $contexto barrado (sessão $sessaoId) — pedido mudou no Sankhya: ${sinc.correcoes.size} correção(ões)")
            throw PedidoAlteradoException(sinc)
        }
    }

    /**
     * Eventos de confirmação que a TELA NATIVA do Sankhya manda junto de
     * ConferenciaSP.finalizarConferencia quando fecha uma conferência com
     * divergência (confirmado com o usuário — payload real capturado da UI
     * nativa) — cada `$` é um popup que o Sankhya mostraria e a tela nativa
     * confirma sozinha. O `chamar()` de uso geral só manda "clientconfirm";
     * pra ESTE finalizarConferencia especificamente, sem estes eventos o
     * Sankhya fica sem confirmação pra decisões que só ele resolve
     * (PROCEDCORTE/GERARPEDCOMPL etc.), o que é a suspeita mais forte pro
     * "corte maior automático" relatado em conferência de secos divergente.
     */
    // `internal`: a finalização que acontece DEPOIS de uma liberação de corte
    // (LiberacaoCorteService — automática, manual ou revalidação) precisa do
    // MESMO payload; com o confirm genérico o Sankhya fechava a conferência sem
    // cortar os itens a menor que não passaram por liberação (nota 57797:
    // requeijão 17/20 ficou sem corte porque só o queijo a maior pediu liberação).
    internal val CLIENT_EVENT_FINALIZAR_DIVERGENTE = buildJsonObject {
        putJsonObject("clientEventList") {
            putJsonArray("clientEvent") {
                add(buildJsonObject { put("$", "conferencia.lista.produtos.divergentes") })
                add(buildJsonObject { put("$", "client.event.escolha.etiqueta.peso") })
                add(buildJsonObject { put("$", "fila.conferencia.client.event.produtos.divergentes") })
                add(buildJsonObject { put("$", "client.event.produtos.escolha.unidade.mov.armazenamento") })
                add(buildJsonObject { put("$", "client.event.escolha.empresa.local.destino") })
                add(buildJsonObject { put("$", "client.event.produtos.excluidos.conferencia") })
                add(buildJsonObject { put("$", "client.event.volumes.produto.recontado") })
                add(buildJsonObject { put("$", "br.com.sankhya.mgecom.busca.identificador.produto") })
            }
        }
    }

    /**
     * Fecha a conferência DE VERDADE no Sankhya — contrato confirmado ao vivo
     * nesta sessão (tenant Negri, pedido 56510):
     *
     * 1. ConferenciaSP.salvarItemConferido uma vez por grupo produto+controle
     *    (substitui o insert manual em TGFCOI2 do projeto base — essa rotina
     *    já é "grava uma vez, valor final": chamar de novo pro mesmo
     *    produto+controle só devolve {jaExisteProduto:true}, sem duplicar).
     * 2. ConferenciaSP.cortar(nuNota) — OBRIGATÓRIA: sozinha já corta estoque
     *    E fecha TGFCON2 (STATUS='F', DHFINCONF preenchido). Testado com
     *    REGPESOTOTAL='N' (NUCCO 5) sem precisar de peso — quando a balança
     *    entrar (gap separado), revisar se algum NUCCO com REGPESOTOTAL!='N'
     *    passa a exigir o parâmetro.
     * 3. ConferenciaSP.finalizarConferencia(nuConf) — NÃO fatal (mesmo
     *    tratamento do projeto base): gera o lado financeiro; falhar aqui não
     *    desfaz o corte, que já aconteceu no passo 2.
     *
     * NÃO bloqueia por item pendente/divergente (nem pra mais nem pra menos)
     * — quem decide o que fazer com a divergência é a Configuração de
     * Conferência (CCO) do NUCCO, lida nativamente pelo Sankhya dentro de
     * `cortar` (PROCEDCORTE pra falta, GERARPEDCOMPL pra excesso — confirmado
     * na documentação oficial: cada NUCCO decide se ajusta a quantidade
     * negociada, gera pedido complementar/nota de devolução, ou nada). O
     * frontend avisa o operador da divergência ANTES de chamar isto, mas a
     * decisão de permitir ou não já está na configuração do Sankhya, não é
     * escolha nossa aqui.
     */
    suspend fun finalizar(
        tenantSlug: String,
        tenantId: UUID,
        sessaoId: UUID,
        semCorte: Boolean = false,
        /** Quem finalizou (login pessoal, ou operador do crachá na estação) — vira TGFCON2.CODUSUCONF. */
        usuarioFinalizadorId: UUID? = null,
        /** false quando quem chama já verificou o pedido (concluirEtapa da última etapa). */
        verificarPedido: Boolean = true,
    ): FinalizarResultadoDto {
        // "Finalizar divergente" desabilitado (01/10/2026): fechava como 'D' por engano, sem passar pela
        // Liberação de Corte. Barrado aqui também pra aba aberta com a tela antiga.
        if (semCorte) {
            throw FinalizarSeparacaoException("\"Finalizar divergente\" está desabilitado — use \"Ajustar\" (a divergência vai para a Liberação de Corte).")
        }
        val sessao = SeparacaoRepository.buscarSessao(tenantId, sessaoId)
            ?: throw FinalizarSeparacaoException("sessão não encontrada")
        if (sessao.status != SeparacaoStatus.PRONTA) {
            throw FinalizarSeparacaoException("sessão em status '${sessao.status}', só é possível finalizar sessão 'pronta'")
        }
        // Conferência por etapa (V29): a nota só finaliza no Sankhya quando todas
        // as etapas com item estão 'C'. Sessão não segmentada não tem etapas —
        // todasEtapasConcluidas devolve false e o guard não dispara.
        if (sessao.conferenciaSegmentada && SeparacaoRepository.listarEtapas(tenantId, sessaoId).isNotEmpty() &&
            !SeparacaoRepository.todasEtapasConcluidas(tenantId, sessaoId)
        ) {
            throw FinalizarSeparacaoException("há etapas de conferência pendentes — conclua todas antes de finalizar")
        }
        val nuconf = SeparacaoRepository.buscarNuconf(tenantId, sessaoId)
            ?: throw FinalizarSeparacaoException("sessão sem NUCONF — carregamento não terminou de verdade")

        if (verificarPedido) verificarPedidoAtualizado(tenantSlug, tenantId, sessaoId, "finalizar")

        // CCO.FORMACAOVOLUMES 'S'/'T'/'D' exige volume apontado — mesma checagem
        // que o frontend faz pra desabilitar o botão, repetida aqui porque quem
        // decide de verdade é o backend (defesa em camada, não só UX).
        if (SeparacaoRepository.exigeVolume(tenantId, sessaoId) && SeparacaoRepository.totalQtdVol(tenantId, sessaoId) <= 0) {
            throw FinalizarSeparacaoException("a Configuração de Conferência exige volume apontado — informe a quantidade de volumes antes de finalizar")
        }

        val grupos = SeparacaoRepository.listarGruposConferidos(tenantId, sessaoId, apenasNaoEnviados = true)
        try {
        enviarGruposAoSankhya(tenantSlug, tenantId, sessaoId, sessao.nunota, nuconf, grupos)

        // REVERTIDO (nota 57500, 2º teste): chamar ConferenciaSP.finalizarConferencia
        // ANTES do cortar() — pra imitar a ordem nativa — na prática fechou a
        // conferência de vez (TGFCON2.STATUS='F'/'D', some da fila) em vez de
        // reabrir pra recontagem depois do liberar+negar, revertendo o ganho do
        // fix anterior (que dependia de NÃO reforçar finalizarConferencia fora
        // de hora). A ordem nativa dos payloads não é segura de replicar 1:1
        // aqui porque o nosso fluxo já é estruturalmente diferente (dispara
        // corte e liberação em momentos separados, não na mesma sessão de UI).

        // Volumes (modo simplificado): total CONSOLIDADO (soma das etapas na
        // conferência segmentada, ou o contador da sessão) vai junto no `cortar`,
        // igual ao legado (ConferenciaSP.cortar recebe { nuNota, peso, qtdVol }).
        val qtdVol = withContext(Dispatchers.IO) { SeparacaoRepository.totalQtdVol(tenantId, sessaoId) }

        // Conferente no Sankhya = quem finalizou no WMS (vínculo CODUSU no cadastro de usuários).
        // Antes do corte/finalização (depois a conferência fecha). Falha aqui NÃO trava a finalização.
        registrarConferenteSankhya(tenantSlug, tenantId, nuconf, usuarioFinalizadorId)

        // "Finalizar divergente" (botão do pop-up de divergência) = o MESMO que a tela nativa do
        // Sankhya: SÓ ConferenciaSP.finalizarConferencia com os eventos de confirmação — sem
        // `cortar`. A conferência fecha como 'D' (Finalizada divergente) sem ajustar a nota.
        // Payload nativo capturado pelo usuário (nuConf 622 / nota 58362, 29/09) → ficou 'D'.
        // Antes os dois botões chamavam cortar → a nota subia "Finalizado OK" com corte.
        // Aqui a falha É fatal: sem o cortar, é esta chamada que fecha a conferência.
        if (semCorte) {
            FinalizacaoProgresso.atualizar(sessaoId, "finalizando")
            SankhyaSpClient.chamarRaw(
                tenantSlug, "ConferenciaSP.finalizarConferencia", "mgecom",
                buildJsonObject {
                    putJsonObject("params") {
                        put("nuConf", nuconf.toString())
                        put("peso", 0)
                        put("qtdVol", qtdVol)
                    }
                    CLIENT_EVENT_FINALIZAR_DIVERGENTE.forEach { (k, v) -> put(k, v) }
                },
            )
            withContext(Dispatchers.IO) {
                SeparacaoRepository.marcarConcluida(tenantId, sessaoId)
                SeparacaoLockRepository.liberarTodos(tenantId, sessaoId)
                TarefasRepository.concluirLocalSemWriteBack(tenantId, sessao.nunota)
            }
            FinalizacaoProgresso.registrarConclusao(
                sessaoId, ConclusaoDto(etapa = null, conferenciaFinalizada = true, aguardandoCorte = false, nuconf = nuconf),
            )
            return FinalizarResultadoDto(ok = true, aguardandoCorte = false, nuconf = nuconf)
        }

        FinalizacaoProgresso.atualizar(sessaoId, "corte")
        // Com os MESMOS eventos de confirmação da finalização divergente — o legado
        // (fila-de-conferencia) e a tela nativa mandam esses eventos em TODA chamada
        // de cortar. Sem eles, divergência que o Sankhya precisa confirmar (ex.:
        // entrada/compra com peso A MAIOR — pedido complementar/devolução) fazia o
        // cortar ser recusado e a finalização quebrar sem ir pra liberação de corte
        // (nota 58933, 01/10).
        // Compra (TIPMOV 'C'/'O') segue o MESMO cortar da venda (payload nativo capturado pelo
        // usuário, nota 58915, 01/10 — com a config certa no Sankhya); a única diferença é não ter
        // corte silencioso (ver auto-liberação abaixo).
        val ehCompra = withContext(Dispatchers.IO) { TarefasRepository.buscarTipMovLocal(tenantId, sessao.nunota) }
            ?.trim()?.uppercase() in setOf("C", "O")
        try {
            SankhyaSpClient.chamarRaw(
                tenantSlug, "ConferenciaSP.cortar", "mgecom",
                buildJsonObject {
                    putJsonObject("params") {
                        put("nuNota", sessao.nunota)
                        put("peso", 0)
                        put("qtdVol", qtdVol)
                    }
                    CLIENT_EVENT_FINALIZAR_DIVERGENTE.forEach { (k, v) -> put(k, v) }
                },
            )
        } catch (e: Exception) {
            println("AVISO: ConferenciaSP.cortar falhou (nunota ${sessao.nunota}, nuconf $nuconf): ${e.message}")
            throw e
        }

        // Se a CCO exige liberação de corte (LIBCORTE='S') e houve divergência, o
        // `cortar` deixa a conferência em TGFCON2.STATUS='C' em vez de 'F' — um
        // liberador precisa aprovar/negar antes da nota seguir (ver
        // wms.backend.liberacaocorte). Não é fatal aqui: a conferência acabou do
        // ponto de vista do WMS, o Sankhya é dono da liberação agora.
        var aguardandoCorte = runCatching { statusConferencia(tenantSlug, nuconf) }.getOrNull()?.trim() == "C"

        // Vínculo liberação -> produto (V55): o Sankhya não grava, só recalcula. Este é o único
        // momento em que TGFCOI2/TGFITE estão como ele viu ao numerar as liberações — antes da
        // auto-liberação (que depende dele) e de qualquer corte mudar a nota. Ver VinculoCorte.
        if (aguardandoCorte) {
            wms.backend.liberacaocorte.VinculoCorte.calcular(tenantSlug, tenantId, sessao.nunota.toLong(), nuconf, "cortar")
        }

        // Auto-liberação de corte por peso, ITEM A ITEM: toda linha de item pesável
        // dentro de ±5% do pedido é liberada em silêncio pela aplicação, mesmo que
        // a nota tenha outras divergências (item normal, ou pesável fora dos 5%) —
        // essas seguem pra liberação manual. Só zera `aguardandoCorte` se, depois
        // disso, não sobrou nada pendente e a conferência foi finalizada.
        //
        // "Liberar sozinho" (sem liberador humano) só vale pra pesável dentro da
        // tolerância — não pesável SEMPRE segue pra liberação manual, mesmo
        // quando o operador escolhe "Cortar" no pop-up de finalização divergente
        // (esse clique não é autorização de liberação — ver LiberacaoCorteService).
        var resolvidoViaAutoLiberacao = false
        // Compra: sem corte silencioso — toda divergência vai pra liberação manual.
        if (aguardandoCorte && !ehCompra) {
            FinalizacaoProgresso.atualizar(sessaoId, "liberacao")
            val liberouTudo = runCatching {
                LiberacaoCorteService.autoLiberarPesoDentroTolerancia(tenantSlug, tenantId, nuconf, sessaoId)
            }.getOrDefault(false)
            if (liberouTudo) {
                aguardandoCorte = false
                resolvidoViaAutoLiberacao = true
            }
        }

        // autoLiberarPesoDentroTolerancia já decide sozinha se chama
        // ConferenciaSP.finalizarConferencia (depende de AOLIBERAR — 'M' marca
        // recontagem sozinho, não deve ser finalizado; ver comentário lá).
        // Chamar de novo aqui atropelaria essa decisão — bug real confirmado
        // (notas 57251/57500): conferência fechava "Finalizado Divergente" em
        // vez de abrir a recontagem do item negado.
        //
        // Usa chamarRaw com CLIENT_EVENT_FINALIZAR_DIVERGENTE (payload real da
        // tela nativa) em vez do `chamar()` simples — sem esses eventos de
        // confirmação o Sankhya fica sem resposta pras decisões que só ele
        // resolve (PROCEDCORTE/GERARPEDCOMPL), suspeita mais forte do "corte
        // maior automático" relatado em secos divergente.
        if (!aguardandoCorte && !resolvidoViaAutoLiberacao) {
            FinalizacaoProgresso.atualizar(sessaoId, "finalizando")
            try {
                SankhyaSpClient.chamarRaw(
                    tenantSlug, "ConferenciaSP.finalizarConferencia", "mgecom",
                    buildJsonObject {
                        putJsonObject("params") {
                            put("nuConf", nuconf.toString())
                            put("peso", 0)
                            put("qtdVol", qtdVol)
                        }
                        CLIENT_EVENT_FINALIZAR_DIVERGENTE.forEach { (k, v) -> put(k, v) }
                    },
                )
            } catch (e: Exception) {
                // Non-fatal — o corte (obrigatório) já aconteceu; o lado financeiro pode ser retomado depois.
            }
        }

        withContext(Dispatchers.IO) {
            SeparacaoRepository.marcarConcluida(tenantId, sessaoId)
            SeparacaoLockRepository.liberarTodos(tenantId, sessaoId)
            // NÃO é TarefaSyncService.sincronizarTenant() — uma nota com
            // TGFCON2.STATUS='F' sai do critério de busca do sync (mesma regra
            // da fila nativa: conferência finalizada não aparece mais), então
            // o próximo ciclo NUNCA reconciliaria esta nota. Precisa fechar
            // localmente aqui, de propósito.
            //
            // Já em STATUS='C' (aguardando corte) a nota AINDA aparece no sync —
            // o próximo ciclo reconcilia pra status_operacional='aguardando_corte'
            // e ela cai na tela /liberacao-corte. concluirLocalSemWriteBack aqui
            // é só um fechamento otimista; o sync corrige em seguida.
            TarefasRepository.concluirLocalSemWriteBack(tenantId, sessao.nunota)
        }

        FinalizacaoProgresso.registrarConclusao(
            sessaoId, ConclusaoDto(etapa = null, conferenciaFinalizada = true, aguardandoCorte = aguardandoCorte, nuconf = nuconf),
        )
        return FinalizarResultadoDto(ok = true, aguardandoCorte = aguardandoCorte, nuconf = nuconf)
        } finally {
            FinalizacaoProgresso.limpar(sessaoId)
        }
    }

    class ConcluirEtapaException(message: String) : Exception(message)
    /** Erro específico "ainda há itens pendentes nesta etapa" — o front abre o modal de confirmação. */
    class EtapaComPendentesException(val pendentes: Int) : Exception("etapa tem $pendentes item(ns) pendente(s)")

    /**
     * Conclui uma etapa da conferência segmentada (V29). Se for a última etapa
     * pendente, dispara `finalizar` (push real pro Sankhya). Espelha
     * `postConcluirEtapa` do fila-de-conferencia.
     */
    /**
     * TGFCON2.CODUSUCONF = CODUSU (TSIUSU) do usuário do WMS que finalizou, via CRUDServiceProvider.saveRecord
     * na CabecalhoConferencia. Sem vínculo no cadastro = não mexe (fica o usuário da integração).
     */
    private suspend fun registrarConferenteSankhya(tenantSlug: String, tenantId: UUID, nuconf: Int, usuarioId: UUID?) {
        if (usuarioId == null) return
        val codusu = withContext(Dispatchers.IO) { wms.backend.usuarios.UsuariosRepository.codusuSankhya(tenantId, usuarioId) } ?: return
        runCatching {
            SankhyaSpClient.chamarRaw(
                tenantSlug, "CRUDServiceProvider.saveRecord", "mge",
                buildJsonObject {
                    putJsonObject("dataSet") {
                        put("rootEntity", "CabecalhoConferencia")
                        put("includePresentationFields", "N")
                        putJsonObject("dataRow") {
                            putJsonObject("localFields") { putJsonObject("CODUSUCONF") { put("\$", codusu.toString()) } }
                            putJsonObject("key") { putJsonObject("NUCONF") { put("\$", nuconf.toString()) } }
                        }
                        putJsonObject("entity") { putJsonObject("fieldset") { put("list", "NUCONF,CODUSUCONF") } }
                    }
                },
            )
        }.onSuccess {
            println("INFO: conferência $nuconf — CODUSUCONF=$codusu gravado")
        }.onFailure {
            println("AVISO: conferência $nuconf — falha ao gravar CODUSUCONF=$codusu: ${it.message}")
        }
    }

    suspend fun concluirEtapa(
        tenantSlug: String,
        tenantId: UUID,
        sessaoId: UUID,
        tipoSeparacao: Int,
        manterPendente: Boolean,
        operador: String,
        /** Última etapa: "Finalizar divergente" (sem corte) em vez de "Cortar" — ver finalizar(semCorte). */
        finalizarSemCorte: Boolean = false,
        /** Quem concluiu (vira o conferente no Sankhya se esta for a última etapa). */
        usuarioFinalizadorId: UUID? = null,
        /** Pin vermelho da etapa na fila — false quando só sobrou pesável na tolerância (ver ConcluirEtapaRequest). */
        divergente: Boolean = manterPendente,
    ): ConcluirEtapaResultadoDto {
        val sessao = withContext(Dispatchers.IO) { SeparacaoRepository.buscarSessao(tenantId, sessaoId) }
            ?: throw ConcluirEtapaException("sessão não encontrada")
        if (sessao.status != SeparacaoStatus.PRONTA) {
            throw ConcluirEtapaException("sessão em status '${sessao.status}', não é possível concluir etapa")
        }
        val tipo = tipoSeparacao.toShort()

        verificarPedidoAtualizado(tenantSlug, tenantId, sessaoId, "concluir etapa $tipoSeparacao")

        if (!manterPendente) {
            val pendentes = withContext(Dispatchers.IO) {
                SeparacaoRepository.contarPendentesDaEtapa(tenantId, sessaoId, tipo)
            }
            if (pendentes > 0) throw EtapaComPendentesException(pendentes)
        }

        val marcou = withContext(Dispatchers.IO) {
            // Concluir COM divergência (pop-up de divergência/aviso de etapa) → chip vermelho na fila.
            // Pendente que é só pesável a menor dentro da tolerância não pinta de vermelho (divergente=false).
            SeparacaoRepository.concluirEtapa(tenantId, sessaoId, tipo, operador, divergente = divergente)
        }
        if (!marcou) throw ConcluirEtapaException("etapa $tipoSeparacao não encontrada ou já concluída")

        val todasConcluidas = withContext(Dispatchers.IO) {
            SeparacaoRepository.todasEtapasConcluidas(tenantId, sessaoId)
        }
        if (!todasConcluidas) {
            // Conferência por etapas: dá baixa no Sankhya dos itens DESTA etapa já ao concluí-la —
            // o finalizar da última etapa só envia o que faltou (evita mandar a nota inteira no fim).
            // Falhou? reabre a etapa pra tentar de novo pela tela (mesmo tratamento da última etapa).
            if (sessao.conferenciaSegmentada) {
                try {
                    val nuconf = withContext(Dispatchers.IO) { SeparacaoRepository.buscarNuconf(tenantId, sessaoId) }
                        ?: throw ConcluirEtapaException("sessão sem NUCONF — carregamento não terminou de verdade")
                    val grupos = withContext(Dispatchers.IO) {
                        SeparacaoRepository.listarGruposConferidos(tenantId, sessaoId, tipoSeparacao = tipo, apenasNaoEnviados = true)
                    }
                    enviarGruposAoSankhya(tenantSlug, tenantId, sessaoId, sessao.nunota, nuconf, grupos)
                } catch (e: Exception) {
                    withContext(Dispatchers.IO) { SeparacaoRepository.reabrirEtapa(tenantId, sessaoId, tipo) }
                    throw e
                } finally {
                    FinalizacaoProgresso.limpar(sessaoId)
                }
            }
            // Etapa concluída: quem quiser abrir outra etapa não é barrado por este lock.
            withContext(Dispatchers.IO) { SeparacaoLockRepository.liberarEtapa(tenantId, sessaoId, tipo) }
            FinalizacaoProgresso.registrarConclusao(sessaoId, ConclusaoDto(etapa = tipoSeparacao, conferenciaFinalizada = false))
            return ConcluirEtapaResultadoDto(etapaConcluida = true, conferenciaFinalizada = false)
        }

        // Última etapa — finaliza a conferência de verdade (push + cortar + finalizarConferencia).
        //
        // Bug real encontrado (nota 57355): se finalizar() falha (confirmado:
        // timeout de rede com o Sankhya, 24s até um 502), a etapa já tinha
        // sido marcada 'C' alguns segundos antes — ficava "presa" concluída
        // localmente pra sempre, sem o corte ter acontecido de verdade no
        // Sankhya. Como concluirEtapa() só marca 'P'→'C' (idempotência por
        // WHERE status='P'), não tinha como tentar de novo pela tela normal —
        // precisou de UPDATE manual no banco pra destravar. Reverte a etapa
        // pra 'P' se finalizar() falhar, pra um retry pela UI funcionar sozinho.
        val res = try {
            finalizar(tenantSlug, tenantId, sessaoId, semCorte = finalizarSemCorte, usuarioFinalizadorId = usuarioFinalizadorId, verificarPedido = false)
        } catch (e: Exception) {
            withContext(Dispatchers.IO) { SeparacaoRepository.reabrirEtapa(tenantId, sessaoId, tipo) }
            throw e
        }
        return ConcluirEtapaResultadoDto(
            etapaConcluida = true,
            conferenciaFinalizada = true,
            aguardandoCorte = res.aguardandoCorte,
            nuconf = res.nuconf,
        )
    }

    /**
     * Breakdown de tipos de separação por nunota — pro card da Fila de Tarefas.
     * Uma chamada `ItemNota` batelada. Gate por módulo: tenant sem
     * `conferencia_segmentada` recebe mapa vazio (custo zero).
     */
    suspend fun etapasFila(tenantSlug: String, tenantId: UUID, nunotas: List<Long>): Map<Long, FilaEtapasDto> {
        if (nunotas.isEmpty()) return emptyMap()
        val segmentado = withContext(Dispatchers.IO) {
            wms.backend.tenancy.TenantRepository.modulosHabilitados(tenantId)
                .contains(wms.backend.tenancy.Modulos.CONFERENCIA_SEGMENTADA)
        }
        if (!segmentado) return emptyMap()

        val tiposPorNunota = etapasFilaCache.get(tenantId, nunotas) {
            val raw = SankhyaLoadRecordsClient.loadRecords(
                tenantSlug,
                LoadRecordsRequest(
                    entityName = "ItemNota",
                    fields = listOf("NUNOTA", "Produto.AD_TIPOSEPARACAO"),
                    criteriaExpression = "NUNOTA IN (${nunotas.joinToString(",")})",
                ),
            )
            SankhyaLoadRecordsClient.parseRows(raw, listOf("NUNOTA", "Produto.AD_TIPOSEPARACAO"))
                .mapNotNull { r -> (r["NUNOTA"]?.toLongOrNull() ?: return@mapNotNull null) to parseTipoSeparacao(r["Produto.AD_TIPOSEPARACAO"]).toInt() }
                .groupBy({ it.first }, { it.second })
                .mapValues { (_, v) -> v.distinct().sorted() }
        }

        val concluidos = withContext(Dispatchers.IO) {
            SeparacaoRepository.etapasConcluidasPorNunota(tenantId, nunotas)
        }
        val divergentes = withContext(Dispatchers.IO) {
            SeparacaoRepository.etapasDivergentesPorNunota(tenantId, nunotas)
        }
        val progresso = withContext(Dispatchers.IO) {
            SeparacaoRepository.progressoEtapasPorNunota(tenantId, nunotas)
        }
        // RECONTAGEM é sempre etapa única (ver carregarEmBackground/ehRecontagem)
        // — nota com sessão CONCLUIDA anterior não entra no breakdown, senão o
        // card oferece "Conferir por etapa" pra uma conferência que vai abrir
        // sem etapa nenhuma.
        val emRecontagem = withContext(Dispatchers.IO) {
            SeparacaoRepository.nunotasComSessaoConcluida(tenantId, nunotas)
        }
        // TOP com conferência por etapa desligada (V49, ex.: entrada/compra): card sem chips de etapa,
        // igual à sessão que vai abrir (carregarEmBackground aplica a mesma regra).
        val semEtapa = withContext(Dispatchers.IO) {
            val topsSemEtapa = wms.backend.tipooperacao.TipoOperacaoRepository.topsSemConferenciaPorEtapa(tenantId)
            if (topsSemEtapa.isEmpty()) emptySet()
            else TarefasRepository.codTipOperPorNunota(tenantId, nunotas).filterValues { it in topsSemEtapa }.keys
        }
        return nunotas.associateWith { nunota ->
            FilaEtapasDto(
                tipos = if (nunota in emRecontagem || nunota in semEtapa) emptyList() else tiposPorNunota[nunota] ?: emptyList(),
                concluidos = concluidos[nunota] ?: emptyList(),
                divergentes = divergentes[nunota] ?: emptyList(),
                progresso = (progresso[nunota] ?: emptyMap()).mapValues { (_, p) -> EtapaProgressoDto(p.total, p.conferidos) },
            )
        }.filterValues { it.tipos.isNotEmpty() }
    }

    /** Cache em processo p/ o breakdown de tipos da fila — TTL curto (a fila re-consulta a cada sync tick). */
    private val etapasFilaCache = EtapasFilaCache(ttlMillis = 30_000)

    private class EtapasFilaCache(private val ttlMillis: Long) {
        private data class Entrada(val valor: Map<Long, List<Int>>, val expiraEm: Long)
        private val mapa = java.util.concurrent.ConcurrentHashMap<String, Entrada>()

        suspend fun get(tenantId: UUID, nunotas: List<Long>, carregar: suspend () -> Map<Long, List<Int>>): Map<Long, List<Int>> {
            val chave = "$tenantId:${nunotas.sorted().joinToString(",")}"
            val agora = System.currentTimeMillis()
            mapa[chave]?.takeIf { it.expiraEm > agora }?.let { return it.valor }
            val valor = carregar()
            mapa[chave] = Entrada(valor, agora + ttlMillis)
            return valor
        }
    }

    /**
     * Quantidade de itens (linhas de TGFITE) por nunota — pro card da Fila de
     * Tarefas ("Itens: N"). Batelada via `ItemNota`, sem gate de módulo (ao
     * contrário de [etapasFila] — vale pra qualquer tenant, segmentado ou não).
     */
    suspend fun itensFila(tenantSlug: String, tenantId: UUID, nunotas: List<Long>): Map<Long, Int> {
        if (nunotas.isEmpty()) return emptyMap()
        return itensFilaCache.get(tenantId, nunotas) {
            val raw = SankhyaLoadRecordsClient.loadRecords(
                tenantSlug,
                LoadRecordsRequest(
                    entityName = "ItemNota",
                    fields = listOf("NUNOTA"),
                    criteriaExpression = "NUNOTA IN (${nunotas.joinToString(",")})",
                ),
            )
            SankhyaLoadRecordsClient.parseRows(raw, listOf("NUNOTA"))
                .mapNotNull { it["NUNOTA"]?.toLongOrNull() }
                .groupingBy { it }
                .eachCount()
        }
    }

    private val itensFilaCache = ItensFilaCache(ttlMillis = 30_000)

    private class ItensFilaCache(private val ttlMillis: Long) {
        private data class Entrada(val valor: Map<Long, Int>, val expiraEm: Long)
        private val mapa = java.util.concurrent.ConcurrentHashMap<String, Entrada>()

        suspend fun get(tenantId: UUID, nunotas: List<Long>, carregar: suspend () -> Map<Long, Int>): Map<Long, Int> {
            val chave = "$tenantId:${nunotas.sorted().joinToString(",")}"
            val agora = System.currentTimeMillis()
            mapa[chave]?.takeIf { it.expiraEm > agora }?.let { return it.valor }
            val valor = carregar()
            mapa[chave] = Entrada(valor, agora + ttlMillis)
            return valor
        }
    }

    /** TGFCON2.STATUS da conferência (via CabecalhoConferencia) — 'F' concluída, 'C' aguardando liberação de corte, 'D' cancelada. */
    private suspend fun statusConferencia(tenantSlug: String, nuconf: Int): String? {
        val fields = listOf("NUCONF", "STATUS")
        val raw = SankhyaLoadRecordsClient.loadRecords(
            tenantSlug,
            LoadRecordsRequest(entityName = "CabecalhoConferencia", fields = fields, criteriaExpression = "NUCONF = $nuconf"),
        )
        return SankhyaLoadRecordsClient.parseRows(raw, fields).firstOrNull()?.get("STATUS")
    }

    class FaturamentoException(message: String) : Exception(message)

    /** Estado da nota no Sankhya que decide se dá pra faturar: notas já geradas a partir dela e corte aguardando liberação. */
    private data class SituacaoFaturamento(val notasGeradas: List<Long>, val liberacoesPendentes: Int, val statusConferencia: String?)

    private suspend fun situacaoFaturamento(tenantSlug: String, nunota: Long): SituacaoFaturamento {
        val geradas = SankhyaDbExplorerClient.executarQuery(
            tenantSlug,
            "SELECT DISTINCT V.NUNOTA FROM TGFVAR V WHERE V.NUNOTAORIG = $nunota AND V.NUNOTA <> $nunota",
        ).mapNotNull { it["NUNOTA"]?.toBigDecimalOrNull()?.toLong() }.sorted()
        // STATUS da conferência atual da nota + corte com liberação (TSILIB evento 64) ainda sem decisão — ver VinculoCorte.
        val conf = SankhyaDbExplorerClient.executarQuery(
            tenantSlug,
            "SELECT (SELECT F.STATUS FROM TGFCON2 F WHERE F.NUCONF = C.NUCONFATUAL) AS STATUS_CONF, " +
                "(SELECT COUNT(*) FROM TSILIB L WHERE L.NUCHAVE = C.NUCONFATUAL AND L.TABELA = 'TGFCOI2' " +
                "AND L.EVENTO = 64 AND L.DHLIB IS NULL) AS QTD " +
                "FROM TGFCAB C WHERE C.NUNOTA = $nunota",
        ).firstOrNull()
        val pendentes = conf?.get("QTD")?.toBigDecimalOrNull()?.toInt() ?: 0
        return SituacaoFaturamento(geradas, pendentes, conf?.get("STATUS_CONF")?.trim()?.takeIf { it.isNotEmpty() })
    }

    /** STATUS de conferência (TGFCON2) que contam como finalizada — mesmo conjunto da checagem de recontagem no iniciar. */
    private val STATUS_CONF_FINALIZADA = setOf("F", "D", "RF", "RD")

    /**
     * Bloqueios de faturamento da sessão. Quem decide se a conferência está finalizada é o
     * STATUS no Sankhya, não a sessão local: a liberação de corte pela tela própria fecha a
     * conferência por lá, e um corte NEGADO põe a nota em recontagem com a sessão local ainda concluída.
     */
    private suspend fun validarFaturamento(tenantSlug: String, sessao: SessaoSeparacaoDto) {
        if (sessao.status == SeparacaoStatus.CANCELADA) {
            throw FaturamentoException("esta conferência foi cancelada")
        }
        val situacao = situacaoFaturamento(tenantSlug, sessao.nunota)
        if (situacao.notasGeradas.isNotEmpty()) {
            throw FaturamentoException("a nota ${sessao.nunota} já foi faturada (nota gerada: ${situacao.notasGeradas.joinToString()})")
        }
        if (situacao.statusConferencia == "C" || situacao.liberacoesPendentes > 0) {
            throw FaturamentoException("há corte aguardando liberação nesta conferência — libere o corte antes de faturar")
        }
        if (situacao.statusConferencia !in STATUS_CONF_FINALIZADA) {
            throw FaturamentoException(
                "a conferência não está finalizada no Sankhya (status '${situacao.statusConferencia ?: "sem conferência"}') — " +
                    "se houve corte negado, a nota precisa ser recontada antes de faturar",
            )
        }
    }

    /** TOPs de destino possíveis pro faturamento da nota da sessão — TGFTOP ativos do mesmo TIPMOV. Portado de fila-conferencia conferencia.service.ts:1684. */
    suspend fun topsFaturamento(tenantSlug: String, tenantId: UUID, sessaoId: UUID): List<TopFaturamentoDto> {
        val sessao = withContext(Dispatchers.IO) { SeparacaoRepository.buscarSessao(tenantId, sessaoId) }
            ?: throw FaturamentoException("sessão não encontrada")
        validarFaturamento(tenantSlug, sessao)
        val tipmov = withContext(Dispatchers.IO) { TarefasRepository.buscarTipMovLocal(tenantId, sessao.nunota) } ?: "V"

        val fields = listOf("CODTIPOPER", "DESCROPER")
        val raw = SankhyaLoadRecordsClient.loadRecords(
            tenantSlug,
            LoadRecordsRequest(
                entityName = "TipoOperacao",
                fields = fields,
                criteriaExpression = "TIPMOV = '$tipmov' AND ATIVO = 'S'",
                orderByExpression = "DESCROPER ASC",
            ),
        )
        return SankhyaLoadRecordsClient.parseRows(raw, fields).mapNotNull { r ->
            val cod = r["CODTIPOPER"]?.toIntOrNull()?.takeIf { it > 0 } ?: return@mapNotNull null
            val desc = r["DESCROPER"]?.trim().orEmpty()
            // O Sankhya devolve uma linha placeholder "<SEM TOP>" (CODTIPOPER 0) — nunca faturável.
            if (desc.equals("<SEM TOP>", ignoreCase = true)) return@mapNotNull null
            TopFaturamentoDto(codTipOper = cod, descricao = desc)
        }
    }

    /**
     * Fatura a nota da sessão via `SelecaoDocumentoSP.faturar`.
     * Corpo portado VERBATIM do fila-conferencia
     * (fila-conferencia-backend/src/modules/conferencia/conferencia.service.ts:1698-1727) —
     * testado em produção lá.
     *
     * Antes: bloqueia sessão não concluída, nota já faturada e corte aguardando liberação.
     * A chamada vai SEM retry (faturar não é idempotente); se ela falhar sem resposta clara,
     * relê o Sankhya — se a nota gerada apareceu, o faturamento entrou e é tratado como sucesso.
     * Devolve os NUNOTA das notas geradas.
     */
    suspend fun faturar(tenantSlug: String, tenantId: UUID, sessaoId: UUID, codTipOper: Int, serie: String?): List<Long> {
        val sessao = withContext(Dispatchers.IO) { SeparacaoRepository.buscarSessao(tenantId, sessaoId) }
            ?: throw FaturamentoException("sessão não encontrada")
        validarFaturamento(tenantSlug, sessao)

        val hoje = java.time.LocalDate.now().format(java.time.format.DateTimeFormatter.ofPattern("dd/MM/yyyy"))
        val requestBody = buildJsonObject {
            putJsonObject("notas") {
                put("codTipOper", codTipOper)
                put("dtFaturamento", hoje)
                put("tipoFaturamento", "FaturamentoNormal")
                put("dataValidada", true)
                putJsonObject("notasComMoeda") {}
                putJsonArray("nota") { add(buildJsonObject { put("$", sessao.nunota) }) }
                put("serie", serie?.takeIf { it.isNotBlank() } ?: "1")
                put("faturarTodosItens", true)
                put("umaNotaParaCada", "false")
                put("ehWizardFaturamento", true)
                put("dtFixaVenc", "")
                put("ehPedidoWeb", false)
                put("nfeDevolucaoViaRecusa", false)
            }
        }
        try {
            SankhyaSpClient.chamarRaw(tenantSlug, "SelecaoDocumentoSP.faturar", "mgecom", requestBody, retentar = false)
        } catch (e: Exception) {
            // Recusa de regra do Sankhya é definitiva; já timeout/5xx pode ter faturado mesmo assim.
            if (e is SankhyaSpClient.SankhyaSpException && e !is SankhyaSpClient.SankhyaSpErroTransitorio) throw e
            val geradas = runCatching { situacaoFaturamento(tenantSlug, sessao.nunota).notasGeradas }.getOrNull()
            if (geradas.isNullOrEmpty()) {
                throw FaturamentoException(
                    "o Sankhya não confirmou o faturamento (${e.message}). Confira no Sankhya se a nota ${sessao.nunota} foi faturada antes de tentar de novo.",
                )
            }
            println("AVISO: faturar nunota ${sessao.nunota} falhou (${e.message}), mas a nota foi gerada: $geradas")
            return geradas
        }
        return runCatching { situacaoFaturamento(tenantSlug, sessao.nunota).notasGeradas }.getOrDefault(emptyList())
    }

    /** Dados pra etiqueta de volume (uma por volume) — cliente/UF/número/qtd de volumes. Portado de fila-conferencia arquivo.helper.ts. */
    suspend fun dadosEtiqueta(tenantSlug: String, tenantId: UUID, sessaoId: UUID, etapa: Int? = null): EtiquetaDadosDto {
        val sessao = withContext(Dispatchers.IO) { SeparacaoRepository.buscarSessao(tenantId, sessaoId) }
            ?: throw FaturamentoException("sessão não encontrada")
        val nuconf = withContext(Dispatchers.IO) { SeparacaoRepository.buscarNuconf(tenantId, sessaoId) }
        // Durante/logo após a conferência: usa o total LOCAL consolidado (soma
        // das etapas ou contador da sessão). O Sankhya só recebe no `cortar`.
        val qtdVolLocal = withContext(Dispatchers.IO) { SeparacaoRepository.totalQtdVol(tenantId, sessaoId) }
        val base = montarDadosEtiqueta(tenantSlug, tenantId, sessao.nunota, nuconf, qtdVolLocal)
        if (etapa == null) {
            if (!sessao.recontagem) return base
            // Recontagem: só os volumes NOVOS, numerados a partir da conferência anterior (7 + 1 = etiqueta 08 de 08).
            return base.copy(
                totalVolumes = qtdVolLocal,
                volumeInicial = sessao.volumeBase + 1,
                volumeFinal = sessao.volumeBase + qtdVolLocal,
                totalExibicao = sessao.volumeBase + qtdVolLocal,
            )
        }
        // Etiqueta POR ETAPA: só os volumes desta etapa, numeração acumulada (ver faixaVolumesEtapa).
        val (ini, fim) = withContext(Dispatchers.IO) { SeparacaoRepository.faixaVolumesEtapa(tenantId, sessaoId, etapa.toShort()) }
        return base.copy(totalVolumes = (fim - ini + 1).coerceAtLeast(0), volumeInicial = ini, volumeFinal = fim, etapaTipo = etapa)
    }

    suspend fun dadosEtiquetaPorNota(tenantSlug: String, tenantId: UUID, nunota: Long): EtiquetaDadosDto {
        val nuconf = withContext(Dispatchers.IO) { SeparacaoRepository.buscarNuconfPorNota(tenantId, nunota) }
        return montarDadosEtiqueta(tenantSlug, tenantId, nunota, nuconf)
    }

    private suspend fun montarDadosEtiqueta(
        tenantSlug: String,
        tenantId: UUID,
        nunota: Long,
        nuconf: Int?,
        qtdVolOverride: Int? = null,
    ): EtiquetaDadosDto {
        val totalVolumes = qtdVolOverride
            ?: nuconf?.let { runCatching { buscarQtdVolumes(tenantSlug, it) }.getOrDefault(0) }
            ?: 0
        val codparc = withContext(Dispatchers.IO) { TarefasRepository.buscarCodParcLocal(tenantId, nunota) }

        val (cliente, uf) = buscarClienteUf(tenantSlug, codparc)

        val numeroNota = nunota.toString().padStart(5, '0').takeLast(5)
        val ordemCarga = withContext(Dispatchers.IO) { TarefasRepository.buscarOrdemCargaLocal(tenantId, nunota) }
        val transporte = ordemCarga?.let { wms.backend.mapaseparacao.TransporteOrdemCarga.buscar(tenantSlug, tenantId, it) }
        return EtiquetaDadosDto(
            motorista = transporte?.motorista,
            placa = transporte?.placa,
            veiculo = transporte?.veiculo,
            cliente = cliente,
            uf = uf,
            numeroNota = numeroNota,
            nunota = nunota,
            ordemCarga = ordemCarga,
            codParc = codparc,
            numeroConferencia = nuconf,
            totalVolumes = totalVolumes,
        )
    }

    /** Cliente (razão social) e UF do parceiro — compartilhado pela etiqueta de volume e pela de peso. */
    private suspend fun buscarClienteUf(tenantSlug: String, codparc: Int?): Pair<String, String> {
        if (codparc == null) return "" to ""
        // Razão social/UF do parceiro quase não mudam: cache de 1h evita uma ida ao Sankhya
        // (~0,75s, 2 com a UF) a cada abertura de etiqueta de volume/peso.
        clienteUfCache[tenantSlug to codparc]?.let { (expira, par) -> if (System.currentTimeMillis() < expira) return par }
        val par = buscarClienteUfNoSankhya(tenantSlug, codparc)
        if (par.first.isNotEmpty()) clienteUfCache[tenantSlug to codparc] = (System.currentTimeMillis() + 3_600_000L) to par
        return par
    }

    private val clienteUfCache = java.util.concurrent.ConcurrentHashMap<Pair<String, Int>, Pair<Long, Pair<String, String>>>()

    private suspend fun buscarClienteUfNoSankhya(tenantSlug: String, codparc: Int): Pair<String, String> {
        val fields = listOf("RAZAOSOCIAL", "Cidade.UF")
        val raw = SankhyaLoadRecordsClient.loadRecords(
            tenantSlug,
            LoadRecordsRequest(entityName = "Parceiro", fields = fields, criteriaExpression = "CODPARC = $codparc"),
        )
        val row = SankhyaLoadRecordsClient.parseRows(raw, fields).firstOrNull()
        val cliente = row?.get("RAZAOSOCIAL")?.trim().orEmpty()
        val ufRaw = row?.get("Cidade.UF")?.trim().orEmpty()
        val uf = if (ufRaw.toIntOrNull() != null) resolverUf(tenantSlug, ufRaw) else ufRaw
        return cliente to uf
    }

    /**
     * Etiquetas de peso dos itens PESÁVEIS já conferidos da sessão (peso = qtd_conferida_local,
     * já em KG — nada é recalculado aqui). `codprod`/`controle` restringem a um item;
     * `nova` só vale junto com um item (nova etiqueta explícita, número novo).
     * Sem `nova`, item que já tem etiqueta reimprime o MESMO número.
     */
    suspend fun etiquetasPeso(
        tenantSlug: String,
        tenantId: UUID,
        sessaoId: UUID,
        codprod: Int?,
        controle: String?,
        nova: Boolean,
        etapa: Int? = null,
    ): List<EtiquetaPesoDto> {
        val sessao = withContext(Dispatchers.IO) { SeparacaoRepository.buscarSessao(tenantId, sessaoId) }
            ?: throw FaturamentoException("sessão não encontrada")
        val nuconf = withContext(Dispatchers.IO) { SeparacaoRepository.buscarNuconf(tenantId, sessaoId) }
        val itens = withContext(Dispatchers.IO) { SeparacaoRepository.listarItens(tenantId, sessaoId) }
            .filter { it.usaConfPeso && (it.qtdConferidaLocal.toBigDecimalOrNull() ?: java.math.BigDecimal.ZERO).signum() > 0 }
            .filter { codprod == null || (it.codprod == codprod && it.controle == (controle ?: it.controle)) }
            .filter { etapa == null || it.tipoSeparacao == etapa }
        if (itens.isEmpty()) return emptyList()

        val codparc = withContext(Dispatchers.IO) { TarefasRepository.buscarCodParcLocal(tenantId, sessao.nunota) }
        val (cliente, uf) = buscarClienteUf(tenantSlug, codparc)
        val ordemCarga = withContext(Dispatchers.IO) { TarefasRepository.buscarOrdemCargaLocal(tenantId, sessao.nunota) }

        return withContext(Dispatchers.IO) {
            itens.map { item ->
                val produto = wms.backend.produtos.NomeProduto.formatar(item.descricaoProduto, item.complementoDescricao)
                    ?: "Produto ${item.codprod}"
                SeparacaoRepository.obterOuCriarEtiquetaPeso(
                    tenantId = tenantId,
                    sessaoId = sessaoId,
                    nunota = sessao.nunota,
                    nuconf = nuconf,
                    codprod = item.codprod,
                    controle = item.controle,
                    produto = produto,
                    peso = item.qtdConferidaLocal.toBigDecimal(),
                    cliente = cliente,
                    nova = nova && codprod != null,
                    correcao = sessao.recontagem,
                ).copy(ordemCarga = ordemCarga, codParc = codparc, uf = uf.ifBlank { null })
            }
        }
    }

    private suspend fun resolverUf(tenantSlug: String, coduf: String): String {
        val fields = listOf("UF")
        val raw = SankhyaLoadRecordsClient.loadRecords(
            tenantSlug,
            LoadRecordsRequest(entityName = "UnidadeFederativa", fields = fields, criteriaExpression = "CODUF = $coduf"),
        )
        return SankhyaLoadRecordsClient.parseRows(raw, fields).firstOrNull()?.get("UF")?.trim().orEmpty()
    }

    class CancelarSeparacaoException(message: String) : Exception(message)

    /**
     * Cancela a sessão — desiste do pedido inteiro (não só devolve 1 item).
     *
     * Chama `ConferenciaSP.excluirConferencia` (cancelamento nativo do Sankhya).
     * ANTES gravava STATUS='D' achando que era "desistida", mas 'D' no domínio
     * do Sankhya é "Finalizada divergente" — cancelar estava, na prática,
     * finalizando a conferência com divergência. Fire-and-forget, igual às
     * outras chamadas ConferenciaSP.* deste arquivo.
     */
    suspend fun cancelar(tenantSlug: String, tenantId: UUID, sessaoId: UUID) {
        val sessao = withContext(Dispatchers.IO) { SeparacaoRepository.buscarSessao(tenantId, sessaoId) }
            ?: throw CancelarSeparacaoException("sessão não encontrada")
        if (sessao.status == SeparacaoStatus.CONCLUIDA || sessao.status == SeparacaoStatus.CANCELADA) {
            throw CancelarSeparacaoException("sessão em status '${sessao.status}', não é possível cancelar")
        }

        runCatching { excluirConferenciaSankhya(tenantSlug, sessao.nunota) }
            .onFailure { println("AVISO: ConferenciaSP.excluirConferencia falhou (nunota ${sessao.nunota}): ${it.message}") }

        withContext(Dispatchers.IO) {
            SeparacaoRepository.marcarCancelada(tenantId, sessaoId)
            SeparacaoLockRepository.liberarTodos(tenantId, sessaoId)
            // Nota excluída sai do critério do sync — fecha a tarefa local
            // (senão a nota reaparece na Fila de Tarefas no próximo ciclo).
            TarefasRepository.concluirLocalSemWriteBack(tenantId, sessao.nunota)
        }
    }

    suspend fun excluirConferenciaSankhya(tenantSlug: String, nunota: Long) {
        SankhyaSpClient.chamarRaw(
            tenantSlug, "ConferenciaSP.excluirConferencia", "mgecom",
            buildJsonObject {
                putJsonObject("notas") {
                    putJsonArray("nota") { addJsonObject { put("$", nunota) } }
                }
                putJsonObject("clientEventList") {
                    putJsonArray("clientEvent") {
                        add(buildJsonObject { put("$", "br.com.sankhya.actionbutton.clientconfirm") })
                    }
                }
            },
        )
    }

    class RecontagemException(message: String) : Exception(message)

    /**
     * Recontagem — reabre a sessão pra bipar tudo de novo do zero, sem
     * precisar reconsultar itens/config no Sankhya (nada disso muda entre
     * uma contagem e outra). Reaproveita `ConferenciaSP.salvarCabecalhoConferencia`
     * com `iniciarRecontagem=true` — mesmo contrato já confirmado ao vivo
     * nesta sessão (usado com `false` no `iniciar()`), só não tínhamos usado
     * esse parâmetro ainda.
     */
    suspend fun iniciarRecontagem(tenantSlug: String, tenantId: UUID, sessaoId: UUID) {
        val sessao = SeparacaoRepository.buscarSessao(tenantId, sessaoId)
            ?: throw RecontagemException("sessão não encontrada")
        if (sessao.status != SeparacaoStatus.PRONTA && sessao.status != SeparacaoStatus.CONCLUIDA) {
            throw RecontagemException("sessão em status '${sessao.status}', só é possível recontar sessão 'pronta' ou 'concluida'")
        }

        SankhyaSpClient.chamar(
            tenantSlug,
            "ConferenciaSP.salvarCabecalhoConferencia",
            mapOf(
                "nuNota" to JsonPrimitive(sessao.nunota),
                "iniciarRecontagem" to JsonPrimitive(true),
            ),
        )

        withContext(Dispatchers.IO) { SeparacaoRepository.reiniciarContagem(tenantId, sessaoId) }
    }

    /** null se o Sankhya ainda não atribuiu NUCONF pra esta nota (não deveria acontecer logo após salvarCabecalhoConferencia, mas não é fatal). */
    private suspend fun buscarNuconf(tenantSlug: String, nunota: Long): Int? {
        val fields = listOf("NUNOTAORIG", "NUCONF")
        val raw = SankhyaLoadRecordsClient.loadRecords(
            tenantSlug,
            LoadRecordsRequest(
                entityName = "CabecalhoConferencia",
                fields = fields,
                criteriaExpression = "NUNOTAORIG = $nunota",
                orderByExpression = "NUCONF DESC",
            ),
        )
        return SankhyaLoadRecordsClient.parseRows(raw, fields).firstOrNull()?.get("NUCONF")?.toIntOrNull()
    }

    private val FIELDS_DETALHE_CONF = listOf(
        "NUCONF", "SEQCONF", "CODPROD", "QTDCONF",
    )

    /**
     * Quanto já foi conferido/aceito de verdade por produto, na conferência
     * ATUAL (NUCONF) — fonte de verdade real, capturada ao vivo da tela
     * nativa (dataSetID 042, DetalhesConferenciaCRUDListener, nota 57500).
     * ItemNota.QTDCONFERIDA NUNCA é preenchido pelo Sankhya pra esse fluxo
     * (fica 0 sempre) — é este dataset que carrega o valor certo. É aqui
     * também que mora o evento "conferencia.lista.produtos.divergentes",
     * mas usar o QTDCONF por linha é equivalente e não depende de parsear
     * clientEvents.
     */
    private suspend fun buscarQtdConferidaPorProduto(tenantSlug: String, nuconf: Int): Map<Int, BigDecimal> {
        val resp = SankhyaSpClient.chamarRaw(
            tenantSlug,
            "DatasetSP.loadRecords",
            "mge",
            buildJsonObject {
                put("dataSetID", "042")
                put("entityName", "DetalhesConferencia")
                put("standAlone", false)
                putJsonArray("fields") { FIELDS_DETALHE_CONF.forEach { add(it) } }
                put("tryJoinedFields", true)
                put("parallelLoader", true)
                put("crudListener", "br.com.sankhya.modelcore.crudlisteners.DetalhesConferenciaCRUDListener")
                putJsonObject("criteria") {
                    put("expression", "(this.NUCONF = ? )")
                    putJsonArray("parameters") {
                        addJsonObject { put("type", "N"); put("value", nuconf.toString()) }
                    }
                }
                put("ignoreListenerMethods", "")
                put("useDefaultRowsLimit", true)
            },
        )
        val rows = (resp["result"] as? JsonArray) ?: return emptyMap()
        return rows.mapNotNull { row ->
            val arr = row as? JsonArray ?: return@mapNotNull null
            val valores = FIELDS_DETALHE_CONF.mapIndexed { i, campo ->
                campo to (arr.getOrNull(i) as? JsonPrimitive)?.contentOrNull
            }.toMap()
            val codprod = valores["CODPROD"]?.toIntOrNull() ?: return@mapNotNull null
            val qtdConf = valores["QTDCONF"].parseBigDecimalBr() ?: BigDecimal.ZERO
            codprod to qtdConf
        }.toMap()
    }

    /** Linha do pedido que o filtro de "já conferido" escondeu (ou quis esconder e a TGFCOI2 não confirmou). */
    @kotlinx.serialization.Serializable
    data class LinhaOculta(
        val sequencia: Int,
        val codprod: Int,
        val qtdNeg: String,
        /** QTDCONF da DetalhesConferencia (o que disparou o filtro). */
        val qtdConfDetalhe: String,
        /** Soma do QTDCONF na TGFCOI2 (checagem dupla; null = não consultada). */
        val qtdConfTgfcoi2: String? = null,
        /** "oculto" = ficou fora da conferência | "mantido" = TGFCOI2 não confirmou, ficou na lista. */
        val acao: String,
    )

    data class ItensDaNota(
        val itens: List<ItemParaSalvar>,
        val pendentes: Set<Int>,
        val ocultas: List<LinhaOculta>,
    )

    /** Rastreio da carga (V56) + log. Nunca derruba a abertura/sincronização. */
    private suspend fun registrarDiagnostico(
        tenantId: UUID, sessaoId: UUID, nunota: Long, nuconf: Int?, origem: String, recontagem: Boolean, carga: ItensDaNota,
    ) {
        val carregadas = carga.itens.count { it.sequencia in carga.pendentes }
        println(
            "INFO: carga itens nunota=$nunota nuconf=$nuconf ($origem${if (recontagem) ", recontagem" else ""}): " +
                "pedido=${carga.itens.size} carregadas=$carregadas" +
                (if (carga.ocultas.isEmpty()) "" else " ocultas=" + carga.ocultas.joinToString { "seq${it.sequencia}/${it.codprod}:${it.acao}" }),
        )
        runCatching {
            withContext(Dispatchers.IO) {
                SeparacaoRepository.gravarDiagnostico(
                    tenantId, sessaoId, nunota, nuconf, origem, recontagem, carga.itens.size, carregadas,
                    kotlinx.serialization.json.Json.encodeToString(kotlinx.serialization.builtins.ListSerializer(LinhaOculta.serializer()), carga.ocultas),
                )
            }
        }.onFailure { println("AVISO: falha ao gravar diagnóstico da carga (nunota $nunota): ${it.message}") }
    }

    /**
     * Todas as linhas da nota (menos EXCLUIRCONF='S') + as SEQUENCIAs que ainda precisam de
     * conferência (critério abaixo). A sincronização com o Sankhya precisa das duas coisas: linha
     * já resolvida no NUCONF atual continua existindo (não pode ser tratada como removida).
     */
    private suspend fun buscarItensDaNota(
        tenantSlug: String,
        nunota: Long,
        nuconf: Int?,
        codprodsNegados: Set<Pair<Int, String>>,
        recontagem: Boolean,
    ): ItensDaNota {
        val raw = SankhyaLoadRecordsClient.loadRecords(
            tenantSlug,
            LoadRecordsRequest(
                entityName = "ItemNota",
                fields = FIELDS_ITEM,
                criteriaExpression = "NUNOTA = $nunota",
                orderByExpression = "SEQUENCIA ASC",
            ),
        )
        val qtdConferidaPorProduto = nuconf?.let {
            runCatching { buscarQtdConferidaPorProduto(tenantSlug, it) }
                .onFailure { e -> println("AVISO: falha ao buscar DetalhesConferencia (nuconf $it): ${e.message}") }
                .getOrDefault(emptyMap())
        } ?: emptyMap()

        val todasAsLinhas = SankhyaLoadRecordsClient.parseRows(raw, FIELDS_ITEM)
            .filter { it["Produto.EXCLUIRCONF"]?.trim()?.uppercase() != "S" }

        // Paliativo (nota 61514, 05/10/2026): itens do pedido sumiram da abertura sem nunca terem sido
        // conferidos — o único filtro que esconde linha é este de "já conferido". Fora da recontagem
        // (onde ele é a regra de verdade), só esconde se a TGFCOI2 da conferência CONFIRMAR conferido do
        // produto; sem confirmação (ou falha na consulta) o item fica na lista. Tudo vai pro rastreio (V56).
        val querEsconder = todasAsLinhas.filter { r ->
            val codprod = r["CODPROD"]?.toIntOrNull() ?: return@filter false
            val controle = r["CONTROLE"]?.trim()?.takeIf { it.isNotEmpty() } ?: " "
            if ((codprod to controle) in codprodsNegados) return@filter false
            val qtdNeg = r["QTDNEG"].parseBigDecimalBr() ?: BigDecimal.ZERO
            qtdNeg <= (qtdConferidaPorProduto[codprod] ?: BigDecimal.ZERO)
        }
        val conferidoTgfcoi2: Map<Int, BigDecimal>? = if (recontagem || querEsconder.isEmpty() || nuconf == null) null else {
            runCatching {
                SankhyaDbExplorerClient.executarQuery(
                    tenantSlug, "SELECT CODPROD, SUM(QTDCONF) AS QTD FROM TGFCOI2 WHERE NUCONF = $nuconf GROUP BY CODPROD",
                ).mapNotNull { r -> r["CODPROD"]?.toBigDecimalOrNull()?.toInt()?.let { it to (r["QTD"]?.toBigDecimalOrNull() ?: BigDecimal.ZERO) } }.toMap()
            }.onFailure { println("AVISO: checagem TGFCOI2 falhou (nuconf $nuconf) — nenhum item escondido: ${it.message}") }
                .getOrDefault(emptyMap())
        }
        val ocultas = querEsconder.mapNotNull { r ->
            val codprod = r["CODPROD"]?.toIntOrNull() ?: return@mapNotNull null
            val confirmado = recontagem || (conferidoTgfcoi2?.get(codprod)?.signum() ?: 0) > 0
            LinhaOculta(
                sequencia = r["SEQUENCIA"]?.toIntOrNull() ?: return@mapNotNull null,
                codprod = codprod,
                qtdNeg = (r["QTDNEG"].parseBigDecimalBr() ?: BigDecimal.ZERO).stripTrailingZeros().toPlainString(),
                qtdConfDetalhe = (qtdConferidaPorProduto[codprod] ?: BigDecimal.ZERO).stripTrailingZeros().toPlainString(),
                qtdConfTgfcoi2 = conferidoTgfcoi2?.let { (it[codprod] ?: BigDecimal.ZERO).stripTrailingZeros().toPlainString() },
                acao = if (confirmado) "oculto" else "mantido",
            )
        }
        val escondidas = ocultas.filter { it.acao == "oculto" }.map { it.sequencia }.toSet()

        val pendentes = todasAsLinhas
            // Critério real de "precisa reconferência", capturado ao vivo da
            // tela nativa (nota 57500): compara QTDNEG (ItemNota) com QTDCONF
            // (DetalhesConferencia, NUCONF atual) — ItemNota.QTDCONFERIDA fica
            // sempre 0, não serve pra nada aqui. Item com QTDCONF == QTDNEG já
            // bateu 100% (sem divergência ou já resolvido); só quem ainda tem
            // saldo (QTDNEG > QTDCONF) precisa aparecer.
            //
            // EXCEÇÃO: item NEGADO (codprodsNegados) sempre reaparece, mesmo
            // que QTDCONF >= QTDNEG no NUCONF novo — bug real confirmado (nota
            // 57501, codprod 94): negar é uma decisão explícita do operador
            // que exige nova ação; não pode sumir sozinho só porque o Sankhya
            // já espelhou um QTDCONF que parece "resolvido" pro novo ciclo.
            .mapNotNull { it["SEQUENCIA"]?.toIntOrNull() }
            .filter { it !in escondidas }
            .toSet()

        val itens = todasAsLinhas.mapNotNull { r ->
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
                tipoSeparacao = parseTipoSeparacao(r["Produto.AD_TIPOSEPARACAO"]),
            )
        }
        return ItensDaNota(itens, pendentes, ocultas)
    }

    /**
     * VOA não tem campo de auditoria (TGFVOA sem DHALTER) — sem sync periódico por página
     * (incremental de verdade, tipo Produto/CodigoBarras), populado sob demanda (mesmo espírito
     * de ProdutoImagemService). "Não achou local" é sempre tratado como "nunca foi buscado",
     * nunca "confirmado vazio" — aceito, é dado opcional (nem todo produto tem unidade
     * alternativa) e a busca ao vivo já é escopada só pelos produtos da nota, igual sempre foi.
     *
     * MAS não é mais cache pra sempre sem revalidação — [VOA_CACHE_TTL] força reconsulta ao vivo
     * pra linha velha. Bug real confirmado (produto 3395): TGFVOA.QUANTIDADE foi corrigido de 1
     * pra 6 no Sankhya (provavelmente na mesma limpeza de pedidos que gerou os outros sync bugs
     * desta sessão), e o WMS continuou aplicando o fator 1 indefinidamente — cache "pra sempre"
     * significava "errado pra sempre" quando o cadastro do Sankhya muda depois do 1º cache.
     */
    private val VOA_CACHE_TTL: java.time.Duration = java.time.Duration.ofMinutes(30)

    /**
     * [aoVivo] = ignora o cache e consulta tudo no Sankhya (atualizando o cache) — usado pelo Mapa de
     * Separação: relatório impresso não pode sair com fator velho (caso real: OC 69, produto 248 com
     * CX=25 no cache e CX=5 no Sankhya → imprimiu 0,2 CX em vez de 1 CX).
     */
    internal suspend fun buscarVoa(tenantSlug: String, tenantId: UUID, codprods: List<Int>, aoVivo: Boolean = false): List<Map<String, String?>> {
        if (codprods.isEmpty()) return emptyList()

        val frescoDesde = java.time.Instant.now().minus(VOA_CACHE_TTL)
        val cache = if (aoVivo) emptyList() else withContext(Dispatchers.IO) { ProdutoCatalogoRepository.buscarVoaPorCodprods(tenantId, codprods, frescoDesde) }
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
        val consultados = SankhyaLoadRecordsClient.parseRows(raw, FIELDS_VOA)
        withContext(Dispatchers.IO) {
            // Limpa órfãos ANTES de upsert — combinação que sumiu do Sankhya pra um CODPROD
            // revalidado (inclusive CODPROD que ficou com ZERO unidades alternativas agora).
            ProdutoCatalogoRepository.removerVoaOrfas(tenantId, faltando, consultados)
            if (consultados.isNotEmpty()) ProdutoCatalogoRepository.upsertVoa(tenantId, consultados)
        }
        return cache + consultados
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

    /**
     * CODVOL "nativo" (cadastro) do produto, TGFPRO.CODVOL — diferente do
     * CODVOL da linha da nota (TGFITE.CODVOL), que reflete a unidade
     * NEGOCIADA naquela venda (ex.: produto pesável cadastrado em KG mas
     * vendido "por peça" em PC via volume alternativo). Bug real encontrado
     * ao vivo (produto 3832, "QUEIJO MUSSARELA"): TGFPRO.CODVOL='KG' com
     * TGFVOL.UTILICONFPESO='S', mas a linha do pedido negociava em CODVOL
     * 'PC' — checar só o CODVOL da linha (como antes) nunca acionava o
     * popup de peso pra esse produto. Hoje a decisão de pesável mora em
     * RegraPesavel; aqui o CODVOL de cadastro só alimenta a unidade padrão/comercial do item.
     */
    private suspend fun buscarCodvolProduto(tenantSlug: String, codprods: List<Int>): Map<Int, String> {
        if (codprods.isEmpty()) return emptyMap()
        val lista = codprods.joinToString(",")
        val sql = "SELECT CODPROD, CODVOL FROM TGFPRO WHERE CODPROD IN ($lista)"
        return SankhyaDbExplorerClient.executarQuery(tenantSlug, sql)
            .mapNotNull { r ->
                val codprod = r["CODPROD"]?.toIntOrNull() ?: return@mapNotNull null
                val codvol = r["CODVOL"] ?: return@mapNotNull null
                codprod to codvol
            }
            .toMap()
    }

    /**
     * UMA (Unidade de Movimentação e Armazenagem) — não é catálogo
     * persistente nem no Sankhya nem no projeto base: lida ao vivo por
     * sessão, só pros produtos que exigem pesagem (RegraPesavel).
     * SQL direto (DatasetSP não lê essas tabelas) — tabelas reais
     * confirmadas ao vivo: `TGFPUMA` (produto×UMA) join `TGFUMA` (UMA).
     */
    private suspend fun buscarUma(tenantSlug: String, codprods: List<Int>): List<UmaParaSalvar> {
        if (codprods.isEmpty()) return emptyList()
        val lista = codprods.joinToString(",")
        val sql = "SELECT P.CODPROD, P.CODUMA, P.CODBARRA, P.CODVOL, P.PADRAO, U.DESCRUMA, U.PESO " +
            "FROM TGFPUMA P JOIN TGFUMA U ON U.CODUMA = P.CODUMA WHERE P.CODPROD IN ($lista)"
        return SankhyaDbExplorerClient.executarQuery(tenantSlug, sql).mapNotNull { r ->
            val codprod = r["CODPROD"]?.toIntOrNull() ?: return@mapNotNull null
            val coduma = r["CODUMA"]?.toIntOrNull() ?: return@mapNotNull null
            UmaParaSalvar(
                codprod = codprod,
                coduma = coduma,
                descricao = r["DESCRUMA"],
                peso = r["PESO"].parseBigDecimalBr(),
                codvol = r["CODVOL"],
                codbarra = r["CODBARRA"],
                padrao = r["PADRAO"]?.trim() == "S",
            )
        }
    }

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
        codvolProdutoPorCodprod: Map<Int, String> = emptyMap(),
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
            val codvol = v["CODVOL"]?.trim()?.takeIf { it.isNotEmpty() }
            // VOA que repete a unidade PADRÃO do produto é fator 1 (ver enriquecerItem) — sem isto,
            // cada bipagem desse código contaria o fator cadastrado (ex.: "BD multiplica 10").
            val ehPadrao = codvol != null && codvolProdutoPorCodprod[codprod]?.trim().equals(codvol, ignoreCase = true)
            codigos += CodigoBarraParaSalvar(
                codigoBarra = codigoBarra,
                codprod = codprod,
                codvol = codvol,
                controle = v["CONTROLE"]?.trim()?.takeIf { it.isNotEmpty() } ?: " ",
                origem = "VOA",
                quantidade = if (ehPadrao) null else v["QUANTIDADE"].parseBigDecimalBr(),
                divideMultiplica = if (ehPadrao) null else v["DIVIDEMULTIPLICA"]?.trim()?.takeIf { it.isNotEmpty() },
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

    /** TGFVOA por (codprod, codvol, controle) → (DIVIDEMULTIPLICA, QUANTIDADE). */
    private fun montarVoaPorChave(voaRows: List<Map<String, String?>>): Map<Triple<Int, String, String>, Pair<String?, BigDecimal?>> =
        voaRows.mapNotNull { r ->
            val cp = r["CODPROD"]?.toIntOrNull() ?: return@mapNotNull null
            val cv = r["CODVOL"]?.trim()?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            val ctrl = r["CONTROLE"]?.trim()?.takeIf { it.isNotEmpty() } ?: " "
            Triple(cp, cv, ctrl) to (r["DIVIDEMULTIPLICA"]?.trim()?.takeIf { it.isNotEmpty() } to r["QUANTIDADE"].parseBigDecimalBr())
        }.toMap()

    /**
     * Unidades (comercial = CODVOL da linha, padrão = TGFPRO.CODVOL), fator de conversão e pesável
     * do item — usado na abertura da sessão e na sincronização com o Sankhya (mesma regra nas duas).
     * Fator: match POR LINHA no VOA cujo CODVOL == CODVOL da linha (+ fallback lote-livre), NUNCA
     * fallback por produto (conferencia.helper.ts:262-287).
     */
    private fun enriquecerItem(
        item: ItemParaSalvar,
        voaPorChave: Map<Triple<Int, String, String>, Pair<String?, BigDecimal?>>,
        codvolProdutoPorCodprod: Map<Int, String>,
        pesavel: Boolean,
    ): ItemParaSalvar {
        val lineCodvol = item.codvol?.trim()?.takeIf { it.isNotEmpty() }
        val prodCodvol = codvolProdutoPorCodprod[item.codprod]?.trim()?.takeIf { it.isNotEmpty() }
        val ctrl = item.controle.trim().takeIf { it.isNotEmpty() } ?: " "
        // Linha na PRÓPRIA unidade padrão não tem conversão (fator 1), mesmo que o cadastro tenha a
        // unidade padrão repetida na TGFVOA com fator — bug real (nota 61572, produto 3717: padrão BD
        // e TGFVOA "BD multiplica 10" faziam o pedido de 5 BD virar 0,5 BD na conferência).
        val voa = lineCodvol?.takeIf { prodCodvol == null || !it.equals(prodCodvol, ignoreCase = true) }?.let {
            voaPorChave[Triple(item.codprod, it, ctrl)] ?: voaPorChave[Triple(item.codprod, it, " ")]
        }
        return item.copy(
            usaConfPeso = pesavel,
            unidadeComercial = lineCodvol ?: prodCodvol,
            unidadePadrao = prodCodvol ?: lineCodvol,
            divideMultiplica = voa?.first,
            fatorConversao = voa?.second,
        )
    }

    class SincronizarSankhyaException(message: String) : Exception(message)

    /**
     * Botão "Atualizar com Sankhya" da conferência (virada de sistema: pedido subiu com quantidade,
     * unidade, fator ou AD_PESAVEL errado e foi corrigido no Sankhya depois que a conferência abriu).
     * Relê a nota AO VIVO — TGFITE, TGFPRO.CODVOL, TGFVOA sem cache, regra de pesável — e aplica as
     * diferenças nos itens da sessão sem fechar a conferência. Item que já tinha conferência e mudou
     * de unidade/fator/pesável tem a conferência DESFEITA (a leitura foi feita na regra errada); mudou
     * só a quantidade, a leitura continua valendo. Item silencioso (já liberado em corte) não é tocado.
     */
    suspend fun sincronizarComSankhya(tenantSlug: String, tenantId: UUID, sessaoId: UUID): SincronizacaoSankhyaDto {
        val sessao = withContext(Dispatchers.IO) { SeparacaoRepository.buscarSessao(tenantId, sessaoId) }
            ?: throw SincronizarSankhyaException("sessão não encontrada")
        if (sessao.status != SeparacaoStatus.PRONTA) {
            throw SincronizarSankhyaException("Conferência em status '${sessao.status}' — só dá pra atualizar conferência aberta.")
        }
        val nunota = sessao.nunota
        val nuconf = withContext(Dispatchers.IO) { SeparacaoRepository.buscarNuconf(tenantId, sessaoId) }
        val codprodsNegados = withContext(Dispatchers.IO) { SeparacaoRepository.buscarCodprodsNegados(tenantId, nunota) }
        val chavesLiberadas = withContext(Dispatchers.IO) { SeparacaoRepository.buscarDecisoesLiberadasComQtd(tenantId, nunota) }
            .map { it.codprod to it.controle }.toSet()

        val carga = buscarItensDaNota(tenantSlug, nunota, nuconf, codprodsNegados, recontagem = sessao.recontagem)
        registrarDiagnostico(tenantId, sessaoId, nunota, nuconf, "sincronizacao", sessao.recontagem, carga)
        val itensNota = carga.itens
        val sequenciasPendentes = carga.pendentes
        val codprods = itensNota.map { it.codprod }.distinct()
        // Aqui falha de leitura TRAVA (diferente da abertura): corrigir com dado incompleto pioraria a sessão.
        val codvolProduto = buscarCodvolProduto(tenantSlug, codprods)
        val decisor = wms.backend.produtos.RegraPesavel.decisor(
            tenantSlug, tenantId, wms.backend.produtos.RegraPesavel.filtroCodprods(codprods),
        )
        val voaRows = buscarVoa(tenantSlug, tenantId, codprods, aoVivo = true)
        val voaPorChave = montarVoaPorChave(voaRows)
        val itensSankhya = itensNota.map { enriquecerItem(it, voaPorChave, codvolProduto, decisor.pesavel(it.codprod, it.codvol)) }

        val resultado = withContext(Dispatchers.IO) {
            SeparacaoRepository.aplicarSincronizacao(tenantId, sessaoId, itensSankhya, sequenciasPendentes, chavesLiberadas)
        }

        // Produtos afetados: códigos de barra (fator do VOA) e UMA (pesável) da sessão refeitos.
        if (resultado.codprodsAfetados.isNotEmpty()) {
            val afetados = resultado.codprodsAfetados.toList()
            // Produto que saiu do pedido perde os códigos de barra da sessão (bipar vira "fora do pedido").
            val noPedido = afetados.filter { cp -> itensSankhya.any { it.codprod == cp } }.toSet()
            val barRows = buscarBar(tenantSlug, tenantId, noPedido.toList())
            val estRows = if (resultado.buscarCodigoBarraPor == "A" || resultado.buscarCodigoBarraPor == "E") {
                buscarEst(tenantSlug, nunota).filter { it["CODPROD"]?.toIntOrNull() in noPedido }
            } else {
                emptyList()
            }
            val codigos = montarCodigosBarra(barRows, voaRows.filter { it["CODPROD"]?.toIntOrNull() in noPedido }, estRows, codvolProduto)
            val pesaveisAfetados = itensSankhya.filter { it.codprod in noPedido && it.usaConfPeso }.map { it.codprod }.distinct()
            val umas = if (pesaveisAfetados.isEmpty()) emptyList() else buscarUma(tenantSlug, pesaveisAfetados)
            withContext(Dispatchers.IO) { SeparacaoRepository.substituirCodigosEUma(tenantId, sessaoId, afetados, codigos, umas) }
        }

        // Conferência por etapa: etapa nova ganha linha; etapa concluída com item que voltou a pendente reabre.
        if (sessao.conferenciaSegmentada) {
            withContext(Dispatchers.IO) {
                val itensSessao = SeparacaoRepository.listarItens(tenantId, sessaoId)
                SeparacaoRepository.semearEtapas(tenantId, sessaoId, itensSessao.map { it.tipoSeparacao.toShort() }.toSet())
                SeparacaoRepository.listarEtapas(tenantId, sessaoId)
                    .filter { it.status == SeparacaoEtapaStatus.CONCLUIDA }
                    .filter { SeparacaoRepository.contarPendentesDaEtapa(tenantId, sessaoId, it.tipoSeparacao.toShort()) > 0 }
                    .forEach { SeparacaoRepository.reabrirEtapa(tenantId, sessaoId, it.tipoSeparacao.toShort()) }
            }
        }

        if (resultado.correcoes.isNotEmpty()) {
            println("Sincronização com Sankhya (nunota $nunota, sessão $sessaoId): ${resultado.correcoes.size} correção(ões) — " +
                resultado.correcoes.joinToString("; ") { "seq ${it.sequencia} prod ${it.codprod} ${it.tipo}: ${it.mudancas.joinToString(", ")}${if (it.conferenciaDesfeita) " [conferência desfeita]" else ""}" })
        }
        return SincronizacaoSankhyaDto(itensVerificados = itensSankhya.size, correcoes = resultado.correcoes)
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
