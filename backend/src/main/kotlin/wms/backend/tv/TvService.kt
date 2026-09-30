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

    private data class Nota(
        val nunota: Long,
        val status: String,
        val dados: JsonObject?,
        val concluidoEm: Instant?,
    ) {
        fun campo(nome: String) = dados?.get(nome)?.jsonPrimitive?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }
        val numNota get() = campo("NUMNOTA")?.let { it.toLongOrNull() ?: it.toDoubleOrNull()?.toLong() }
        val cliente get() = campo("Parceiro.NOMEPARC")
    }

    private data class Sessao(
        val id: UUID, val nunota: Long, val status: String, val criadoEm: Instant, val operadorId: UUID?,
        val qtdVol: Int, val recontagem: Boolean, val segmentada: Boolean,
    )

    fun resumo(tenantId: UUID): TvResumoDto {
        val agora = Instant.now()
        val inicioDoDia = LocalDate.now(ZONA).atStartOfDay(ZONA).toInstant()
        val limiteLock = agora.minusSeconds(LIMITE_PARADO_MIN * 60L)
        val segmentado = TenantRepository.modulosHabilitados(tenantId).contains(Modulos.CONFERENCIA_SEGMENTADA)

        val composto = TenantTx.run(tenantId) {
            // 1) Todas as notas do espelho (mesmo universo da Fila).
            val notas = TarefasTable.selectAll().where { TarefasTable.tenantId eq tenantId }.map { r ->
                Nota(
                    nunota = r[TarefasTable.nunota].toLong(),
                    status = r[TarefasTable.statusOperacional],
                    dados = runCatching { Json.parseToJsonElement(r[TarefasTable.dados]) as JsonObject }.getOrNull(),
                    concluidoEm = r[TarefasTable.concluidoEm],
                )
            }
            val emConf = notas.filter { it.status in EM_CONFERENCIA }
            val prontas = notas.filter { it.status in PRONTO }

            // 2) Conclusão real: transição pra "pronto" na auditoria (sync — vale conclusão feita no Sankhya)
            //    ou concluido_em local (finalização pelo WMS). Pega a mais recente.
            val conclusaoAuditoria = HashMap<Long, Instant>()
            if (prontas.isNotEmpty()) {
                TarefasAuditoriaTable.selectAll().where {
                    (TarefasAuditoriaTable.tenantId eq tenantId) and
                        (TarefasAuditoriaTable.criadoEm greaterEq inicioDoDia) and
                        (TarefasAuditoriaTable.statusNovo inList PRONTO)
                }.forEach { r ->
                    val n = r[TarefasAuditoriaTable.nunota].toLong()
                    val t = r[TarefasAuditoriaTable.criadoEm]
                    if ((conclusaoAuditoria[n] ?: Instant.MIN) < t) conclusaoAuditoria[n] = t
                }
            }
            fun conclusaoDe(n: Nota): Instant? = listOfNotNull(conclusaoAuditoria[n.nunota], n.concluidoEm).maxOrNull()
            val prontasHoje = prontas.mapNotNull { n -> conclusaoDe(n)?.takeIf { it >= inicioDoDia }?.let { n to it } }

            // 3) Sessões das notas em conferência e das prontas hoje (uma consulta).
            val nunotasSessao = (emConf.map { it.nunota } + prontasHoje.map { it.first.nunota }).map { it.toInt() }.distinct()
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
                )
            }
            val duracoes = prontasHoje.mapNotNull { (n, fim) ->
                val inicio = concluidaPorNunota[n.nunota]?.criadoEm ?: return@mapNotNull null
                val seg = fim.epochSecond - inicio.epochSecond
                seg.takeIf { it > 0 }
            }
            val tempoMedio = if (duracoes.isEmpty()) null else Math.round(duracoes.average() / 60.0).toInt()

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
                resumo = TvContadoresDto(
                    disponivel = notas.count { it.status in DISPONIVEL },
                    emConferencia = emConf.size,
                    aguardandoLiberacao = notas.count { it.status in AGUARDANDO_LIBERACAO },
                    prontoHoje = prontasHoje.size,
                    tempoMedioHojeMin = tempoMedio,
                ),
                emConferencia = cards,
                recemFinalizados = finalizados,
                porEtapa = porEtapa,
            )
        }
        return composto
    }

    private fun iso(i: Instant): String = DateTimeFormatter.ISO_INSTANT.format(i)
}
