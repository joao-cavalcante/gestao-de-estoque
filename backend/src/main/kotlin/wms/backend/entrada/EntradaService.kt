package wms.backend.entrada

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.inList
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.selectAll
import wms.backend.auth.ClaimsToken
import wms.backend.permissoes.PermissoesRecurso
import wms.backend.separacao.AjustePesoCompra
import wms.backend.separacao.CompraAjustePesoItensTable
import wms.backend.separacao.SeparacaoItensTable
import wms.backend.separacao.SeparacaoSessoesTable
import wms.backend.separacao.SeparacaoStatus
import wms.backend.tarefas.AjustePesoDto
import wms.backend.tarefas.StatusOperacional
import wms.backend.tarefas.TarefaApiDto
import wms.backend.tarefas.TarefasRepository
import wms.backend.tenancy.TenantTx
import wms.backend.usuarios.UsersTable
import java.math.BigDecimal
import java.time.LocalDate
import java.util.UUID

/**
 * Tela Entrada e mesa de liberação (19/11/2026 — `docs/projetos/torre-enderecamento-entrada.md` no Hub, etapa 2,
 * primeira entrega só de visibilidade). A compra (TIPMOV C/O) sai da Fila de Conferência e aparece aqui; conferir
 * continua na tela de conferência de sempre. Nada é gravado no Sankhya: a confirmação da compra continua manual e a
 * Torre só acompanha (aprovar pela mesa depende do addon, etapa 1).
 */
object EntradaService {

    /** Conferida e liberada há mais que isso (pela DTNEG) sai da lista — mesma ideia da janela de 15 dias da Fila. */
    private const val DIAS_LIBERADAS = 15L
    /** Conferida e ainda não liberada continua visível por mais tempo (é pendência de alguém). */
    private const val DIAS_PENDENTES = 60L

    private val CONCLUIDOS = setOf(
        StatusOperacional.CONCLUIDO.codigo, StatusOperacional.CONCLUIDO_DIVERGENTE.codigo,
        StatusOperacional.RECONTAGEM_CONCLUIDA.codigo, StatusOperacional.RECONTAGEM_CONCLUIDA_DIVERGENTE.codigo,
    )

    /** TGFCAB.TIPMOV de entrada: C nota de compra, O pedido de compra (mesmo critério da TV de entrada). */
    fun ehEntrada(tipMov: String?): Boolean = tipMov?.trim()?.uppercase() in setOf("C", "O")

    fun conferenciaConcluida(statusOperacional: String): Boolean = statusOperacional in CONCLUIDOS

    /** Situação do recebimento a partir do status da conferência e da confirmação da nota (null = não lida). */
    fun situacao(statusOperacional: String, notaConfirmada: Boolean?): String = when (StatusOperacional.porCodigo(statusOperacional)) {
        StatusOperacional.AGUARDANDO, StatusOperacional.AGUARDANDO_LIBERACAO -> SituacaoRecebimento.AGUARDANDO_CONFERENCIA
        StatusOperacional.AGUARDANDO_CORTE -> SituacaoRecebimento.DIVERGENCIA
        StatusOperacional.ANDAMENTO, StatusOperacional.AGUARDANDO_FINALIZACAO,
        StatusOperacional.AGUARDANDO_RECONTAGEM, StatusOperacional.RECONTAGEM_ANDAMENTO -> SituacaoRecebimento.EM_CONFERENCIA
        StatusOperacional.CONCLUIDO, StatusOperacional.CONCLUIDO_DIVERGENTE,
        StatusOperacional.RECONTAGEM_CONCLUIDA, StatusOperacional.RECONTAGEM_CONCLUIDA_DIVERGENTE ->
            if (notaConfirmada == true) SituacaoRecebimento.LIBERADA else SituacaoRecebimento.AGUARDANDO_LIBERACAO
    }

    /** Classificação do item na mesa (puro, testável). */
    fun tipoItem(qtdNota: BigDecimal, qtdConferida: BigDecimal, foraPedido: Boolean, ajustePeso: BigDecimal?): String = when {
        foraPedido -> TipoItemMesa.FORA_PEDIDO
        ajustePeso != null && ajustePeso.signum() != 0 -> TipoItemMesa.AJUSTE
        qtdConferida < qtdNota -> TipoItemMesa.FALTA
        qtdConferida > qtdNota -> TipoItemMesa.SOBRA
        else -> TipoItemMesa.OK
    }

    /** Compras que o sync deve reler no Sankhya: conferidas e dentro da janela de exibição. */
    fun comprasParaReler(tenantId: UUID): List<Long> =
        TarefasRepository.listar(tenantId)
            .filter { ehEntrada(it.tipMov) && conferenciaConcluida(it.statusOperacional) && dentroDaJanela(it.dataMovimento, DIAS_PENDENTES) }
            .map { it.nunota }

    fun listar(claims: ClaimsToken): List<RecebimentoDto> {
        val tenantId = claims.tenantId
        val situacoes = EntradaSituacao.doCache(tenantId)
        val compras = filtrarPorTop(claims, TarefasRepository.listar(tenantId).filter { ehEntrada(it.tipMov) })
        val ajustes = runCatching { AjustePesoCompra.situacaoPorNota(tenantId, compras.map { it.nunota }) }
            .getOrElse { println("AVISO: ajustes de peso da Entrada: ${it.message}"); emptyMap() }
        return compras
            .map { t -> recebimento(t, situacoes[t.nunota], ajustes[t.nunota]) }
            .filter { r -> visivel(r) }
    }

    fun mesa(claims: ClaimsToken, nunota: Long): MesaLiberacaoDto? {
        val tenantId = claims.tenantId
        val tarefa = filtrarPorTop(claims, TarefasRepository.listar(tenantId).filter { it.nunota == nunota && ehEntrada(it.tipMov) })
            .firstOrNull() ?: return null
        val ajustes = runCatching { AjustePesoCompra.situacaoPorNota(tenantId, listOf(nunota)) }.getOrElse { emptyMap() }
        val rec = recebimento(tarefa, EntradaSituacao.doCache(tenantId)[nunota], ajustes[nunota])
        val conf = conferenciaDaTorre(tenantId, nunota)
        return MesaLiberacaoDto(
            recebimento = rec,
            itens = conf?.itens.orEmpty(),
            conferenciaNaTorre = conf != null,
            conferente = conf?.conferente,
            conferenciaInicio = conf?.inicio,
            conferenciaFim = conf?.fim,
            recontagem = conf?.recontagem ?: false,
            situacaoLidaEm = EntradaSituacao.lidoEm(tenantId)?.toString(),
            aprovacaoDisponivel = EntradaAprovacao.disponivel(tenantId),
        )
    }

    private fun recebimento(t: TarefaApiDto, s: EntradaSituacao.Situacao?, ajustes: List<AjustePesoCompra.SituacaoAjuste>?): RecebimentoDto {
        val confirmada = s?.confirmada
        return RecebimentoDto(
            nunota = t.nunota,
            numeroNota = t.numeroNota,
            codigoParceiro = t.codigoParceiro,
            fornecedor = t.nomeParceiro,
            codigoEmpresa = t.codigoEmpresa,
            nomeEmpresa = t.nomeEmpresa,
            dataMovimento = t.dataMovimento,
            codigoTipoOperacao = t.codigoTipoOperacao,
            tipoOperacao = t.descricaoTipoOperacao,
            tipMov = t.tipMov,
            statusConferencia = t.statusOperacional,
            situacao = situacao(t.statusOperacional, confirmada),
            pesoBruto = t.pesoBruto,
            andamento = t.andamento,
            ajustesPeso = ajustes?.map { AjustePesoDto(it.sentido, it.status, it.nunotaAjuste, it.erro) },
            notaConfirmada = confirmada,
            ajustePendente = confirmada == true && ajustes.orEmpty().any { it.status !in setOf("confirmado", "cancelado") },
            devolucao = s?.devolucao,
            complemento = s?.complemento,
            segundosDesdeSync = t.segundosDesdeSync,
        )
    }

    /** Liberada: só os últimos [DIAS_LIBERADAS] dias. Conferida sem liberação: [DIAS_PENDENTES]. Em aberto: sempre. */
    private fun visivel(r: RecebimentoDto): Boolean = when (r.situacao) {
        SituacaoRecebimento.LIBERADA -> r.ajustePendente || dentroDaJanela(r.dataMovimento, DIAS_LIBERADAS)
        SituacaoRecebimento.AGUARDANDO_LIBERACAO -> dentroDaJanela(r.dataMovimento, DIAS_PENDENTES)
        else -> true
    }

    /** DTNEG ("dd/MM/yyyy ...") nos últimos [dias]. Data ilegível = entra. */
    internal fun dentroDaJanela(dataMovimento: String?, dias: Long, hoje: LocalDate = LocalDate.now()): Boolean {
        val m = Regex("""^(\d{2})/(\d{2})/(\d{4})""").find(dataMovimento?.trim() ?: return true) ?: return true
        val (d, mes, a) = m.destructured
        val data = runCatching { LocalDate.of(a.toInt(), mes.toInt(), d.toInt()) }.getOrNull() ?: return true
        return !data.isBefore(hoje.minusDays(dias))
    }

    private fun filtrarPorTop(claims: ClaimsToken, tarefas: List<TarefaApiDto>): List<TarefaApiDto> {
        if (PermissoesRecurso.acessoTotal(claims)) return tarefas
        val restritas = PermissoesRecurso.topsRestritas(claims.tenantId)
        if (restritas.isEmpty()) return tarefas
        return tarefas.filter { PermissoesRecurso.podeUsarTop(claims, restritas, it.codigoTipoOperacao?.trim()?.toIntOrNull()) }
    }

    // ─── Conferência feita na Torre (itens da mesa) ───────────────────────────────────────────────────

    private data class ConferenciaTorre(
        val itens: List<ItemMesaDto>, val conferente: String?, val inicio: String?, val fim: String?, val recontagem: Boolean,
    )

    private data class Sessao(
        val id: UUID, val status: String, val nuconf: Int?, val recontagem: Boolean,
        val operadorId: UUID?, val criadoEm: java.time.Instant, val atualizadoEm: java.time.Instant,
    )

    /**
     * Itens da conferência da Torre: a sessão concluída mais recente e, se houve recontagem, as concluídas com o mesmo
     * NUCONF — por item vale a contagem mais recente. Sem sessão concluída, a ativa (conferência em andamento).
     */
    private fun conferenciaDaTorre(tenantId: UUID, nunota: Long): ConferenciaTorre? = TenantTx.run(tenantId) {
        val sessoes = SeparacaoSessoesTable.selectAll()
            .where { (SeparacaoSessoesTable.tenantId eq tenantId) and (SeparacaoSessoesTable.nunota eq nunota.toInt()) }
            .map {
                Sessao(
                    it[SeparacaoSessoesTable.id], it[SeparacaoSessoesTable.status], it[SeparacaoSessoesTable.nuconf],
                    it[SeparacaoSessoesTable.recontagem], it[SeparacaoSessoesTable.operadorId],
                    it[SeparacaoSessoesTable.criadoEm], it[SeparacaoSessoesTable.atualizadoEm],
                )
            }
        val concluidas = sessoes.filter { it.status == SeparacaoStatus.CONCLUIDA }.sortedByDescending { it.criadoEm }
        val ultima = concluidas.firstOrNull()
            ?: sessoes.filter { it.status in setOf(SeparacaoStatus.PRONTA, SeparacaoStatus.CARREGANDO) }.maxByOrNull { it.criadoEm }
            ?: return@run null
        // Recontagem: as concluídas da mesma conferência (NUCONF), da mais recente para a mais antiga.
        val usadas = if (ultima.status == SeparacaoStatus.CONCLUIDA && ultima.nuconf != null) {
            concluidas.filter { it.nuconf == ultima.nuconf }
        } else listOf(ultima)
        val ordem = usadas.mapIndexed { i, s -> s.id to i }.toMap()

        val linhas = SeparacaoItensTable.selectAll()
            .where { (SeparacaoItensTable.tenantId eq tenantId) and (SeparacaoItensTable.sessaoId inList usadas.map { it.id }) }
            .toList()
        // Por sequência, a linha da sessão mais recente (menor índice em [ordem]).
        val porSequencia = linhas.groupBy { it[SeparacaoItensTable.sequencia] to it[SeparacaoItensTable.foraPedido] }
            .mapValues { (_, l) -> l.minBy { ordem[it[SeparacaoItensTable.sessaoId]] ?: Int.MAX_VALUE } }

        val ajustes = CompraAjustePesoItensTable.selectAll()
            .where { (CompraAjustePesoItensTable.tenantId eq tenantId) and (CompraAjustePesoItensTable.sessaoId inList usadas.map { it.id }) }
            .associate { (it[CompraAjustePesoItensTable.codprod] to it[CompraAjustePesoItensTable.controle].trim()) to it[CompraAjustePesoItensTable.diferenca] }

        // Fora da nota sem quantidade (aberto e não bipado — compra 88444, 19/11/2026) não é ocorrência.
        val itens = porSequencia.values.filterNot { it[SeparacaoItensTable.foraPedido] && it[SeparacaoItensTable.qtdConferidaLocal].signum() == 0 }.map { row ->
            val dados = runCatching { Json.parseToJsonElement(row[SeparacaoItensTable.dados]) as JsonObject }.getOrNull()
            fun campo(n: String) = dados?.get(n)?.jsonPrimitive?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }
            val codprod = row[SeparacaoItensTable.codprod]
            val controle = row[SeparacaoItensTable.controle].trim()
            val qtdNota = row[SeparacaoItensTable.qtdNeg]
            val qtdConf = row[SeparacaoItensTable.qtdConferidaLocal]
            val ajuste = ajustes[codprod to controle]
            val foraPedido = row[SeparacaoItensTable.foraPedido]
            val nome = listOfNotNull(campo("Produto.DESCRPROD"), campo("Produto.COMPLDESC")).joinToString(" - ").ifEmpty { "Produto $codprod" }
            ItemMesaDto(
                sequencia = row[SeparacaoItensTable.sequencia],
                codprod = codprod,
                produto = nome,
                controle = controle.takeIf { it.isNotEmpty() },
                unidade = row[SeparacaoItensTable.codvol]?.trim()?.takeIf { it.isNotEmpty() },
                qtdNota = qtdNota.toDouble(),
                qtdConferida = qtdConf.toDouble(),
                diferenca = (qtdConf - qtdNota).toDouble(),
                pesavel = row[SeparacaoItensTable.usaConfPeso],
                ajustePeso = ajuste?.takeIf { it.signum() != 0 }?.toDouble(),
                tipo = tipoItem(qtdNota, qtdConf, foraPedido, ajuste),
            )
        }
        // Divergências primeiro; depois ordem alfabética pt-BR (padrão da Torre).
        val collator = java.text.Collator.getInstance(java.util.Locale.forLanguageTag("pt-BR"))
        val ordenados = itens.sortedWith(
            compareBy<ItemMesaDto> { if (it.tipo == TipoItemMesa.OK) 1 else 0 }.thenComparator { a, b -> collator.compare(a.produto, b.produto) },
        )

        val operador = ultima.operadorId?.let { op ->
            UsersTable.selectAll().where { UsersTable.id eq op }.firstOrNull()?.get(UsersTable.nome)
        }
        ConferenciaTorre(
            itens = ordenados,
            conferente = operador,
            inicio = usadas.minOf { it.criadoEm }.toString(),
            fim = if (ultima.status == SeparacaoStatus.CONCLUIDA) ultima.atualizadoEm.toString() else null,
            recontagem = usadas.size > 1 || usadas.any { it.recontagem },
        )
    }
}
