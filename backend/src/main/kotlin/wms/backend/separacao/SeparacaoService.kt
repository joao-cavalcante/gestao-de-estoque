package wms.backend.separacao

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
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
        // Conferência por etapa (V29) — 1 Secos | 2 Resfriados | 3 Congelados.
        "Produto.AD_TIPOSEPARACAO",
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
            SankhyaSpClient.chamar(
                tenantSlug,
                "ConferenciaSP.salvarCabecalhoConferencia",
                mapOf(
                    "nuNota" to JsonPrimitive(nunota),
                    "iniciarRecontagem" to JsonPrimitive(false),
                ),
            )

            // NUCONF — necessário pra rotinas nativas usadas depois (volume,
            // finalização): ConferenciaSP.salvarVolumeSimplificado e
            // ConferenciaSP.salvarItemConferido exigem esse número, e ele só é
            // atribuído pelo Sankhya na chamada acima. Confirmado ao vivo nesta
            // sessão: CabecalhoConferencia.NUNOTAORIG é a forma de resolvê-lo
            // (mesma técnica que TarefaSyncService já usa, mas persistindo em
            // vez de descartar).
            buscarNuconf(tenantSlug, nunota)?.let { nuconf ->
                withContext(Dispatchers.IO) { SeparacaoRepository.salvarNuconf(tenantId, sessaoId, nuconf) }
            }

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
            val conferenciaSegmentada = withContext(Dispatchers.IO) {
                wms.backend.tenancy.TenantRepository.modulosHabilitados(tenantId)
                    .contains(wms.backend.tenancy.Modulos.CONFERENCIA_SEGMENTADA)
            }

            // Peso (UTILICONFPESO por CODVOL + UMA por produto pesável) — mesma
            // técnica do projeto base: TGFVOL.UTILICONFPESO diz se aquele CODVOL
            // exige pesagem; só pra esses produtos vale a pena buscar UMA. Falha
            // aqui não derruba a sessão (peso é aditivo, não bloqueia bipagem
            // por quantidade) — só loga e segue sem peso pra essa sessão.
            val codprodsDosItens = itens.map { it.codprod }.distinct()
            val codvolProdutoPorCodprod = runCatching { buscarCodvolProduto(tenantSlug, codprodsDosItens) }
                .onFailure { println("AVISO: falha ao ler TGFPRO.CODVOL (tenant $tenantId, nunota $nunota): ${it.message}") }
                .getOrDefault(emptyMap())
            val codvolsDosItens = (itens.mapNotNull { it.codvol } + codvolProdutoPorCodprod.values).distinct()
            val utilizaConfPesoPorCodvol = runCatching { buscarUtilizaConfPeso(tenantSlug, codvolsDosItens) }
                .onFailure { println("AVISO: falha ao ler TGFVOL.UTILICONFPESO (tenant $tenantId, nunota $nunota): ${it.message}") }
                .getOrDefault(emptyMap())
            // usaConfPeso chaveado no CODVOL DE CADASTRO do produto (TGFPRO.CODVOL),
            // fallback pro CODVOL da linha só se o do produto não existir — exatamente
            // como o fila-de-conferencia (conferencia.helper.ts:307).
            fun itemUsaConfPeso(item: ItemParaSalvar): Boolean {
                val codvolChave = codvolProdutoPorCodprod[item.codprod] ?: item.codvol
                return utilizaConfPesoPorCodvol[codvolChave] == true
            }
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

            val codigosBarra = montarCodigosBarra(barRows, voaRows, estRows)
            val fingerprint = calcularFingerprint(itens)

            // Unidades alternativas (TGFVOA) — match POR LINHA: a linha negociada
            // numa unidade != a de cadastro do produto pega o fator do VOA cujo
            // CODVOL == CODVOL da linha (+ fallback lote-livre). NUNCA fallback
            // por produto (conferencia.helper.ts:262-287). É só p/ display.
            val voaPorChave: Map<Triple<Int, String, String>, Pair<String?, BigDecimal?>> = voaRows.mapNotNull { r ->
                val cp = r["CODPROD"]?.toIntOrNull() ?: return@mapNotNull null
                val cv = r["CODVOL"]?.trim()?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
                val ctrl = r["CONTROLE"]?.trim()?.takeIf { it.isNotEmpty() } ?: " "
                Triple(cp, cv, ctrl) to (r["DIVIDEMULTIPLICA"]?.trim()?.takeIf { it.isNotEmpty() } to r["QUANTIDADE"].parseBigDecimalBr())
            }.toMap()

            val itensComPeso = itens.map { item ->
                val lineCodvol = item.codvol?.trim()?.takeIf { it.isNotEmpty() }
                val prodCodvol = codvolProdutoPorCodprod[item.codprod]?.trim()?.takeIf { it.isNotEmpty() }
                val ctrl = item.controle.trim().takeIf { it.isNotEmpty() } ?: " "
                val voa = lineCodvol?.let {
                    voaPorChave[Triple(item.codprod, it, ctrl)] ?: voaPorChave[Triple(item.codprod, it, " ")]
                }
                item.copy(
                    usaConfPeso = itemUsaConfPeso(item),
                    unidadeComercial = lineCodvol ?: prodCodvol,
                    unidadePadrao = prodCodvol ?: lineCodvol,
                    divideMultiplica = voa?.first,
                    fatorConversao = voa?.second,
                )
            }

            withContext(Dispatchers.IO) {
                SeparacaoRepository.salvarItens(tenantId, sessaoId, itensComPeso)
                SeparacaoRepository.salvarCodigosBarra(tenantId, sessaoId, codigosBarra)
                SeparacaoRepository.salvarUma(tenantId, sessaoId, umas)
                // Conferência por etapa (V29): semeia uma etapa por tipo de separação
                // presente na nota. Só quando o módulo está ligado pro tenant.
                if (conferenciaSegmentada) {
                    SeparacaoRepository.semearEtapas(tenantId, sessaoId, itensComPeso.map { it.tipoSeparacao }.toSet())
                }
                SeparacaoRepository.marcarPronta(tenantId, sessaoId, fingerprint, buscarCodigoBarraPor, qtdAmaior, obterQtdBalanca, produtosForaPed, conferenciaSegmentada, fatAoConcluir, exibirProd, exibirQtd, exibirProdConf, exibirQtdConf, exibirImgProd)
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

    class FinalizarSeparacaoException(message: String) : Exception(message)

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
    suspend fun finalizar(tenantSlug: String, tenantId: UUID, sessaoId: UUID): FinalizarResultadoDto {
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

        val grupos = SeparacaoRepository.listarGruposConferidos(tenantId, sessaoId)
        for (grupo in grupos) {
            // Paridade com o legado: CODBARRA = código escanado (fallback codprod),
            // CODVOL = unidade escanada (VOA). A magnitude continua na unidade PADRÃO
            // (o Sankhya converte pela própria TGFVOA). Se o salvarItemConferido do
            // ambiente não aceitar codVol/codBarra reais, remover as 2 linhas marcadas.
            val params = buildMap<String, kotlinx.serialization.json.JsonElement> {
                put("nuNota", JsonPrimitive(sessao.nunota))
                put("numConf", JsonPrimitive(nuconf))
                put("codBarra", JsonPrimitive(grupo.codigoBarra?.takeIf { it.isNotBlank() } ?: grupo.codprod.toString()))
                put("controle", JsonPrimitive(grupo.controle.trim()))
                put("qtdConf", JsonPrimitive(grupo.qtdTotal))
                grupo.codvol?.takeIf { it.isNotBlank() }?.let { put("codVol", JsonPrimitive(it)) } // ← paridade legado
            }
            SankhyaSpClient.chamar(tenantSlug, "ConferenciaSP.salvarItemConferido", params)
        }

        // Volumes (modo simplificado): total CONSOLIDADO (soma das etapas na
        // conferência segmentada, ou o contador da sessão) vai junto no `cortar`,
        // igual ao legado (ConferenciaSP.cortar recebe { nuNota, peso, qtdVol }).
        val qtdVol = withContext(Dispatchers.IO) { SeparacaoRepository.totalQtdVol(tenantId, sessaoId) }
        SankhyaSpClient.chamar(
            tenantSlug,
            "ConferenciaSP.cortar",
            mapOf(
                "nuNota" to JsonPrimitive(sessao.nunota),
                "peso" to JsonPrimitive(0),
                "qtdVol" to JsonPrimitive(qtdVol),
            ),
        )

        // Se a CCO exige liberação de corte (LIBCORTE='S') e houve divergência, o
        // `cortar` deixa a conferência em TGFCON2.STATUS='C' em vez de 'F' — um
        // liberador precisa aprovar/negar antes da nota seguir (ver
        // wms.backend.liberacaocorte). Não é fatal aqui: a conferência acabou do
        // ponto de vista do WMS, o Sankhya é dono da liberação agora.
        var aguardandoCorte = runCatching { statusConferencia(tenantSlug, nuconf) }.getOrNull()?.trim() == "C"

        // Auto-liberação de corte por peso, ITEM A ITEM: toda linha de item pesável
        // dentro de ±5% do pedido é liberada em silêncio pela aplicação, mesmo que
        // a nota tenha outras divergências (item normal, ou pesável fora dos 5%) —
        // essas seguem pra liberação manual. Só zera `aguardandoCorte` se, depois
        // disso, não sobrou nada pendente e a conferência foi finalizada.
        if (aguardandoCorte) {
            val liberouTudo = runCatching {
                LiberacaoCorteService.autoLiberarPesoDentroTolerancia(tenantSlug, tenantId, nuconf, sessaoId)
            }.getOrDefault(false)
            if (liberouTudo) aguardandoCorte = false
        }

        if (!aguardandoCorte) {
            try {
                SankhyaSpClient.chamar(tenantSlug, "ConferenciaSP.finalizarConferencia", mapOf("nuConf" to JsonPrimitive(nuconf)))
            } catch (e: Exception) {
                // Non-fatal — o corte (obrigatório) já aconteceu; o lado financeiro pode ser retomado depois.
            }
        }

        withContext(Dispatchers.IO) {
            SeparacaoRepository.marcarConcluida(tenantId, sessaoId)
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

        return FinalizarResultadoDto(ok = true, aguardandoCorte = aguardandoCorte, nuconf = nuconf)
    }

    class ConcluirEtapaException(message: String) : Exception(message)
    /** Erro específico "ainda há itens pendentes nesta etapa" — o front abre o modal de confirmação. */
    class EtapaComPendentesException(val pendentes: Int) : Exception("etapa tem $pendentes item(ns) pendente(s)")

    /**
     * Conclui uma etapa da conferência segmentada (V29). Se for a última etapa
     * pendente, dispara `finalizar` (push real pro Sankhya). Espelha
     * `postConcluirEtapa` do fila-de-conferencia.
     */
    suspend fun concluirEtapa(
        tenantSlug: String,
        tenantId: UUID,
        sessaoId: UUID,
        tipoSeparacao: Int,
        manterPendente: Boolean,
        operador: String,
    ): ConcluirEtapaResultadoDto {
        val sessao = withContext(Dispatchers.IO) { SeparacaoRepository.buscarSessao(tenantId, sessaoId) }
            ?: throw ConcluirEtapaException("sessão não encontrada")
        if (sessao.status != SeparacaoStatus.PRONTA) {
            throw ConcluirEtapaException("sessão em status '${sessao.status}', não é possível concluir etapa")
        }
        val tipo = tipoSeparacao.toShort()

        if (!manterPendente) {
            val pendentes = withContext(Dispatchers.IO) {
                SeparacaoRepository.contarPendentesDaEtapa(tenantId, sessaoId, tipo)
            }
            if (pendentes > 0) throw EtapaComPendentesException(pendentes)
        }

        val marcou = withContext(Dispatchers.IO) {
            SeparacaoRepository.concluirEtapa(tenantId, sessaoId, tipo, operador)
        }
        if (!marcou) throw ConcluirEtapaException("etapa $tipoSeparacao não encontrada ou já concluída")

        val todasConcluidas = withContext(Dispatchers.IO) {
            SeparacaoRepository.todasEtapasConcluidas(tenantId, sessaoId)
        }
        if (!todasConcluidas) {
            return ConcluirEtapaResultadoDto(etapaConcluida = true, conferenciaFinalizada = false)
        }

        // Última etapa — finaliza a conferência de verdade (push + cortar + finalizarConferencia).
        val res = finalizar(tenantSlug, tenantId, sessaoId)
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
        val progresso = withContext(Dispatchers.IO) {
            SeparacaoRepository.progressoEtapasPorNunota(tenantId, nunotas)
        }
        return nunotas.associateWith { nunota ->
            FilaEtapasDto(
                tipos = tiposPorNunota[nunota] ?: emptyList(),
                concluidos = concluidos[nunota] ?: emptyList(),
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

    /** TOPs de destino possíveis pro faturamento da nota da sessão — TGFTOP ativos do mesmo TIPMOV. Portado de fila-conferencia conferencia.service.ts:1684. */
    suspend fun topsFaturamento(tenantSlug: String, tenantId: UUID, sessaoId: UUID): List<TopFaturamentoDto> {
        val sessao = withContext(Dispatchers.IO) { SeparacaoRepository.buscarSessao(tenantId, sessaoId) }
            ?: throw FaturamentoException("sessão não encontrada")
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
     */
    suspend fun faturar(tenantSlug: String, tenantId: UUID, sessaoId: UUID, codTipOper: Int, serie: String?) {
        val sessao = withContext(Dispatchers.IO) { SeparacaoRepository.buscarSessao(tenantId, sessaoId) }
            ?: throw FaturamentoException("sessão não encontrada")

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
        SankhyaSpClient.chamarRaw(tenantSlug, "SelecaoDocumentoSP.faturar", "mgecom", requestBody)
    }

    /** Dados pra etiqueta de volume (uma por volume) — cliente/UF/número/qtd de volumes. Portado de fila-conferencia arquivo.helper.ts. */
    suspend fun dadosEtiqueta(tenantSlug: String, tenantId: UUID, sessaoId: UUID): EtiquetaDadosDto {
        val sessao = withContext(Dispatchers.IO) { SeparacaoRepository.buscarSessao(tenantId, sessaoId) }
            ?: throw FaturamentoException("sessão não encontrada")
        val nuconf = withContext(Dispatchers.IO) { SeparacaoRepository.buscarNuconf(tenantId, sessaoId) }
        // Durante/logo após a conferência: usa o total LOCAL consolidado (soma
        // das etapas ou contador da sessão). O Sankhya só recebe no `cortar`.
        val qtdVolLocal = withContext(Dispatchers.IO) { SeparacaoRepository.totalQtdVol(tenantId, sessaoId) }
        return montarDadosEtiqueta(tenantSlug, tenantId, sessao.nunota, nuconf, qtdVolLocal)
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

        var cliente = ""
        var uf = ""
        if (codparc != null) {
            val fields = listOf("RAZAOSOCIAL", "Cidade.UF")
            val raw = SankhyaLoadRecordsClient.loadRecords(
                tenantSlug,
                LoadRecordsRequest(entityName = "Parceiro", fields = fields, criteriaExpression = "CODPARC = $codparc"),
            )
            val row = SankhyaLoadRecordsClient.parseRows(raw, fields).firstOrNull()
            cliente = row?.get("RAZAOSOCIAL")?.trim().orEmpty()
            val ufRaw = row?.get("Cidade.UF")?.trim().orEmpty()
            uf = if (ufRaw.toIntOrNull() != null) resolverUf(tenantSlug, ufRaw) else ufRaw
        }

        val numeroNota = nunota.toString().padStart(5, '0').takeLast(5)
        return EtiquetaDadosDto(cliente = cliente, uf = uf, numeroNota = numeroNota, numeroConferencia = nuconf, totalVolumes = totalVolumes)
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
     * Espelha `excluirSessao` do fila-conferencia (conferencia.service.ts:1230-1243):
     * NÃO é `ConferenciaSP.excluirConferencia` — é um `DatasetSP.save` que seta
     * `CabecalhoConferencia.STATUS='D'` (desistida), fire-and-forget. Falhar no
     * Sankhya não bloqueia o cancelamento local (a conferência fica 'A' aberta
     * lá — inofensivo, um supervisor exclui manual). O local é a fonte da verdade.
     */
    suspend fun cancelar(tenantSlug: String, tenantId: UUID, sessaoId: UUID) {
        val sessao = withContext(Dispatchers.IO) { SeparacaoRepository.buscarSessao(tenantId, sessaoId) }
            ?: throw CancelarSeparacaoException("sessão não encontrada")
        if (sessao.status == SeparacaoStatus.CONCLUIDA || sessao.status == SeparacaoStatus.CANCELADA) {
            throw CancelarSeparacaoException("sessão em status '${sessao.status}', não é possível cancelar")
        }

        val nuconf = withContext(Dispatchers.IO) { SeparacaoRepository.buscarNuconf(tenantId, sessaoId) }
        if (nuconf != null) {
            runCatching {
                SankhyaSpClient.chamarRaw(
                    tenantSlug, "DatasetSP.save", "mge",
                    buildJsonObject {
                        put("entityName", "CabecalhoConferencia")
                        put("standAlone", false)
                        putJsonArray("fields") { add("STATUS") }
                        putJsonArray("records") {
                            addJsonObject {
                                putJsonObject("values") { put("0", "D") }
                                putJsonObject("pk") { put("NUCONF", nuconf) }
                            }
                        }
                    },
                )
            }.onFailure { println("AVISO: DatasetSP.save STATUS='D' falhou (nuconf $nuconf): ${it.message}") }
        }

        withContext(Dispatchers.IO) {
            SeparacaoRepository.marcarCancelada(tenantId, sessaoId)
            // Nota com STATUS='D' sai do critério do sync — fecha a tarefa local
            // (senão a nota reaparece na Fila de Tarefas no próximo ciclo).
            TarefasRepository.concluirLocalSemWriteBack(tenantId, sessao.nunota)
        }
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
                tipoSeparacao = parseTipoSeparacao(r["Produto.AD_TIPOSEPARACAO"]),
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

    /**
     * TGFVOL.UTILICONFPESO — diz se aquele CODVOL exige pesagem na conferência
     * (rotina de peso portada do projeto base). SQL direto (DbExplorer), NÃO
     * `DatasetSP.loadRecords` — confirmado ao vivo que a entidade "Volume" não
     * é legível via DatasetSP (mesma limitação de "VolumeConferencia"/
     * "DetalhesConferencia": volta sempre vazio, mesmo com dado real na
     * tabela — só funciona por SQL cru). Consulta pontual (só os codvols da
     * nota), não catálogo completo — mesmo espírito de buscarBar.
     */
    private suspend fun buscarUtilizaConfPeso(tenantSlug: String, codvols: List<String>): Map<String, Boolean> {
        if (codvols.isEmpty()) return emptyMap()
        val lista = codvols.joinToString(",") { "'${it.replace("'", "''")}'" }
        val sql = "SELECT CODVOL, UTILICONFPESO FROM TGFVOL WHERE CODVOL IN ($lista)"
        return SankhyaDbExplorerClient.executarQuery(tenantSlug, sql)
            .mapNotNull { r -> r["CODVOL"]?.let { it to (r["UTILICONFPESO"]?.trim() == "S") } }
            .toMap()
    }

    /**
     * CODVOL "nativo" (cadastro) do produto, TGFPRO.CODVOL — diferente do
     * CODVOL da linha da nota (TGFITE.CODVOL), que reflete a unidade
     * NEGOCIADA naquela venda (ex.: produto pesável cadastrado em KG mas
     * vendido "por peça" em PC via volume alternativo). Bug real encontrado
     * ao vivo (produto 3832, "QUEIJO MUSSARELA"): TGFPRO.CODVOL='KG' com
     * TGFVOL.UTILICONFPESO='S', mas a linha do pedido negociava em CODVOL
     * 'PC' — checar só o CODVOL da linha (como antes) nunca acionava o
     * popup de peso pra esse produto. Agora `usaConfPeso` considera os
     * dois CODVOLs (linha OU cadastro do produto).
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
     * sessão, só pros produtos que exigem pesagem (buscarUtilizaConfPeso).
     * SQL direto (mesmo motivo de buscarUtilizaConfPeso) — tabelas reais
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
