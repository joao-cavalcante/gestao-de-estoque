package wms.backend.liberacaocorte

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import wms.backend.erp.LoadRecordsRequest
import wms.backend.erp.SankhyaLoadRecordsClient
import wms.backend.erp.SankhyaSpClient
import java.util.UUID

/**
 * Liberação de corte — portado de fila-conferencia
 * (fila-conferencia-backend/src/modules/conferencia/conferencia.service.ts:150-374).
 *
 * Todas as chamadas Sankhya vão pelo módulo `/mge` (não `/mgecom`), com o
 * `clientEventList` de "clientconfirm" ao lado de `params` — ver
 * SankhyaSpClient.chamarRaw.
 */
object LiberacaoCorteService {

    class LiberacaoCorteException(message: String) : Exception(message)

    private const val EVENTO_LIBERACAO_CORTE = 64

    private val CLIENT_EVENT_CONFIRM: JsonObject = buildJsonObject {
        putJsonObject("clientEventList") {
            putJsonArray("clientEvent") {
                add(buildJsonObject { put("$", "br.com.sankhya.actionbutton.clientconfirm") })
            }
        }
    }

    private val CAMPOS_VIEW = listOf(
        "NUCHAVE", "TABELA", "EVENTO", "NUCLL", "SEQCASCATA", "SEQUENCIA",
        "CODUSULIB", "OBSERVACAO", "VLRATUAL", "VLRLIMITE",
    )

    /**
     * Lista as conferências aguardando liberação de corte, REVALIDANDO cada uma
     * contra o Sankhya: se o STATUS não é mais 'C' (já liberada por fora), ou é
     * 'C' mas sem nenhum item pendente (estado "preso"), fecha a tarefa local e
     * não devolve o card.
     */
    suspend fun listarRevalidando(tenantSlug: String, tenantId: UUID): List<ConferenciaAguardandoCorteDto> {
        val locais = withContext(Dispatchers.IO) { LiberacaoCorteRepository.listarAguardandoCorte(tenantId) }
        val vivos = mutableListOf<ConferenciaAguardandoCorteDto>()
        for (c in locais) {
            val nuconf = c.nuconf
            if (nuconf == null) { vivos += c; continue }
            val resolvido = runCatching { revalidarUma(tenantSlug, tenantId, nuconf, c.nunota) }.getOrDefault(false)
            if (!resolvido) vivos += c
        }
        return vivos
    }

    /** Revalida uma conferência contra o Sankhya. Retorna true se foi resolvida (tarefa local fechada). */
    suspend fun revalidarUma(tenantSlug: String, tenantId: UUID, nuconf: Int, nunota: Long): Boolean {
        val status = statusConferencia(tenantSlug, nuconf)?.trim()
        val semPendentes = runCatching { buscarPendentesRaw(tenantSlug, nuconf).isEmpty() }.getOrDefault(false)

        val resolver = when {
            status == null -> false
            status != "C" -> true                 // já saiu do 'aguardando corte'
            semPendentes -> {                      // preso: 'C' mas sem itens — finaliza
                runCatching {
                    SankhyaSpClient.chamarRaw(
                        tenantSlug, "ConferenciaSP.finalizarConferencia", "mgecom",
                        buildJsonObject {
                            putJsonObject("params") { put("nuConf", nuconf.toString()); put("peso", 0); put("qtdVol", 0) }
                            CLIENT_EVENT_CONFIRM.forEach { (k, v) -> put(k, v) }
                        },
                    )
                }
                true
            }
            else -> false
        }

        if (resolver) {
            withContext(Dispatchers.IO) {
                wms.backend.tarefas.TarefasRepository.concluirLocalSemWriteBack(tenantId, nunota)
            }
        }
        return resolver
    }

    private suspend fun statusConferencia(tenantSlug: String, nuconf: Int): String? {
        val fields = listOf("NUCONF", "STATUS")
        val raw = SankhyaLoadRecordsClient.loadRecords(
            tenantSlug,
            LoadRecordsRequest(entityName = "CabecalhoConferencia", fields = fields, criteriaExpression = "NUCONF = $nuconf"),
        )
        return SankhyaLoadRecordsClient.parseRows(raw, fields).firstOrNull()?.get("STATUS")
    }

    private suspend fun chamarLiberarNegar(
        tenantSlug: String,
        itens: List<Map<String, String?>>,
        codusu: String,
        liberar: String,
        obsLib: String,
    ) {
        SankhyaSpClient.chamarRaw(
            tenantSlug,
            "LiberacaoLimitesSP.liberarNegarLimites",
            "mge",
            buildJsonObject {
                putJsonObject("params") {
                    putJsonArray("itens") {
                        itens.forEach { i ->
                            addJsonObject {
                                put("nuChave", i["NUCHAVE"]?.toLongOrNull() ?: 0L)
                                put("evento", i["EVENTO"]?.toIntOrNull() ?: EVENTO_LIBERACAO_CORTE)
                                put("nucll", i["NUCLL"]?.toLongOrNull() ?: 0L)
                                put("seqCascata", i["SEQCASCATA"]?.toLongOrNull() ?: 0L)
                                put("sequencia", i["SEQUENCIA"]?.toIntOrNull() ?: 0)
                                put("tabela", i["TABELA"] ?: "TGFCOI2")
                            }
                        }
                    }
                    put("usuario", codusu)
                    put("liberar", liberar)
                    put("obsLib", obsLib)
                    put("vlrLib", 1)
                }
                CLIENT_EVENT_CONFIRM.forEach { (k, v) -> put(k, v) }
            },
        )
    }

    /** 5% da qtd pedida — mesma tolerância do indicador de divergência de peso na conferência. */
    private const val TOLERANCIA_PESO = 0.05

    /**
     * Chave de match contra `obs.produto` (ViewLiberacaoLimite.OBSERVACAO) —
     * quando o produto tem complemento (TGFPRO.COMPLDESC, ex.: "GRANDE"), o
     * Sankhya escreve ele junto na OBSERVACAO ("Prod.: QUEIJO ..., Complem.:
     * GRANDE, Qtd. total conf.: ..."), e o REGEX_OBS engole essa parte no
     * grupo `produto` (não para no primeiro "," — só para quando acha
     * "Qtd. total conf." de verdade). Sem reproduzir esse formato aqui, TODO
     * item pesável com complemento nunca batia no Map (comparava só a
     * descrição pura contra "DESCRIÇÃO, Complem.: X") e caía sempre pra
     * liberação manual, mesmo com divergência ínfima dentro da tolerância —
     * bug real confirmado (Queijo Mussarela Búfala Levitare, Complem.:
     * Grande, divergência de 0,001 PC).
     */
    private fun chaveDescricao(descricao: String?, complemento: String?): String? {
        val desc = descricao?.trim()?.takeIf(String::isNotEmpty) ?: return null
        val compl = complemento?.trim()?.takeIf(String::isNotEmpty)
        return (if (compl != null) "$desc, Complem.: $compl" else desc).uppercase()
    }

    /**
     * Auto-liberação de corte por peso, ITEM A ITEM (fila-conferencia
     * autoLiberarCortePesavel, agora seletivo): toda linha de item PESÁVEL é
     * liberada em silêncio pela aplicação quando o corte é A MAIOR (conferido
     * > pedido — pesou mais que o negociado, nunca precisa de liberação) OU
     * A MENOR dentro de 5% do pedido. A MENOR além de 5% segue pra liberação
     * manual (fica pendente na ViewLiberacaoLimite), assim como item normal
     * (não pesável).
     *
     * Credenciais do liberador via env LIBERADOR_USUARIO / LIBERADOR_SENHA — sem
     * elas nada é liberado.
     *
     * IMPORTANTE — regra de negócio confirmada: "liberar sozinho" (sem um
     * liberador humano logando com a própria credencial) só vale pra produto
     * PESÁVEL dentro desta tolerância. Não pesável NUNCA passa por aqui,
     * mesmo quando o operador clica em "Cortar" no pop-up de finalização
     * divergente — esse clique não é autorização de liberação, só decide
     * seguir com a divergência; a liberação de verdade de item não pesável
     * exige a tela manual (LiberacaoCorteRoutes/OqLiberacaoCorteModalComponent),
     * com login do liberador. Já existiu uma versão que estendia esta função
     * pra liberar não pesável também no clique de "Cortar" — revertida (nota
     * real onde 2 itens de secos genuinamente divergentes foram liberados
     * sozinhos pela credencial de serviço, sem nenhum liberador humano
     * revisar) por ferir esse controle.
     *
     * Retorna `true` só quando, depois das liberações automáticas, NÃO sobra nada
     * pendente e a conferência foi finalizada aqui (nota deixa de aguardar corte).
     */
    suspend fun autoLiberarPesoDentroTolerancia(
        tenantSlug: String,
        tenantId: UUID,
        nuconf: Int,
        sessaoId: UUID,
    ): Boolean {
        val usuario = System.getenv("LIBERADOR_USUARIO")
        val senha = System.getenv("LIBERADOR_SENHA")
        if (usuario.isNullOrBlank() || senha.isNullOrBlank()) {
            println("AVISO: LIBERADOR_USUARIO/LIBERADOR_SENHA não configurados — corte $nuconf fica pra liberação manual.")
            return false
        }
        return try {
            val pendentes = buscarPendentesRaw(tenantSlug, nuconf)
            if (pendentes.isEmpty()) return false

            // Produtos pesáveis da sessão, por descrição (match por descrição — é o
            // que a ViewLiberacaoLimite expõe na OBSERVACAO) — Map, não Set, porque
            // precisamos do CODPROD depois pra persistir a decisão (ver V36).
            val pesaveisPorDescricao = withContext(Dispatchers.IO) {
                wms.backend.separacao.SeparacaoRepository.listarItens(tenantId, sessaoId, incluirSilenciosos = true)
            }
                .filter { it.usaConfPeso }
                .mapNotNull { item -> chaveDescricao(item.descricaoProduto, item.complementoDescricao)?.let { it to item } }
                .toMap()

            val liberaveis = pendentes.filter { linha ->
                val obs = parseObservacaoLiberacao(linha["OBSERVACAO"])
                val prod = obs.produto?.trim()?.uppercase() ?: return@filter false
                val conf = obs.qtdConferida ?: return@filter false
                val ped = obs.qtdPedido ?: return@filter false
                if (prod !in pesaveisPorDescricao) return@filter false
                // A MAIOR (pesou mais que o pedido) nunca precisa de liberação — sempre libera.
                if (conf > ped) return@filter true
                // A MENOR: só libera sozinho dentro da tolerância de 5%.
                val base = if (ped != 0.0) ped else conf
                base != 0.0 && (ped - conf) / base <= TOLERANCIA_PESO
            }
            if (liberaveis.isEmpty()) {
                println("INFO: corte $nuconf sem item pesável dentro da tolerância pra auto-liberar — tudo pra liberação manual.")
                return false
            }

            val codusu = validarLiberador(tenantSlug, usuario, senha)
            chamarLiberarNegar(
                tenantSlug, liberaveis, codusu, "S",
                "Liberação automática — peso a maior, ou a menor dentro da tolerância (${(TOLERANCIA_PESO * 100).toInt()}%)",
            )

            // Registra a decisão localmente (TGFITE não guarda isso — ver V36),
            // senão o item liberado reaparece na recontagem igual ao negado.
            val nunota = withContext(Dispatchers.IO) { wms.backend.separacao.SeparacaoRepository.buscarNunotaPorNuconf(tenantId, nuconf) }
            if (nunota != null) {
                withContext(Dispatchers.IO) {
                    liberaveis.forEach { linha ->
                        val obs = parseObservacaoLiberacao(linha["OBSERVACAO"])
                        val prod = obs.produto?.trim()?.uppercase() ?: return@forEach
                        val item = pesaveisPorDescricao[prod] ?: return@forEach
                        wms.backend.separacao.SeparacaoRepository.registrarDecisaoLiberacao(
                            tenantId, nunota, item.codprod, liberado = true, nuconf = nuconf,
                            controle = item.controle, qtdLiberada = obs.qtdConferida?.toBigDecimal(),
                        )
                    }
                }
            }

            val restantes = runCatching { buscarPendentesRaw(tenantSlug, nuconf) }.getOrDefault(emptyList())
            if (restantes.isNotEmpty()) {
                println("INFO: corte $nuconf — ${liberaveis.size} item(ns) pesável(is) liberado(s) em silêncio; ${restantes.size} segue(m) pra liberação manual.")
                return false
            }

            // Nada mais pendente → AOLIBERAR='M' já marca a recontagem sozinho
            // (mesmo raciocínio de liberarOuNegar, ver comentário lá) — só 'R'
            // precisa da finalização explícita daqui.
            val aoLiberar = withContext(Dispatchers.IO) {
                val nunotaAtual = wms.backend.separacao.SeparacaoRepository.buscarNunotaPorNuconf(tenantId, nuconf)
                val nucco = nunotaAtual?.let { wms.backend.tarefas.TarefasRepository.buscarNuccoLocal(tenantId, it) }
                nucco?.let { wms.backend.configconferencia.ConfigConferenciaRepository.buscarPorNucco(tenantId, it) }
                    ?.campos?.get("AOLIBERAR")?.trim()?.uppercase()
            }
            if (aoLiberar != "M") {
                runCatching {
                    SankhyaSpClient.chamarRaw(
                        tenantSlug, "ConferenciaSP.finalizarConferencia", "mgecom",
                        buildJsonObject {
                            putJsonObject("params") { put("nuConf", nuconf.toString()); put("peso", 0); put("qtdVol", 0) }
                            CLIENT_EVENT_CONFIRM.forEach { (k, v) -> put(k, v) }
                        },
                    )
                }.onFailure {
                    println("AVISO: auto-liberação de corte OK mas ConferenciaSP.finalizarConferencia falhou (nuconf $nuconf): ${it.message}")
                }
            }
            true
        } catch (e: Exception) {
            println("AVISO: auto-liberação de corte falhou (nuconf $nuconf): ${e.message} — segue pra liberação manual.")
            false
        }
    }

    /** Valida usuário/senha do liberador no Sankhya e devolve o CODUSU dele. */
    suspend fun validarLiberador(tenantSlug: String, usuario: String, senha: String): String {
        val resp = try {
            SankhyaSpClient.chamarRaw(
                tenantSlug,
                "LiberacaoLimitesSP.validarSenhaUsuario",
                "mge",
                buildJsonObject {
                    putJsonObject("params") {
                        put("nomeUsu", usuario)
                        put("senhaUsu", senha)
                    }
                    CLIENT_EVENT_CONFIRM.forEach { (k, v) -> put(k, v) }
                },
            )
        } catch (e: Exception) {
            throw LiberacaoCorteException("Usuário ou senha inválidos no Sankhya.")
        }
        return resp["codUsu"]?.jsonPrimitive?.contentOrNull
            ?: throw LiberacaoCorteException("Não foi possível validar o usuário liberador.")
    }

    /**
     * Itens pendentes de liberação (ViewLiberacaoLimite) já parseados. Pra
     * item PESÁVEL, troca qtd./unidade pedido/conferida pelo que a sessão
     * local de fato registrou (separacao_itens.qtd_neg/qtd_conferida_local,
     * unidade_padrao — normalmente KG) em vez do texto que o Sankhya expõe
     * na OBSERVACAO (sempre unidade comercial, ex.: CX — não é a referência
     * usada durante a conferência pra produto pesável). Match por descrição
     * do produto, mesma técnica de [autoLiberarPesoDentroTolerancia].
     */
    suspend fun listarPendentes(tenantSlug: String, tenantId: UUID, nuconf: Int): List<LiberacaoPendenteDto> {
        val itensPesaveisPorDescricao = withContext(Dispatchers.IO) {
            val nunota = wms.backend.separacao.SeparacaoRepository.buscarNunotaPorNuconf(tenantId, nuconf) ?: return@withContext emptyMap()
            val sessao = wms.backend.separacao.SeparacaoRepository.buscarSessaoMaisRecentePorNota(tenantId, nunota) ?: return@withContext emptyMap()
            wms.backend.separacao.SeparacaoRepository.listarItens(tenantId, java.util.UUID.fromString(sessao.id), incluirSilenciosos = true)
                .filter { it.usaConfPeso }
                .mapNotNull { item -> chaveDescricao(item.descricaoProduto, item.complementoDescricao)?.let { it to item } }
                .toMap()
        }

        return buscarPendentesRaw(tenantSlug, nuconf).map { linha ->
            val obs = parseObservacaoLiberacao(linha["OBSERVACAO"])
            val itemPesavel = obs.produto?.trim()?.uppercase()?.let { itensPesaveisPorDescricao[it] }

            if (itemPesavel != null) {
                val pedido = itemPesavel.qtdNeg.toDoubleOrNull()
                val conferido = itemPesavel.qtdConferidaLocal.toDoubleOrNull()
                LiberacaoPendenteDto(
                    sequencia = linha["SEQUENCIA"]?.toIntOrNull() ?: 0,
                    produto = obs.produto,
                    qtdPedido = pedido,
                    unidadePedido = itemPesavel.unidadePadrao,
                    qtdConferida = conferido,
                    unidadeConferida = itemPesavel.unidadePadrao,
                    diferenca = if (pedido != null && conferido != null) round3(conferido - pedido) else null,
                    pesavel = true,
                )
            } else {
                val diferenca = if (obs.qtdConferida != null && obs.qtdPedido != null) {
                    round3(obs.qtdConferida - obs.qtdPedido)
                } else {
                    null
                }
                LiberacaoPendenteDto(
                    sequencia = linha["SEQUENCIA"]?.toIntOrNull() ?: 0,
                    produto = obs.produto,
                    qtdPedido = obs.qtdPedido,
                    unidadePedido = obs.unidadePedido,
                    qtdConferida = obs.qtdConferida,
                    unidadeConferida = obs.unidadeConferida,
                    diferenca = diferenca,
                )
            }
        }
    }

    /** Aprova ('S') ou nega ('N') os itens selecionados. Se liberar e não sobrar nada pendente, finaliza a conferência. */
    suspend fun liberarOuNegar(
        tenantSlug: String,
        tenantId: java.util.UUID,
        nuconf: Int,
        usuario: String,
        senha: String,
        liberar: String,
        sequencias: List<Int>,
        obs: String?,
    ): Int {
        if (sequencias.isEmpty()) throw LiberacaoCorteException("Selecione pelo menos um item para liberar ou negar.")
        val liberarNorm = if (liberar.trim().uppercase() == "S") "S" else "N"

        val codusu = validarLiberador(tenantSlug, usuario, senha)

        val pendentes = buscarPendentesRaw(tenantSlug, nuconf)
        val selecionados = pendentes.filter { (it["SEQUENCIA"]?.toIntOrNull() ?: -1) in sequencias }
        if (selecionados.isEmpty()) {
            throw LiberacaoCorteException("Nenhum dos itens selecionados está mais pendente de liberação.")
        }

        // "Negar" só pode ser a ÚLTIMA ação da rodada — confirmado ao vivo
        // (nota 57251): assim que UM item é negado, o Sankhya muda o status
        // da conferência pra "Aguardando recontagem", e qualquer "liberar"
        // tentado depois é recusado ("Conferência não pode ser reprocessada
        // no status atual"). Defesa em camada — o frontend já trava isso na
        // UI, isto cobre chamada direta à API.
        if (liberarNorm == "N" && selecionados.size < pendentes.size) {
            throw LiberacaoCorteException(
                "Selecione e libere todos os itens pendentes antes de negar algum deles. Após uma negativa, o Sankhya não aceita novas liberações nesta conferência.",
            )
        }

        val obsFinal = obs?.trim()?.takeIf { it.isNotEmpty() }
            ?: if (liberarNorm == "S") "Liberado manualmente pela tela de Liberação de Corte"
            else "Negado manualmente pela tela de Liberação de Corte"

        try {
            chamarLiberarNegar(tenantSlug, selecionados, codusu, liberarNorm, obsFinal)
        } catch (e: Exception) {
            // Ponto cego identificado ao vivo (nota 57251): sem isto, uma falha
            // aqui vira "502 Bad Gateway" genérico na rota, sem NENHUM log —
            // impossível saber depois se foi timeout de rede ou recusa de regra
            // de negócio do Sankhya (ex.: liberar após negar). Loga a mensagem
            // real antes de repropagar, mesmo padrão de autoLiberarPesoDentroTolerancia.
            println("AVISO: falha ao ${if (liberarNorm == "S") "liberar" else "negar"} corte (nuconf $nuconf, sequências $sequencias): ${e.message}")
            throw e
        }

        val nunota = withContext(Dispatchers.IO) { wms.backend.separacao.SeparacaoRepository.buscarNunotaPorNuconf(tenantId, nuconf) }

        // Registra a decisão localmente, liberado ou negado — TGFITE não guarda
        // isso em campo nenhum (ver V36); sem isto um item liberado reaparece
        // na recontagem igual a um negado de verdade.
        if (nunota != null) {
            withContext(Dispatchers.IO) {
                val itensPorDescricao = wms.backend.separacao.SeparacaoRepository.buscarSessaoMaisRecentePorNota(tenantId, nunota)
                    ?.let { sessao -> wms.backend.separacao.SeparacaoRepository.listarItens(tenantId, java.util.UUID.fromString(sessao.id), incluirSilenciosos = true) }
                    ?.mapNotNull { item -> chaveDescricao(item.descricaoProduto, item.complementoDescricao)?.let { it to item } }
                    ?.toMap()
                    ?: emptyMap()
                selecionados.forEach { linha ->
                    val obs = parseObservacaoLiberacao(linha["OBSERVACAO"])
                    val prod = obs.produto?.trim()?.uppercase() ?: return@forEach
                    val item = itensPorDescricao[prod] ?: return@forEach
                    wms.backend.separacao.SeparacaoRepository.registrarDecisaoLiberacao(
                        tenantId, nunota, item.codprod, liberado = liberarNorm == "S", nuconf = nuconf,
                        controle = item.controle,
                        qtdLiberada = if (liberarNorm == "S") obs.qtdConferida?.toBigDecimal() else null,
                    )
                }
            }
        }

        // Verifica se sobrou algo pendente INDEPENDENTE da ação ter sido liberar
        // ou negar — negar o ÚLTIMO item pendente também precisa "fechar" o
        // corte (senão o corte de um item já decidido antes, ex. o silencioso,
        // nunca é aplicado na nota de verdade).
        val restantes = runCatching { buscarPendentesRaw(tenantSlug, nuconf) }.getOrDefault(selecionados)
        if (restantes.isEmpty()) {
            // NUNCA chama ConferenciaSP.finalizarConferencia depois de um NEGAR
            // (confirmado ao vivo, nota 57500): o LiberacaoLimitesSP.liberarNegarLimites
            // já resolve a recontagem sozinho no Sankhya — chamar finalizarConferencia
            // em cima disso, mesmo condicionado por AOLIBERAR, fecha a conferência
            // como "Finalizado Divergente" em vez de abrir a recontagem do item
            // negado. Só faz sentido finalizar explicitamente quando a ação foi
            // um LIBERAR que fechou tudo sem nenhum negado pendente.
            if (liberarNorm == "S") {
                // AOLIBERAR decide se ainda assim precisa finalizar: doc oficial diz
                // que só 'R' (Reprocessar conferência) descreve finalização explícita;
                // 'M' (Marcar para recontagem) já cobre isso sozinho.
                val aoLiberar = withContext(Dispatchers.IO) {
                    val nucco = nunota?.let { wms.backend.tarefas.TarefasRepository.buscarNuccoLocal(tenantId, it) }
                    nucco?.let { wms.backend.configconferencia.ConfigConferenciaRepository.buscarPorNucco(tenantId, it) }
                        ?.campos?.get("AOLIBERAR")?.trim()?.uppercase()
                }

                if (aoLiberar != "M") {
                    runCatching {
                        SankhyaSpClient.chamarRaw(
                            tenantSlug,
                            "ConferenciaSP.finalizarConferencia",
                            "mgecom",
                            buildJsonObject {
                                putJsonObject("params") {
                                    put("nuConf", nuconf.toString())
                                    put("peso", 0)
                                    put("qtdVol", 0)
                                }
                                CLIENT_EVENT_CONFIRM.forEach { (k, v) -> put(k, v) }
                            },
                        )
                    }.onFailure {
                        println("AVISO: liberação de corte OK mas ConferenciaSP.finalizarConferencia falhou (nuconf $nuconf): ${it.message}")
                    }
                }
            }

            // Corte 100% decidido + conferência fechada no Sankhya → fecha a
            // tarefa local na hora (senão o card só some no próximo ciclo do
            // sync, que ainda vê status_operacional='aguardando_corte'). Se
            // sobrou item negado precisando de recontagem, o próprio sync
            // seguinte já corrige pra 'aguardando' assim que o Sankhya refletir
            // isso — mesmo mecanismo de auto-correção usado em outras notas.
            if (nunota != null) {
                runCatching {
                    withContext(Dispatchers.IO) {
                        wms.backend.tarefas.TarefasRepository.concluirLocalSemWriteBack(tenantId, nunota)
                    }
                }.onFailure { println("AVISO: falha ao fechar a tarefa local após liberação de corte (nuconf $nuconf): ${it.message}") }
            }
        }

        return selecionados.size
    }

    // ─── internos ────────────────────────────────────────────────────────

    private suspend fun buscarPendentesRaw(tenantSlug: String, nuconf: Int): List<Map<String, String?>> {
        // Corpo VERBATIM do legado — a ViewLiberacaoLimite quebra silenciosamente
        // com `crudListener`, então nada de reaproveitar SankhyaLoadRecordsClient aqui.
        val requestBody = buildJsonObject {
            put("dataSetID", "001")
            put("entityName", "ViewLiberacaoLimite")
            put("standAlone", true)
            putJsonArray("fields") { CAMPOS_VIEW.forEach { add(it) } }
            put("tryJoinedFields", true)
            putJsonObject("criteria") {
                put(
                    "expression",
                    "this.NUCHAVE = $nuconf AND this.TABELA = 'TGFCOI2' " +
                        "AND this.EVENTO = $EVENTO_LIBERACAO_CORTE AND this.CODUSULIB = 0",
                )
            }
        }
        val resp = SankhyaSpClient.chamarRaw(tenantSlug, "DatasetSP.loadRecords", "mge", requestBody)
        val rows = resp["result"] as? JsonArray ?: return emptyList()
        return rows.mapNotNull { row ->
            val arr = row as? JsonArray ?: return@mapNotNull null
            CAMPOS_VIEW.mapIndexed { i, campo ->
                campo to (arr.getOrNull(i) as? JsonPrimitive)?.contentOrNull
            }.toMap()
        }
    }

    private data class ObsLiberacao(
        val produto: String?,
        val qtdConferida: Double?,
        val unidadeConferida: String?,
        val qtdPedido: Double?,
        val unidadePedido: String?,
    )

    private val REGEX_OBS = Regex(
        """^Prod\.:\s*(.+?),\s*Qtd\.\s*total\s*conf\.:\s*([\d.]+)\s*(\S+),\s*Qtd\.\s*total\s*pedido/nota:\s*([\d.]+)\s*(\S+)""",
        RegexOption.IGNORE_CASE,
    )

    private fun parseObservacaoLiberacao(observacao: String?): ObsLiberacao {
        if (observacao == null) return ObsLiberacao(null, null, null, null, null)
        val m = REGEX_OBS.find(observacao.trim())
            ?: return ObsLiberacao(observacao, null, null, null, null)
        val (prod, qConf, uConf, qPed, uPed) = m.destructured
        return ObsLiberacao(
            produto = prod.trim(),
            qtdConferida = qConf.toDoubleOrNull()?.let { round3(it) },
            unidadeConferida = uConf,
            qtdPedido = qPed.toDoubleOrNull()?.let { round3(it) },
            unidadePedido = uPed,
        )
    }

    private fun round3(v: Double): Double = Math.round(v * 1000.0) / 1000.0
}
