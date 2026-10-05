package wms.backend.tv

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import org.jetbrains.exposed.sql.SqlExpressionBuilder.greaterEq
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.max
import org.jetbrains.exposed.sql.or
import org.jetbrains.exposed.sql.selectAll
import wms.backend.separacao.SeparacaoEtapasTable
import wms.backend.separacao.SeparacaoItensTable
import wms.backend.separacao.SeparacaoLeiturasTable
import wms.backend.separacao.SeparacaoLocksTable
import wms.backend.separacao.SeparacaoSessoesTable
import wms.backend.separacao.SeparacaoStatus
import wms.backend.tarefas.StatusOperacional
import wms.backend.tarefas.TarefasAuditoriaTable
import wms.backend.tarefas.TarefasRepository
import wms.backend.tarefas.TarefasTable
import wms.backend.tenancy.Modulos
import wms.backend.tenancy.TenantRepository
import wms.backend.tenancy.TenantTx
import wms.backend.usuarios.UsersTable
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.UUID

/**
 * Composição da TV (GET /api/tv/resumo) — SÓ banco local do WMS, uma transação, sem consulta por pedido
 * e sem Sankhya. Não cria estado: agrupa StatusOperacional em 4 grupos visuais.
 *
 * Limite conhecido: a faixa por etapa só enxerga etapas de conferências JÁ ABERTAS (separacao_etapas) —
 * as etapas de nota nunca aberta vêm dos itens no Sankhya (SeparacaoService.etapasFila), fora do alcance da TV.
 */
object TvService {

    const val LIMITE_PARADO_MIN = 10
    private val ZONA = ZoneId.of("America/Sao_Paulo")
    private const val MAX_FINALIZADOS = 40

    private val DISPONIVEL = setOf(StatusOperacional.AGUARDANDO, StatusOperacional.AGUARDANDO_RECONTAGEM).codigos()
    private val EM_CONFERENCIA = setOf(
        StatusOperacional.ANDAMENTO, StatusOperacional.RECONTAGEM_ANDAMENTO, StatusOperacional.AGUARDANDO_FINALIZACAO,
    ).codigos()
    private val AGUARDANDO_LIBERACAO = setOf(StatusOperacional.AGUARDANDO_CORTE, StatusOperacional.AGUARDANDO_LIBERACAO).codigos()
    private val PRONTO = setOf(
        StatusOperacional.CONCLUIDO, StatusOperacional.CONCLUIDO_DIVERGENTE,
        StatusOperacional.RECONTAGEM_CONCLUIDA, StatusOperacional.RECONTAGEM_CONCLUIDA_DIVERGENTE,
    ).codigos()
    private val DIVERGENTE = setOf(StatusOperacional.CONCLUIDO_DIVERGENTE, StatusOperacional.RECONTAGEM_CONCLUIDA_DIVERGENTE).codigos()
    private val RECONTAGEM = setOf(
        StatusOperacional.AGUARDANDO_RECONTAGEM, StatusOperacional.RECONTAGEM_ANDAMENTO,
        StatusOperacional.RECONTAGEM_CONCLUIDA, StatusOperacional.RECONTAGEM_CONCLUIDA_DIVERGENTE,
    ).codigos()

    private fun Set<StatusOperacional>.codigos() = map { it.codigo }.toSet()

    /**
     * Turnos da expedição (mesmos códigos de app.users.turno): Manhã 08:00–18:00, Noite 22:00–07:00
     * (atravessa a meia-noite). Fora das janelas = sem turno → conta desde 00:00.
     */
    private fun turnoAtual(agora: Instant): Triple<String?, String, Instant> {
        val local = agora.atZone(ZONA)
        val hoje = local.toLocalDate()
        val h = local.hour
        return when {
            h in 8..17 -> Triple("MANHA", "Manhã · 08:00–18:00", hoje.atTime(8, 0).atZone(ZONA).toInstant())
            h >= 22 -> Triple("NOITE", "Noite · 22:00–07:00", hoje.atTime(22, 0).atZone(ZONA).toInstant())
            h < 7 -> Triple("NOITE", "Noite · 22:00–07:00", hoje.minusDays(1).atTime(22, 0).atZone(ZONA).toInstant())
            else -> Triple(null, "Fora de turno", hoje.atStartOfDay(ZONA).toInstant())
        }
    }

    private data class Nota(
        val nunota: Long,
        val status: String,
        val dados: JsonObject?,
        val concluidoEm: Instant?,
    ) {
        fun campo(nome: String) = dados?.get(nome)?.jsonPrimitive?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }
        /** TGFCAB.TIPMOV: V/P = saída (venda) | C/O = entrada (compra) | outro = null. */
        val movimento: String? get() = when (campo("TIPMOV")?.uppercase()) {
            "V", "P" -> "SAIDA"
            "C", "O" -> "ENTRADA"
            else -> null
        }
        val numNota get() = campo("NUMNOTA")?.let { it.toLongOrNull() ?: it.toDoubleOrNull()?.toLong() }
        val cliente get() = campo("Parceiro.NOMEPARC")
        /** TGFCAB.PESOBRUTO em KG (0 enquanto o sync não trouxe o campo). Aceita "1543.22" e "1.543,22". */
        val pesoBrutoKg: Double get() = campo("PESOBRUTO")?.let { bruto ->
            bruto.toDoubleOrNull() ?: bruto.replace(".", "").replace(',', '.').toDoubleOrNull()
        } ?: 0.0
    }

    private data class Sessao(
        val id: UUID, val nunota: Long, val status: String, val criadoEm: Instant, val operadorId: UUID?,
        val qtdVol: Int, val recontagem: Boolean, val segmentada: Boolean,
    )

    /** [movimento]: "saida" | "entrada" | qualquer outro = todos. Filtra TODAS as contagens da TV pelo TIPMOV. */
    fun resumo(tenantId: UUID, movimento: String? = null): TvResumoDto {
        val filtro = when (movimento?.lowercase()) {
            "saida" -> "SAIDA"
            "entrada" -> "ENTRADA"
            else -> null
        }
        val agora = Instant.now()
        val inicioDoDia = LocalDate.now(ZONA).atStartOfDay(ZONA).toInstant()
        val limiteLock = agora.minusSeconds(LIMITE_PARADO_MIN * 60L)
        val segmentado = TenantRepository.modulosHabilitados(tenantId).contains(Modulos.CONFERENCIA_SEGMENTADA)
        val (turnoCodigo, turnoRotulo, inicioTurno) = turnoAtual(agora)
        // A auditoria precisa cobrir o turno da noite que começou ontem 22:00.
        val inicioBusca = minOf(inicioDoDia, inicioTurno)

        val composto = TenantTx.run(tenantId) {
            // 1) Todas as notas do espelho (mesmo universo da Fila).
            val todasNotas = TarefasTable.selectAll().where { TarefasTable.tenantId eq tenantId }.map { r ->
                Nota(
                    nunota = r[TarefasTable.nunota].toLong(),
                    status = r[TarefasTable.statusOperacional],
                    dados = runCatching { Json.parseToJsonElement(r[TarefasTable.dados]) as JsonObject }.getOrNull(),
                    concluidoEm = r[TarefasTable.concluidoEm],
                )
            }
            // Entrada (compra) x saída (venda): o filtro vale pra TUDO abaixo (contadores, cartões, etapas, médias).
            // Pedido de entrega sem Ordem de Carga não conta na TV (TarefasRepository.entregaSemOrdemCarga).
            val visiveis = todasNotas.filterNot { TarefasRepository.entregaSemOrdemCarga(it.dados) }
            val notas = if (filtro == null) visiveis else visiveis.filter { it.movimento == filtro }
            val nunotasFiltradas = notas.map { it.nunota }.toSet()
            val emConf = notas.filter { it.status in EM_CONFERENCIA }
            val prontas = notas.filter { it.status in PRONTO }

            // 2) Conclusão real: transição pra "pronto" na auditoria (sync — vale conclusão feita no Sankhya)
            //    ou concluido_em local (finalização pelo WMS). Pega a mais recente.
            val conclusaoAuditoria = HashMap<Long, Instant>()
            if (prontas.isNotEmpty()) {
                TarefasAuditoriaTable.selectAll().where {
                    (TarefasAuditoriaTable.tenantId eq tenantId) and
                        (TarefasAuditoriaTable.criadoEm greaterEq inicioBusca) and
                        (TarefasAuditoriaTable.statusNovo inList PRONTO)
                }.forEach { r ->
                    val n = r[TarefasAuditoriaTable.nunota].toLong()
                    val t = r[TarefasAuditoriaTable.criadoEm]
                    if ((conclusaoAuditoria[n] ?: Instant.MIN) < t) conclusaoAuditoria[n] = t
                }
            }
            fun conclusaoDe(n: Nota): Instant? = listOfNotNull(conclusaoAuditoria[n.nunota], n.concluidoEm).maxOrNull()
            val prontasJanela = prontas.mapNotNull { n -> conclusaoDe(n)?.takeIf { it >= inicioBusca }?.let { n to it } }
            val prontasHoje = prontasJanela.filter { it.second >= inicioDoDia }
            val prontasTurno = prontasJanela.filter { it.second >= inicioTurno }

            // 3) Sessões das notas em conferência e das prontas hoje (uma consulta).
            val nunotasSessao = (emConf.map { it.nunota } + prontasJanela.map { it.first.nunota }).map { it.toInt() }.distinct()
            val sessoes = if (nunotasSessao.isEmpty()) emptyList() else SeparacaoSessoesTable.selectAll().where {
                (SeparacaoSessoesTable.tenantId eq tenantId) and (SeparacaoSessoesTable.nunota inList nunotasSessao)
            }.map { r ->
                Sessao(
                    id = r[SeparacaoSessoesTable.id], nunota = r[SeparacaoSessoesTable.nunota].toLong(),
                    status = r[SeparacaoSessoesTable.status], criadoEm = r[SeparacaoSessoesTable.criadoEm],
                    operadorId = r[SeparacaoSessoesTable.operadorId], qtdVol = r[SeparacaoSessoesTable.qtdVol],
                    recontagem = r[SeparacaoSessoesTable.recontagem], segmentada = r[SeparacaoSessoesTable.conferenciaSegmentada],
                )
            }
            val ativas = sessoes.filter { it.status == SeparacaoStatus.CARREGANDO || it.status == SeparacaoStatus.PRONTA }
            val ativaPorNunota = ativas.groupBy { it.nunota }.mapValues { (_, l) -> l.maxBy { it.criadoEm } }
            val concluidaPorNunota = sessoes.filter { it.status == SeparacaoStatus.CONCLUIDA }
                .groupBy { it.nunota }.mapValues { (_, l) -> l.maxBy { it.criadoEm } }
            val idsAtivas = ativaPorNunota.values.map { it.id }

            // 4) Locks, último bipe, etapas e itens das sessões ativas (uma consulta cada).
            val locks = if (idsAtivas.isEmpty()) emptyList() else SeparacaoLocksTable.selectAll().where {
                (SeparacaoLocksTable.tenantId eq tenantId) and (SeparacaoLocksTable.sessaoId inList idsAtivas)
            }.map { Triple(it[SeparacaoLocksTable.sessaoId], it[SeparacaoLocksTable.tipoSeparacao].toInt(), it) }
            val maxLeitura = SeparacaoLeiturasTable.criadoEm.max()
            val ultimoBipe = if (idsAtivas.isEmpty()) emptyMap() else SeparacaoLeiturasTable
                .select(SeparacaoLeiturasTable.sessaoId, maxLeitura)
                .where { (SeparacaoLeiturasTable.tenantId eq tenantId) and (SeparacaoLeiturasTable.sessaoId inList idsAtivas) }
                .groupBy(SeparacaoLeiturasTable.sessaoId)
                .associate { it[SeparacaoLeiturasTable.sessaoId] to it[maxLeitura] }

            val idsEtapas = idsAtivas
            val etapas = SeparacaoEtapasTable.selectAll().where {
                (SeparacaoEtapasTable.tenantId eq tenantId) and (
                    (if (idsEtapas.isEmpty()) org.jetbrains.exposed.sql.Op.FALSE else (SeparacaoEtapasTable.sessaoId inList idsEtapas)) or
                        (SeparacaoEtapasTable.concluidaEm greaterEq inicioDoDia)
                    )
            }.toList().let { linhas ->
                // Etapas concluídas hoje de sessões fora do universo filtrado (outro movimento) saem da conta.
                val idsConhecidos = sessoes.map { it.id }.toSet()
                val desconhecidas = linhas.map { it[SeparacaoEtapasTable.sessaoId] }.filter { it !in idsConhecidos }.distinct()
                val nunotaPorSessao = if (desconhecidas.isEmpty()) emptyMap() else SeparacaoSessoesTable.selectAll().where {
                    (SeparacaoSessoesTable.tenantId eq tenantId) and (SeparacaoSessoesTable.id inList desconhecidas)
                }.associate { it[SeparacaoSessoesTable.id] to it[SeparacaoSessoesTable.nunota].toLong() }
                linhas.filter { r ->
                    val sid = r[SeparacaoEtapasTable.sessaoId]
                    sid in idsConhecidos || nunotaPorSessao[sid] in nunotasFiltradas
                }
            }.map { r ->
                object {
                    val sessaoId = r[SeparacaoEtapasTable.sessaoId]
                    val tipo = r[SeparacaoEtapasTable.tipoSeparacao].toInt()
                    val status = r[SeparacaoEtapasTable.status]
                    val concluidaEm = r[SeparacaoEtapasTable.concluidaEm]
                    val divergente = r[SeparacaoEtapasTable.divergente]
                    val qtdVol = r[SeparacaoEtapasTable.qtdVol]
                }
            }

            // Itens das sessões ativas: [total, conferidos] por sessão e por (sessão, etapa). Mesma regra de
            // progresso da Fila (SeparacaoRepository.progressoEtapasPorNunota): linha conferida = conferido >= negociado.
            val porSessao = HashMap<UUID, IntArray>()
            val porSessaoEtapa = HashMap<Pair<UUID, Int>, IntArray>()
            if (idsAtivas.isNotEmpty()) {
                SeparacaoItensTable.selectAll().where {
                    (SeparacaoItensTable.tenantId eq tenantId) and (SeparacaoItensTable.sessaoId inList idsAtivas) and
                        (SeparacaoItensTable.foraPedido eq false) and (SeparacaoItensTable.silencioso eq false)
                }.forEach { r ->
                    val s = r[SeparacaoItensTable.sessaoId]
                    val ok = r[SeparacaoItensTable.qtdConferidaLocal] >= r[SeparacaoItensTable.qtdNeg]
                    for (acc in listOf(porSessao.getOrPut(s) { intArrayOf(0, 0) },
                        porSessaoEtapa.getOrPut(s to r[SeparacaoItensTable.tipoSeparacao].toInt()) { intArrayOf(0, 0) })) {
                        acc[0]++
                        if (ok) acc[1]++
                    }
                }
            }

            // Nomes (operador do crachá / dono do lock).
            val idsUsuarios = (ativas.mapNotNull { it.operadorId } +
                locks.flatMap { listOfNotNull(it.third[SeparacaoLocksTable.operadorId], it.third[SeparacaoLocksTable.userId]) }).distinct()
            val nomes = if (idsUsuarios.isEmpty()) emptyMap() else UsersTable.selectAll().where {
                (UsersTable.tenantId eq tenantId) and (UsersTable.id inList idsUsuarios)
            }.associate { it[UsersTable.id] to it[UsersTable.nome] }

            // ---- Cartões "Em conferência agora" ----
            val cards = emConf.map { n ->
                val s = ativaPorNunota[n.nunota]
                val locksDaSessao = locks.filter { it.first == s?.id }
                    .filter { it.third[SeparacaoLocksTable.ultimaAtividade] >= limiteLock }
                    .sortedByDescending { it.third[SeparacaoLocksTable.ultimaAtividade] }
                val lockAtual = locksDaSessao.firstOrNull()
                val etapaAtual = lockAtual?.second?.takeIf { it > 0 }
                val etapasDaSessao = etapas.filter { it.sessaoId == s?.id }
                val atividade = listOfNotNull(
                    s?.let { ultimoBipe[it.id] },
                    locks.filter { it.first == s?.id }.maxOfOrNull { it.third[SeparacaoLocksTable.ultimaAtividade] },
                ).maxOrNull()
                val conferenteId = s?.operadorId
                    ?: lockAtual?.third?.get(SeparacaoLocksTable.operadorId)
                    ?: lockAtual?.third?.get(SeparacaoLocksTable.userId)
                val progresso = s?.let { if (etapaAtual != null) porSessaoEtapa[it.id to etapaAtual] else porSessao[it.id] }
                val volumes = s?.let { if (it.segmentada && etapasDaSessao.isNotEmpty()) etapasDaSessao.sumOf { e -> e.qtdVol } else it.qtdVol }
                TvConferenciaDto(
                    nunota = n.nunota, numNota = n.numNota, cliente = n.cliente,
                    ordemCarga = TarefasRepository.normalizarOrdemCarga(n.campo("ORDEMCARGA")),
                    express = n.campo("AD_EXPRESS")?.uppercase() == "S",
                    retira = n.campo("AD_RETIRA")?.uppercase() == "S",
                    entrega = n.campo("AD_ENTREGA")?.uppercase() == "S",
                    movimento = n.movimento,
                    status = n.status,
                    recontagem = n.status in RECONTAGEM || s?.recontagem == true,
                    etapaAtual = etapaAtual,
                    etapasPendentes = etapasDaSessao.filter { it.status == "P" }.map { it.tipo }.distinct().sorted(),
                    conferente = conferenteId?.let { nomes[it] },
                    inicioEm = s?.criadoEm?.let(::iso),
                    ultimaAtividadeEm = atividade?.let(::iso),
                    itensConferidos = progresso?.get(1),
                    itensTotal = progresso?.get(0),
                    volumes = volumes?.takeIf { it > 0 },
                )
            }.sortedWith(compareBy(nullsLast()) { it.inicioEm })

            // ---- Recém finalizados + tempo médio ----
            val finalizados = prontasHoje.sortedByDescending { it.second }.take(MAX_FINALIZADOS).map { (n, fim) ->
                val sessaoConcluida = concluidaPorNunota[n.nunota]
                val etapaDivergente = sessaoConcluida != null && etapas.any { it.sessaoId == sessaoConcluida.id && it.divergente }
                TvFinalizadoDto(
                    nunota = n.nunota, numNota = n.numNota, cliente = n.cliente, concluidoEm = iso(fim),
                    divergente = n.status in DIVERGENTE || etapaDivergente,
                    recontagem = n.status in RECONTAGEM,
                    movimento = n.movimento,
                )
            }
            fun tempoMedioMin(lista: List<Pair<Nota, Instant>>): Int? {
                val duracoes = lista.mapNotNull { (n, fim) ->
                    val inicio = concluidaPorNunota[n.nunota]?.criadoEm ?: return@mapNotNull null
                    (fim.epochSecond - inicio.epochSecond).takeIf { it > 0 }
                }
                return if (duracoes.isEmpty()) null else Math.round(duracoes.average() / 60.0).toInt()
            }
            val tempoMedio = tempoMedioMin(prontasHoje)
            val pendentes = notas.filter { it.status in DISPONIVEL || it.status in EM_CONFERENCIA || it.status in AGUARDANDO_LIBERACAO }
            fun sim(n: Nota, campo: String) = n.campo(campo)?.uppercase() == "S"
            // Ordens de carga / peso ainda a fazer: mesmos pedidos não finalizados das modalidades.
            // Pedido sem ordem de carga (retira, express) entra no peso mas não conta ordem.
            val ordensCargaPendentes = pendentes.mapNotNull { TarefasRepository.normalizarOrdemCarga(it.campo("ORDEMCARGA")) }.toSet().size
            val pesoPendenteKg = pendentes.sumOf { it.pesoBrutoKg }
            val pesoAguardandoLiberacaoKg = pendentes.filter { it.status in AGUARDANDO_LIBERACAO }.sumOf { it.pesoBrutoKg }

            // ---- Painel de ordens de carga (só saídas): uma linha por OC que ainda tem pedido a fazer ----
            val ordensCarga = notas
                .filter { it.movimento == "SAIDA" && (it.status in PRONTO || it in pendentes) }
                .groupBy { TarefasRepository.normalizarOrdemCarga(it.campo("ORDEMCARGA")) }
                .mapNotNull { (oc, doGrupo) ->
                    val aFazer = doGrupo.filter { it in pendentes }
                    if (aFazer.isEmpty()) return@mapNotNull null
                    // "Sem OC" (retira/express) só conta o que ainda falta — pronto sem OC não é de nenhuma carga.
                    val grupo = if (oc == null) aFazer else doGrupo
                    TvOrdemCargaDto(
                        ordemCarga = oc,
                        pedidos = grupo.size,
                        pedidosProntos = grupo.count { it.status in PRONTO },
                        emConferencia = aFazer.count { it.status in EM_CONFERENCIA },
                        pesoTotalKg = arredondar(grupo.sumOf { it.pesoBrutoKg }),
                        pesoPendenteKg = arredondar(aFazer.sumOf { it.pesoBrutoKg }),
                        pesoAguardandoLiberacaoKg = arredondar(aFazer.filter { it.status in AGUARDANDO_LIBERACAO }.sumOf { it.pesoBrutoKg }),
                    )
                }
                .sortedWith(compareBy(nullsLast()) { it.ordemCarga })

            // ---- Faixa por etapa (etapas reais das conferências abertas + concluídas hoje) ----
            val locksAtivos = locks.filter { it.third[SeparacaoLocksTable.ultimaAtividade] >= limiteLock }
                .map { it.first to it.second }.toSet()
            val porEtapa = if (!segmentado) emptyList() else (1..3).map { tipo ->
                val pendentesAtivas = etapas.filter { it.tipo == tipo && it.status == "P" && it.sessaoId in idsAtivas }
                val trabalhando = pendentesAtivas.count { (it.sessaoId to tipo) in locksAtivos }
                TvEtapaResumoDto(
                    tipo = tipo,
                    disponivel = pendentesAtivas.size - trabalhando,
                    emConferencia = trabalhando,
                    prontoHoje = etapas.count { it.tipo == tipo && it.status == "C" && (it.concluidaEm ?: Instant.MIN) >= inicioDoDia },
                )
            }

            TvResumoDto(
                atualizadoEm = iso(agora),
                limiteParadoMin = LIMITE_PARADO_MIN,
                segmentado = segmentado,
                movimento = filtro?.lowercase() ?: "todos",
                porMovimento = if (filtro != null) null else {
                    fun contar(mov: String) = TvMovimentoContadoresDto(
                        disponivel = notas.count { it.movimento == mov && it.status in DISPONIVEL },
                        emConferencia = notas.count { it.movimento == mov && it.status in EM_CONFERENCIA },
                        aguardandoLiberacao = notas.count { it.movimento == mov && it.status in AGUARDANDO_LIBERACAO },
                        prontoTurno = prontasTurno.count { it.first.movimento == mov },
                    )
                    TvPorMovimentoDto(saida = contar("SAIDA"), entrada = contar("ENTRADA"))
                },
                resumo = TvContadoresDto(
                    disponivel = notas.count { it.status in DISPONIVEL },
                    emConferencia = emConf.size,
                    aguardandoLiberacao = notas.count { it.status in AGUARDANDO_LIBERACAO },
                    prontoHoje = prontasHoje.size,
                    tempoMedioHojeMin = tempoMedio,
                    prontoTurno = prontasTurno.size,
                    tempoMedioTurnoMin = tempoMedioMin(prontasTurno),
                    ordensCargaPendentes = ordensCargaPendentes,
                    pesoPendenteKg = arredondar(pesoPendenteKg),
                    pesoAguardandoLiberacaoKg = arredondar(pesoAguardandoLiberacaoKg),
                ),
                turno = TvTurnoDto(codigo = turnoCodigo, rotulo = turnoRotulo, inicioEm = iso(inicioTurno)),
                modalidades = TvModalidadesDto(
                    express = pendentes.count { sim(it, "AD_EXPRESS") },
                    retira = pendentes.count { sim(it, "AD_RETIRA") },
                    entrega = pendentes.count { sim(it, "AD_ENTREGA") },
                ),
                emConferencia = cards,
                recemFinalizados = finalizados,
                porEtapa = porEtapa,
                ordensCarga = ordensCarga,
            )
        }
        return composto
    }

    /**
     * TV de Ordens de Carga: uma linha por OC de SAÍDA com pedido a conferir OU pedido conferido ainda não
     * carregado (checklist do carregamento, ver ReconferenciaService.resumoCarregamento). OC com tudo
     * conferido e carregado sai da lista (entra em "carregadas hoje"). Motorista/placa só do cache.
     */
    fun carga(tenantId: UUID, tenantSlug: String?): TvCargaDto {
        val agora = Instant.now()
        val inicioDoDia = LocalDate.now(ZONA).atStartOfDay(ZONA).toInstant()
        val notas = TenantTx.run(tenantId) {
            TarefasTable.selectAll().where { TarefasTable.tenantId eq tenantId }.map { r ->
                Nota(
                    nunota = r[TarefasTable.nunota].toLong(),
                    status = r[TarefasTable.statusOperacional],
                    dados = runCatching { Json.parseToJsonElement(r[TarefasTable.dados]) as JsonObject }.getOrNull(),
                    concluidoEm = r[TarefasTable.concluidoEm],
                )
            }
        }.filter { it.movimento == "SAIDA" }
            .mapNotNull { n -> TarefasRepository.normalizarOrdemCarga(n.campo("ORDEMCARGA"))?.let { it to n } }

        val pendentes = setOf(DISPONIVEL, EM_CONFERENCIA, AGUARDANDO_LIBERACAO).flatten().toSet()
        val prontas = notas.filter { it.second.status in PRONTO }.map { it.second.nunota }
        val carregamento = wms.backend.reconferencia.ReconferenciaService.resumoCarregamento(tenantId, prontas, 7)

        var carregadasHoje = 0
        val linhas = notas.groupBy({ it.first }, { it.second }).mapNotNull { (oc, doGrupo) ->
            val aConferir = doGrupo.filter { it.status in pendentes }
            val conferidas = doGrupo.filter { it.status in PRONTO }
            val pedidosACarregar = conferidas.count { n -> carregamento[n.nunota]?.let { it.carregados < it.total } == true }
            if (aConferir.isEmpty() && pedidosACarregar == 0) {
                // Tudo pronto — conta como carregada hoje se a última conclusão foi hoje.
                if (conferidas.any { (it.concluidoEm ?: Instant.MIN) >= inicioDoDia }) carregadasHoje++
                return@mapNotNull null
            }
            TvOcDto(
                ordemCarga = oc,
                fase = if (aConferir.isNotEmpty()) "CONFERINDO" else "A_CARREGAR",
                pedidos = doGrupo.size,
                pedidosConferidos = conferidas.size,
                pedidosEmConferencia = aConferir.count { it.status in EM_CONFERENCIA },
                pedidosACarregar = pedidosACarregar,
                pesoTotalKg = arredondar(doGrupo.sumOf { it.pesoBrutoKg }),
                pesoASepararKg = arredondar(aConferir.sumOf { it.pesoBrutoKg }),
                pesoAguardandoLiberacaoKg = arredondar(aConferir.filter { it.status in AGUARDANDO_LIBERACAO }.sumOf { it.pesoBrutoKg }),
            )
        }.sortedBy { it.ordemCarga }

        val transporte = tenantSlug?.let {
            runCatching { wms.backend.mapaseparacao.TransporteOrdemCarga.doCache(it, tenantId, linhas.map { l -> l.ordemCarga }) }.getOrNull()
        }.orEmpty()
        val ocs = linhas.map { l -> transporte[l.ordemCarga]?.let { t -> l.copy(motorista = t.motorista, placa = t.placa) } ?: l }

        return TvCargaDto(
            atualizadoEm = iso(agora),
            ocsAbertas = ocs.size,
            pesoASepararKg = arredondar(ocs.sumOf { it.pesoASepararKg }),
            pesoAguardandoLiberacaoKg = arredondar(ocs.sumOf { it.pesoAguardandoLiberacaoKg }),
            pedidosACarregar = notas.count { (_, n) -> n.status in PRONTO && carregamento[n.nunota]?.let { it.carregados < it.total } == true },
            ocsCarregadasHoje = carregadasHoje,
            ocs = ocs,
        )
    }

    private fun arredondar(kg: Double): Double = Math.round(kg * 10.0) / 10.0

    private fun iso(i: Instant): String = DateTimeFormatter.ISO_INSTANT.format(i)
}
