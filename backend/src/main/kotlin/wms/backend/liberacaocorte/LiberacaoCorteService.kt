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

    /**
     * Auto-liberação de corte por peso (fila-conferencia autoLiberarCortePesavel):
     * credenciais do liberador via env LIBERADOR_USUARIO / LIBERADOR_SENHA. Sem
     * elas (ou qualquer falha) → retorna false e o corte segue pra liberação manual.
     */
    suspend fun autoLiberar(tenantSlug: String, nuconf: Int): Boolean {
        val usuario = System.getenv("LIBERADOR_USUARIO")
        val senha = System.getenv("LIBERADOR_SENHA")
        if (usuario.isNullOrBlank() || senha.isNullOrBlank()) {
            println("AVISO: LIBERADOR_USUARIO/LIBERADOR_SENHA não configurados — corte $nuconf fica pra liberação manual.")
            return false
        }
        return try {
            val codusu = validarLiberador(tenantSlug, usuario, senha)
            val pendentes = buscarPendentesRaw(tenantSlug, nuconf)
            if (pendentes.isEmpty()) return false
            chamarLiberarNegar(
                tenantSlug, pendentes, codusu, "S",
                "Liberação automática — corte silencioso por divergência de peso (regra de negócio)",
            )
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

    /** Itens pendentes de liberação (ViewLiberacaoLimite) já parseados. */
    suspend fun listarPendentes(tenantSlug: String, nuconf: Int): List<LiberacaoPendenteDto> {
        return buscarPendentesRaw(tenantSlug, nuconf).map { linha ->
            val obs = parseObservacaoLiberacao(linha["OBSERVACAO"])
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

        val obsFinal = obs?.trim()?.takeIf { it.isNotEmpty() }
            ?: if (liberarNorm == "S") "Liberado manualmente pela tela de Liberação de Corte"
            else "Negado manualmente pela tela de Liberação de Corte"

        chamarLiberarNegar(tenantSlug, selecionados, codusu, liberarNorm, obsFinal)

        if (liberarNorm == "S") {
            val restantes = runCatching { buscarPendentesRaw(tenantSlug, nuconf) }.getOrDefault(emptyList())
            if (restantes.isEmpty()) {
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

                // Corte 100% liberado + conferência fechada no Sankhya → fecha a
                // tarefa local na hora (senão o card só some no próximo ciclo do
                // sync, que ainda vê status_operacional='aguardando_corte').
                runCatching {
                    val nunota = wms.backend.separacao.SeparacaoRepository.buscarNunotaPorNuconf(tenantId, nuconf)
                    if (nunota != null) {
                        withContext(Dispatchers.IO) {
                            wms.backend.tarefas.TarefasRepository.concluirLocalSemWriteBack(tenantId, nunota)
                        }
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
