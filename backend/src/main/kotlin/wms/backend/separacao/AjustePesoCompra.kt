package wms.backend.separacao

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.inList
import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.insertIgnore
import org.jetbrains.exposed.sql.javatime.timestamp
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.update
import wms.backend.erp.SankhyaDbExplorerClient
import wms.backend.erp.SankhyaSpClient
import wms.backend.tenancy.ParametrosAjustePesoCompra
import wms.backend.tenancy.TenantRepository
import wms.backend.tenancy.TenantTx
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Instant
import java.util.UUID

/** app.compra_ajuste_peso_itens (V69) — diferença da balança por produto+lote da sessão (+ a maior, - a menor). */
object CompraAjustePesoItensTable : Table("app.compra_ajuste_peso_itens") {
    val tenantId = uuid("tenant_id")
    val sessaoId = uuid("sessao_id")
    val nunota = integer("nunota")
    val nuconf = integer("nuconf")
    val codprod = integer("codprod")
    val controle = text("controle")
    val qtdNota = decimal("qtd_nota", 20, 10)
    val qtdBalanca = decimal("qtd_balanca", 20, 10)
    val diferenca = decimal("diferenca", 20, 10)
    override val primaryKey = PrimaryKey(tenantId, sessaoId, codprod, controle)
}

/** app.compra_ajuste_peso_notas (V69) — nota de ajuste por sessão e sentido ('E' entrada, 'S' saída). */
object CompraAjustePesoNotasTable : Table("app.compra_ajuste_peso_notas") {
    val id = uuid("id")
    val tenantId = uuid("tenant_id")
    val sessaoId = uuid("sessao_id")
    val nunota = integer("nunota")
    val nuconf = integer("nuconf")
    val sentido = text("sentido")
    val status = text("status")
    val nunotaAjuste = integer("nunota_ajuste").nullable()
    val tentativas = integer("tentativas")
    val ultimoErro = text("ultimo_erro").nullable()
    val criadoEm = timestamp("criado_em")
    val atualizadoEm = timestamp("atualizado_em")
    override val primaryKey = PrimaryKey(id)
}

/**
 * Ajuste de peso na COMPRA (decisões do usuário, 03–04/11/2026 — plano de ação "entrada de pesáveis na compra").
 *
 * Problema: a entrada assume o peso da balança, que quase sempre difere do peso da nota. Atualizar a quantidade
 * (CCO GERARPEDCOMPL 'A') muda o valor da nota e o financeiro; o pedido complementar ('S') gera documento extra e a
 * sobra só entra no estoque quando ele for faturado. O Sankhya não tem "subir a quantidade sem mudar o valor".
 *
 * Modelo (o mesmo do recebimento de peso variável do WMS da Sankhya — MgeWmsSPBean.gerarNotaAjusteProdutoPesoVariavel):
 *  1. A nota do fornecedor fica igual ao XML: pesável com diferença DENTRO da tolerância do NUCCO vai com o peso da
 *     nota, nos dois sentidos ([decidir]); a diferença é gravada em app.compra_ajuste_peso_itens.
 *  2. Depois que a nota do fornecedor é CONFIRMADA (estoque dela já entrou) e a conferência é a atual e finalizada,
 *     o ciclo de sincronização ([processarPendentes]) gera UMA nota de ajuste por sentido — entrada (TOP de entrada,
 *     soma a diferença) e saída (TOP de saída, debita) — com o valor unitário da nota original, e confirma.
 *  3. Acima da tolerância nada muda: vai o lido, divergência e liberação.
 *
 * Como o nativo, o cabeçalho do ajuste é o da nota do fornecedor (parceiro, empresa, natureza, centro de resultado,
 * tipo de negociação) com a TOP de ajuste, a quantidade é arredondada pelas casas decimais do produto e cada item é
 * ligado ao item de origem na TGFVAR (decisão do usuário, 05/11/2026).
 *
 * Só com parametros.ajustePesoCompra.habilitado. Desligado = regra de antes (só o peso a maior dentro da tolerância
 * vai com o peso da nota, sem ajuste). Payload do cabeçalho/item capturado da Central de Notas (nota 79759, TOP 1195).
 */
object AjustePesoCompra {
    const val ENTRADA = "E"
    const val SAIDA = "S"
    private const val MAX_TENTATIVAS = 5
    /** Marca na observação do ajuste — acha a nota de novo se a resposta do incluir se perdeu. */
    internal fun marcador(sessaoId: UUID, sentido: String) = "[TO-AJPESO $sessaoId $sentido]"

    // ─── 1. Decisão do peso enviado (puro, testável) ─────────────────────────────────────────────────

    enum class Faixa { SEM_DIFERENCA, AJUSTAR, DIVERGENCIA, REGRA_ANTIGA }

    data class Decisao(val enviar: BigDecimal, val diferenca: BigDecimal?, val faixa: Faixa = Faixa.REGRA_ANTIGA)

    /**
     * Peso enviado ao Sankhya para um pesável da compra. [tolAcimaPct]/[tolAbaixoPct] = tolerância efetiva (produto,
     * senão NUCCO; null = sem limite). Com [p] desligado vale a regra de 19/10 (a maior dentro da tolerância vai com o
     * peso da nota, sem ajuste). Ligado, duas faixas — ver [ParametrosAjustePesoCompra] (a faixa "desconsiderar"
     * saiu em 10/11/2026, decisão do usuário):
     *  2. até a tolerância, com o ajuste do sentido ligado → peso da nota + diferença (arredondada a 3 casas, a balança);
     *     "sem limite" não ajusta (nada entra no estoque sem alguém ver);
     *  3. o resto → peso lido (divergência; o Sankhya trata pela CCO — complementar/devolução, com auto-liberação
     *     dentro da tolerância).
     */
    fun decidir(
        negociado: BigDecimal?,
        lido: BigDecimal,
        tolAcimaPct: Double?,
        tolAbaixoPct: Double?,
        p: ParametrosAjustePesoCompra,
    ): Decisao {
        if (negociado == null || negociado.signum() <= 0 || lido.signum() <= 0) return Decisao(lido, null, Faixa.SEM_DIFERENCA)
        val dif = lido - negociado
        if (dif.signum() == 0) return Decisao(lido, null, Faixa.SEM_DIFERENCA)
        val aMaior = dif.signum() > 0
        val pct = dif.abs().toDouble() / negociado.toDouble() * 100
        val limite = if (aMaior) tolAcimaPct else tolAbaixoPct
        if (!p.habilitado) {
            // Regra de 19/10: só o a maior dentro da tolerância volta pro peso da nota.
            if (limite != null && pct > limite + 1e-9) return Decisao(lido, null, Faixa.DIVERGENCIA)
            return if (aMaior) Decisao(negociado, null, Faixa.REGRA_ANTIGA) else Decisao(lido, null, Faixa.REGRA_ANTIGA)
        }
        val ajusteLigado = if (aMaior) p.ajusteEntrada else p.ajusteSaida
        if (ajusteLigado && limite != null && pct <= limite + 1e-9) {
            val difBalanca = dif.setScale(3, RoundingMode.HALF_UP)
            return if (difBalanca.signum() == 0) Decisao(negociado, null, Faixa.SEM_DIFERENCA)
            else Decisao(negociado, difBalanca, Faixa.AJUSTAR)
        }
        return Decisao(lido, null, Faixa.DIVERGENCIA)
    }

    // ─── Tolerância do produto (compra) ──────────────────────────────────────────────────────────────

    /** TGFPRO.PERCTOLPESOMAIOR / PERCTOLPESOMENOR — o mesmo cadastro que o recebimento de peso variável nativo usa. */
    data class ToleranciaProduto(val acimaPct: Double?, val abaixoPct: Double?)

    /**
     * Compra (regra do usuário, 05/11/2026): tolerância preenchida no PRODUTO tem prioridade, sentido a sentido; o que o
     * produto não tiver (vazio ou 0) vem do NUCCO (cópia da sessão; null = sem limite, como sempre).
     */
    fun toleranciaEfetiva(nuccoAcima: Double?, nuccoAbaixo: Double?, produto: ToleranciaProduto?): Pair<Double?, Double?> =
        (produto?.acimaPct?.takeIf { it > 0 } ?: nuccoAcima) to (produto?.abaixoPct?.takeIf { it > 0 } ?: nuccoAbaixo)

    /** Tolerâncias preenchidas (> 0) no cadastro dos produtos. Falha na leitura = vazio (vale o NUCCO) — nunca trava. */
    suspend fun toleranciasProduto(tenantSlug: String, codprods: Collection<Int>): Map<Int, ToleranciaProduto> {
        if (codprods.isEmpty()) return emptyMap()
        return runCatching {
            SankhyaDbExplorerClient.executarQuery(
                tenantSlug,
                "SELECT P.CODPROD, P.PERCTOLPESOMAIOR, P.PERCTOLPESOMENOR FROM TGFPRO P WHERE P.CODPROD IN (${codprods.distinct().joinToString()}) " +
                    "AND (NVL(P.PERCTOLPESOMAIOR, 0) > 0 OR NVL(P.PERCTOLPESOMENOR, 0) > 0)",
            ).mapNotNull { r ->
                val cod = r["CODPROD"].inteiro() ?: return@mapNotNull null
                fun pct(c: String) = r[c]?.trim()?.replace(',', '.')?.toDoubleOrNull()?.takeIf { it > 0 }
                cod to ToleranciaProduto(pct("PERCTOLPESOMAIOR"), pct("PERCTOLPESOMENOR"))
            }.toMap()
        }.getOrElse {
            println("AVISO: tolerância de peso dos produtos não lida (vale a do NUCCO): ${it.message}")
            emptyMap()
        }
    }

    fun parametros(tenantId: UUID): ParametrosAjustePesoCompra =
        runCatching { TenantRepository.parametros(tenantId).ajustePesoCompra }.getOrElse { ParametrosAjustePesoCompra() }

    /** Grava as diferenças da sessão (idempotente: reenvio do mesmo grupo não duplica). */
    fun registrarDiferencas(
        tenantId: UUID,
        sessaoId: UUID,
        nunota: Long,
        nuconf: Int,
        diferencas: List<Triple<SeparacaoRepository.GrupoConferido, BigDecimal, BigDecimal>>,
    ) {
        if (diferencas.isEmpty()) return
        TenantTx.run(tenantId) {
            diferencas.forEach { (g, lido, dif) ->
                CompraAjustePesoItensTable.deleteWhere {
                    (CompraAjustePesoItensTable.tenantId eq tenantId) and (CompraAjustePesoItensTable.sessaoId eq sessaoId) and
                        (CompraAjustePesoItensTable.codprod eq g.codprod) and (CompraAjustePesoItensTable.controle eq g.controle)
                }
                CompraAjustePesoItensTable.insert {
                    it[CompraAjustePesoItensTable.tenantId] = tenantId
                    it[CompraAjustePesoItensTable.sessaoId] = sessaoId
                    it[CompraAjustePesoItensTable.nunota] = nunota.toInt()
                    it[CompraAjustePesoItensTable.nuconf] = nuconf
                    it[codprod] = g.codprod
                    it[controle] = g.controle
                    it[qtdNota] = g.qtdNegociada ?: BigDecimal.ZERO
                    it[qtdBalanca] = lido
                    it[diferenca] = dif
                }
            }
            listOf(ENTRADA, SAIDA).filter { s -> diferencas.any { if (s == ENTRADA) it.third.signum() > 0 else it.third.signum() < 0 } }
                .forEach { s ->
                    CompraAjustePesoNotasTable.insertIgnore {
                        it[id] = UUID.randomUUID()
                        it[CompraAjustePesoNotasTable.tenantId] = tenantId
                        it[CompraAjustePesoNotasTable.sessaoId] = sessaoId
                        it[CompraAjustePesoNotasTable.nunota] = nunota.toInt()
                        it[CompraAjustePesoNotasTable.nuconf] = nuconf
                        it[sentido] = s
                        it[status] = "pendente"
                        it[tentativas] = 0
                        it[criadoEm] = Instant.now()
                        it[atualizadoEm] = Instant.now()
                    }
                }
        }
    }

    // ─── 2. Geração no Sankhya (ciclo de sincronização) ──────────────────────────────────────────────

    private data class Doc(
        val id: UUID, val sessaoId: UUID, val nunota: Int, val nuconf: Int, val sentido: String,
        val status: String, val nunotaAjuste: Int?, val tentativas: Int,
    )

    data class ItemAjuste(val codprod: Int, val controle: String, val quantidade: BigDecimal, val qtdNota: BigDecimal)

    /** Situação dos ajustes de uma nota de compra — card da fila. */
    data class SituacaoAjuste(val sentido: String, val status: String, val nunotaAjuste: Int?, val erro: String?)

    fun situacaoPorNota(tenantId: UUID, nunotas: Collection<Long>): Map<Long, List<SituacaoAjuste>> {
        if (nunotas.isEmpty()) return emptyMap()
        return TenantTx.run(tenantId) {
            CompraAjustePesoNotasTable.selectAll()
                .where {
                    (CompraAjustePesoNotasTable.tenantId eq tenantId) and
                        (CompraAjustePesoNotasTable.nunota inList nunotas.map { it.toInt() }) and
                        (CompraAjustePesoNotasTable.status neq "cancelado")
                }
                .groupBy({ it[CompraAjustePesoNotasTable.nunota].toLong() }) {
                    SituacaoAjuste(it[CompraAjustePesoNotasTable.sentido], it[CompraAjustePesoNotasTable.status],
                        it[CompraAjustePesoNotasTable.nunotaAjuste], it[CompraAjustePesoNotasTable.ultimoErro])
                }
        }
    }

    /** Chamado a cada ciclo do TarefaSyncService. Nunca derruba o ciclo — cada documento falha sozinho. */
    suspend fun processarPendentes(tenantSlug: String, tenantId: UUID) {
        val p = withContext(Dispatchers.IO) { parametros(tenantId) }
        if (!p.habilitado) return
        val docs = withContext(Dispatchers.IO) {
            TenantTx.run(tenantId) {
                CompraAjustePesoNotasTable.selectAll()
                    .where {
                        (CompraAjustePesoNotasTable.tenantId eq tenantId) and
                            (CompraAjustePesoNotasTable.status inList listOf("pendente", "criando", "cabecalho"))
                    }
                    .limit(50)
                    .map {
                        Doc(it[CompraAjustePesoNotasTable.id], it[CompraAjustePesoNotasTable.sessaoId], it[CompraAjustePesoNotasTable.nunota],
                            it[CompraAjustePesoNotasTable.nuconf], it[CompraAjustePesoNotasTable.sentido], it[CompraAjustePesoNotasTable.status],
                            it[CompraAjustePesoNotasTable.nunotaAjuste], it[CompraAjustePesoNotasTable.tentativas])
                    }
            }
        }
        if (docs.isEmpty()) return
        val statusSessao = withContext(Dispatchers.IO) {
            TenantTx.run(tenantId) {
                SeparacaoSessoesTable.selectAll()
                    .where { (SeparacaoSessoesTable.tenantId eq tenantId) and (SeparacaoSessoesTable.id inList docs.map { it.sessaoId }.distinct()) }
                    .associate { it[SeparacaoSessoesTable.id] to it[SeparacaoSessoesTable.status] }
            }
        }
        val situacao = situacaoCompras(tenantSlug, docs.map { it.nunota }.distinct())
        for (doc in docs) {
            // Só sessão concluída vale: cancelada/invalidada (mesmo com o mesmo NUCONF) não gera ajuste — a que
            // substituiu grava as próprias diferenças. Ainda aberta: espera.
            when (statusSessao[doc.sessaoId]) {
                SeparacaoStatus.CONCLUIDA -> Unit
                null, SeparacaoStatus.CANCELADA, SeparacaoStatus.INVALIDADA -> {
                    if (doc.status == "pendente") {
                        marcar(tenantId, doc.id, "cancelado", erro = "sessão da conferência cancelada")
                        continue
                    }
                }
                else -> continue
            }
            val s = situacao[doc.nunota]
            // Nota excluída, ou conferência refeita (recontagem/excluída): as diferenças desta sessão não valem mais.
            if (s == null || s.nuconfAtual != doc.nuconf) {
                if (doc.status == "pendente") {
                    marcar(tenantId, doc.id, "cancelado", erro = if (s == null) "nota de compra não encontrada" else "conferência refeita (NUCONF ${s.nuconfAtual})")
                    println("INFO: ajuste de peso nota ${doc.nunota} (${doc.sentido}) cancelado — ${if (s == null) "nota sumiu" else "conferência refeita"}")
                    continue
                }
                // já começou a criar no Sankhya: termina o que começou (não deixa ajuste pela metade)
            } else if (s.statusNota != "L" || s.statusConf !in setOf("F", "D")) {
                continue // espera a nota do fornecedor ser confirmada com a conferência finalizada
            }
            try {
                processar(tenantSlug, tenantId, doc, p, s?.codemp)
            } catch (e: Exception) {
                val msg = (e.message ?: e::class.simpleName ?: "erro").take(1000)
                val tentativas = doc.tentativas + 1
                println("AVISO: ajuste de peso nota ${doc.nunota} (${doc.sentido}) tentativa $tentativas falhou: $msg")
                withContext(Dispatchers.IO) {
                    TenantTx.run(tenantId) {
                        CompraAjustePesoNotasTable.update({ CompraAjustePesoNotasTable.id eq doc.id }) {
                            it[CompraAjustePesoNotasTable.tentativas] = tentativas
                            it[ultimoErro] = msg
                            if (tentativas >= MAX_TENTATIVAS) it[status] = "erro"
                            it[atualizadoEm] = Instant.now()
                        }
                    }
                }
            }
        }
    }

    /** Recoloca na fila um ajuste que parou em erro (botão "Gerar ajuste de novo" do card). */
    fun reprocessar(tenantId: UUID, nunota: Long): Int = TenantTx.run(tenantId) {
        CompraAjustePesoNotasTable.update({
            (CompraAjustePesoNotasTable.tenantId eq tenantId) and (CompraAjustePesoNotasTable.nunota eq nunota.toInt()) and
                (CompraAjustePesoNotasTable.status eq "erro")
        }) {
            it[status] = "pendente"
            it[tentativas] = 0
            it[atualizadoEm] = Instant.now()
        }
    }

    private data class SituacaoCompra(val statusNota: String?, val nuconfAtual: Int?, val statusConf: String?, val codemp: Int?)

    private suspend fun situacaoCompras(tenantSlug: String, nunotas: List<Int>): Map<Int, SituacaoCompra> {
        if (nunotas.isEmpty()) return emptyMap()
        return SankhyaDbExplorerClient.executarQuery(
            tenantSlug,
            "SELECT C.NUNOTA, C.STATUSNOTA, C.NUCONFATUAL, C.CODEMP, (SELECT F.STATUS FROM TGFCON2 F WHERE F.NUCONF = C.NUCONFATUAL) AS STATUS_CONF " +
                "FROM TGFCAB C WHERE C.NUNOTA IN (${nunotas.joinToString()})",
        ).mapNotNull { r ->
            val n = r["NUNOTA"].inteiro() ?: return@mapNotNull null
            n to SituacaoCompra(r["STATUSNOTA"]?.trim(), r["NUCONFATUAL"].inteiro(), r["STATUS_CONF"]?.trim(), r["CODEMP"].inteiro())
        }.toMap()
    }

    private suspend fun processar(tenantSlug: String, tenantId: UUID, doc: Doc, p: ParametrosAjustePesoCompra, codempCompra: Int?) {
        val top = (if (doc.sentido == ENTRADA) p.topEntrada else p.topSaida) ?: error("TOP de ${nomeSentido(doc.sentido)} não configurada")
        var nunotaAjuste = doc.nunotaAjuste
        var status = doc.status
        if (status == "criando") {
            // A resposta do incluir cabeçalho se perdeu: procura pela marca antes de criar outro.
            nunotaAjuste = buscarPorMarcador(tenantSlug, top, marcador(doc.sessaoId, doc.sentido))
            if (nunotaAjuste != null) {
                marcar(tenantId, doc.id, "cabecalho", nunotaAjuste = nunotaAjuste)
                status = "cabecalho"
            } else {
                status = "pendente"
            }
        }
        if (status == "pendente") {
            val itens = itensDoSentido(tenantId, doc)
            if (itens.isEmpty()) {
                marcar(tenantId, doc.id, "cancelado", erro = "sem diferença neste sentido")
                return
            }
            // Cabeçalho = o da nota do fornecedor (como o nativo: WmsHelper.getNovoCabecalhoNota clona a nota de origem)
            // com a TOP de ajuste — fornecedor, empresa, natureza, centro de resultado e tipo de negociação da compra.
            val cab = dadosCabecalho(tenantSlug, top, doc.nunota, p.codTipVenda).let { c -> p.codParc?.let { c.copy(codParc = it) } ?: c }
            val observacao = observacaoAjuste(tenantSlug, tenantId, doc, itens)
            marcar(tenantId, doc.id, "criando")
            val resposta = SankhyaSpClient.chamarRaw(
                tenantSlug, "CACSP.incluirAlterarCabecalhoNota", "mgecom",
                payloadCabecalho(
                    run {
                        // Como o nativo: data do ajuste = agora (no fuso do cliente); entrada/saída com a hora.
                        val agora = java.time.ZonedDateTime.now(withContext(Dispatchers.IO) { wms.backend.tenancy.FusoInstalacao.zona(tenantId) })
                        cab.copy(
                            dtNeg = agora.format(java.time.format.DateTimeFormatter.ofPattern("dd/MM/yyyy")),
                            hora = agora.format(java.time.format.DateTimeFormatter.ofPattern("HH:mm:ss")),
                            serie = if (doc.sentido == ENTRADA) p.serieEntrada else p.serieSaida,
                        )
                    },
                    observacao = observacao,
                ),
                retentar = false,
            )
            nunotaAjuste = nunotaDaResposta(resposta) ?: buscarPorMarcador(tenantSlug, top, marcador(doc.sessaoId, doc.sentido))
                ?: error("o Sankhya não devolveu o número da nota de ajuste")
            marcar(tenantId, doc.id, "cabecalho", nunotaAjuste = nunotaAjuste)
            status = "cabecalho"
            println("INFO: ajuste de peso nota ${doc.nunota} (${doc.sentido}) — cabeçalho $nunotaAjuste criado (TOP $top)")
        }
        val nAj = nunotaAjuste ?: error("nota de ajuste sem número")
        // Itens: só inclui se a nota ainda está vazia (retry depois de incluir não duplica).
        if (quantidadeItens(tenantSlug, nAj) == 0) {
            val itens = itensDoSentido(tenantId, doc)
            val precos = precosDaCompra(tenantSlug, doc.nunota, itens)
            val comPreco = itens.map { i -> i to (precos[i.codprod] ?: error("produto ${i.codprod} não está na nota ${doc.nunota}")) }
                .filter { (i, preco) -> quantidadeAjuste(i.quantidade, preco.decQtd).signum() > 0 }
            if (comPreco.isEmpty()) {
                error("a diferença arredondada pelas casas decimais do produto ficou zero — exclua a nota de ajuste $nAj vazia no Sankhya")
            }
            SankhyaSpClient.chamarRaw(tenantSlug, "CACSP.incluirAlterarItemNota", "mgecom", payloadItens(nAj, codempCompra, comPreco), retentar = false)
        }
        if (statusNota(tenantSlug, nAj) != "L") {
            SeparacaoService.confirmarNota(tenantSlug, nAj.toLong())?.let { throw it }
        }
        // Vínculo item do ajuste -> item da nota do fornecedor (TGFVAR), como o nativo (geraLigacaoTGFVAR). Não trava o ajuste.
        val avisoVinculo = runCatching { ligarNotaOrigem(tenantSlug, nAj, doc.nunota) }
            .exceptionOrNull()?.let { "nota $nAj confirmada, mas o vínculo com a nota de origem não foi gravado: ${it.message}".take(1000) }
        avisoVinculo?.let { println("AVISO: ajuste de peso nota ${doc.nunota} (${doc.sentido}) — $it") }
        marcar(tenantId, doc.id, "confirmado", nunotaAjuste = nAj, erro = avisoVinculo)
        println("INFO: ajuste de peso nota ${doc.nunota} (${doc.sentido}) — nota $nAj confirmada")
    }

    /**
     * Observação da nota de ajuste com o motivo (usuário, 10/11/2026): peso da nota x pesado por produto, percentual e
     * tolerância da sessão. Mantém o [marcador] (a busca pela nota depende dele). Falha ao montar = texto curto.
     */
    private suspend fun observacaoAjuste(tenantSlug: String, tenantId: UUID, doc: Doc, itens: List<ItemAjuste>): String {
        val marca = marcador(doc.sessaoId, doc.sentido)
        return runCatching {
            val entrada = doc.sentido == ENTRADA
            val (acima, abaixo) = withContext(Dispatchers.IO) {
                TenantTx.run(tenantId) {
                    SeparacaoSessoesTable.selectAll().where { SeparacaoSessoesTable.id eq doc.sessaoId }.firstOrNull()
                        ?.let { it[SeparacaoSessoesTable.tolPesoAcimaPct] to it[SeparacaoSessoesTable.tolPesoAbaixoPct] }
                } ?: (null to null)
            }
            val nomes = MotivoDivergenciaCompra.descricoes(tenantSlug, itens.map { it.codprod })
            val linhas = itens.map { i ->
                val pesado = if (entrada) i.qtdNota + i.quantidade else i.qtdNota - i.quantidade
                MotivoDivergenciaCompra.LinhaItem(i.codprod, "KG", i.qtdNota, pesado) to nomes[i.codprod]
            }
            MotivoDivergenciaCompra.textoAjuste(doc.nunota, doc.nuconf, entrada, linhas, acima, abaixo, marca)
        }.getOrElse { "Ajuste de peso da nota ${doc.nunota} (Torre de Operação) $marca" }.take(4000)
    }

    /** Quantidade do ajuste arredondada pelas casas decimais do produto (TGFPRO.DECQTD; sem valor = 3, a balança). */
    internal fun quantidadeAjuste(diferenca: BigDecimal, decQtd: Int?): BigDecimal =
        diferenca.abs().setScale((decQtd ?: 3).coerceIn(0, 6), RoundingMode.HALF_UP)

    /**
     * TGFVAR: cada item do ajuste aponta pro item do mesmo produto+lote na nota do fornecedor (NUNOTAORIG/SEQUENCIAORIG,
     * STATUSNOTA da nota de ajuste) — a Central de Notas mostra a origem. Idempotente: item já ligado não é regravado.
     */
    private suspend fun ligarNotaOrigem(tenantSlug: String, nunotaAjuste: Int, nunotaOrigem: Int) {
        val ajuste = SankhyaDbExplorerClient.executarQuery(
            tenantSlug,
            "SELECT I.SEQUENCIA, I.CODPROD, NVL(TRIM(I.CONTROLE), ' ') CONTROLE, C.STATUSNOTA, " +
                "(SELECT COUNT(1) FROM TGFVAR V WHERE V.NUNOTA = I.NUNOTA AND V.SEQUENCIA = I.SEQUENCIA) LIGADO " +
                "FROM TGFITE I JOIN TGFCAB C ON C.NUNOTA = I.NUNOTA WHERE I.NUNOTA = $nunotaAjuste",
        )
        val origem = SankhyaDbExplorerClient.executarQuery(
            tenantSlug,
            "SELECT MIN(I.SEQUENCIA) SEQUENCIA, I.CODPROD, NVL(TRIM(I.CONTROLE), ' ') CONTROLE FROM TGFITE I " +
                "WHERE I.NUNOTA = $nunotaOrigem AND I.SEQUENCIA > 0 GROUP BY I.CODPROD, NVL(TRIM(I.CONTROLE), ' ')",
        ).associate { (it["CODPROD"].inteiro() to it["CONTROLE"].orEmpty()) to it["SEQUENCIA"].inteiro() }
        ajuste.filter { (it["LIGADO"].inteiro() ?: 0) == 0 }.forEach { r ->
            val seq = r["SEQUENCIA"].inteiro() ?: return@forEach
            val seqOrig = origem[r["CODPROD"].inteiro() to r["CONTROLE"].orEmpty()] ?: return@forEach
            SankhyaSpClient.chamarRaw(
                tenantSlug, "CRUDServiceProvider.saveRecord", "mge",
                payloadVinculo(nunotaAjuste, seq, nunotaOrigem, seqOrig, r["STATUSNOTA"]?.trim().orEmpty().ifEmpty { "L" }),
                retentar = false,
            )
        }
    }

    internal fun payloadVinculo(nunota: Int, sequencia: Int, nunotaOrig: Int, sequenciaOrig: Int, statusNota: String): JsonObject =
        buildJsonObject {
            putJsonObject("dataSet") {
                put("rootEntity", "CompraVendavariosPedido")
                put("includePresentationFields", "N")
                putJsonObject("dataRow") {
                    putJsonObject("localFields") {
                        putJsonObject("NUNOTA") { put("$", nunota.toString()) }
                        putJsonObject("SEQUENCIA") { put("$", sequencia.toString()) }
                        putJsonObject("NUNOTAORIG") { put("$", nunotaOrig.toString()) }
                        putJsonObject("SEQUENCIAORIG") { put("$", sequenciaOrig.toString()) }
                        putJsonObject("STATUSNOTA") { put("$", statusNota) }
                    }
                }
                putJsonObject("entity") { putJsonObject("fieldset") { put("list", "NUNOTA,SEQUENCIA,NUNOTAORIG,SEQUENCIAORIG,STATUSNOTA") } }
            }
        }

    private fun nomeSentido(s: String) = if (s == ENTRADA) "entrada" else "saída"

    private suspend fun itensDoSentido(tenantId: UUID, doc: Doc): List<ItemAjuste> = withContext(Dispatchers.IO) {
        TenantTx.run(tenantId) {
            CompraAjustePesoItensTable.selectAll()
                .where { (CompraAjustePesoItensTable.tenantId eq tenantId) and (CompraAjustePesoItensTable.sessaoId eq doc.sessaoId) }
                .map { ItemAjuste(it[CompraAjustePesoItensTable.codprod], it[CompraAjustePesoItensTable.controle], it[CompraAjustePesoItensTable.diferenca], it[CompraAjustePesoItensTable.qtdNota]) }
                .filter { if (doc.sentido == ENTRADA) it.quantidade.signum() > 0 else it.quantidade.signum() < 0 }
                .map { it.copy(quantidade = it.quantidade.abs()) }
        }
    }

    private fun marcar(tenantId: UUID, id: UUID, novo: String, nunotaAjuste: Int? = null, erro: String? = "") {
        TenantTx.run(tenantId) {
            CompraAjustePesoNotasTable.update({ CompraAjustePesoNotasTable.id eq id }) {
                it[status] = novo
                if (nunotaAjuste != null) it[CompraAjustePesoNotasTable.nunotaAjuste] = nunotaAjuste
                if (erro != "") it[ultimoErro] = erro
                it[atualizadoEm] = Instant.now()
            }
        }
    }

    /** Preço por item: valor unitário da nota original na unidade padrão = VLRTOT do produto / quantidade da nota. */
    internal data class PrecoItem(val vlrUnit: BigDecimal, val codvol: String, val codLocal: Int?, val decQtd: Int? = null)

    private suspend fun precosDaCompra(tenantSlug: String, nunota: Int, itens: List<ItemAjuste>): Map<Int, PrecoItem> {
        if (itens.isEmpty()) return emptyMap()
        val codprods = itens.map { it.codprod }.distinct()
        val linhas = SankhyaDbExplorerClient.executarQuery(
            tenantSlug,
            "SELECT I.CODPROD, SUM(I.VLRTOT) VLRTOT, MAX(I.CODLOCALORIG) CODLOCAL, MAX(P.CODVOL) CODVOLPAD, MAX(P.DECQTD) DECQTD " +
                "FROM TGFITE I JOIN TGFPRO P ON P.CODPROD = I.CODPROD WHERE I.NUNOTA = $nunota AND I.CODPROD IN (${codprods.joinToString()}) " +
                "GROUP BY I.CODPROD",
        )
        val qtdNotaPorProduto = itens.groupBy { it.codprod }.mapValues { (_, l) -> l.fold(BigDecimal.ZERO) { a, i -> a + i.qtdNota } }
        return linhas.mapNotNull { r ->
            val cod = r["CODPROD"].inteiro() ?: return@mapNotNull null
            val total = r["VLRTOT"]?.trim()?.replace(',', '.')?.toBigDecimalOrNull() ?: return@mapNotNull null
            val qtd = qtdNotaPorProduto[cod]?.takeIf { it.signum() > 0 } ?: return@mapNotNull null
            cod to PrecoItem(total.divide(qtd, 4, RoundingMode.HALF_UP), r["CODVOLPAD"]?.trim().orEmpty(), r["CODLOCAL"].inteiro(), r["DECQTD"].inteiro())
        }.toMap()
    }

    /** Campos do cabeçalho do ajuste: os da nota do fornecedor + a TOP de ajuste (versão atual e TIPMOV dela). */
    internal data class DadosCab(
        val codemp: Int,
        val codParc: Int,
        val codTipVenda: Int,
        val dhTipVenda: String,
        val codNat: String,
        val codCenCus: String,
        val codTipOper: Int,
        val tipMov: String,
        val dhTipOper: String,
        val dtNeg: String = "",
        val hora: String = "00:00:00",
        /** null = a série padrão da TOP. */
        val serie: String? = null,
    )

    private suspend fun dadosCabecalho(tenantSlug: String, codTipOper: Int, nunotaCompra: Int, codTipVendaFixo: Int? = null): DadosCab {
        val top = SankhyaDbExplorerClient.executarQuery(
            tenantSlug,
            "SELECT T.TIPMOV, TO_CHAR(T.DHALTER, 'DD/MM/YYYY HH24:MI:SS') DH FROM TGFTOP T WHERE T.CODTIPOPER = $codTipOper " +
                "AND T.DHALTER = (SELECT MAX(T2.DHALTER) FROM TGFTOP T2 WHERE T2.CODTIPOPER = $codTipOper)",
        ).firstOrNull() ?: error("TOP $codTipOper não existe no Sankhya")
        val compra = SankhyaDbExplorerClient.executarQuery(
            tenantSlug,
            "SELECT C.CODEMP, C.CODPARC, C.CODTIPVENDA, TO_CHAR(C.DHTIPVENDA, 'DD/MM/YYYY HH24:MI:SS') DHTIPVENDA, C.CODNAT, C.CODCENCUS " +
                "FROM TGFCAB C WHERE C.NUNOTA = $nunotaCompra",
        ).firstOrNull() ?: error("nota de compra $nunotaCompra não encontrada")
        // Tipo de negociação fixo do ajuste (parâmetro) — versão atual dele (DHALTER), como a Central de Notas manda.
        val tpvFixo = codTipVendaFixo?.let { cod ->
            SankhyaDbExplorerClient.executarQuery(
                tenantSlug,
                "SELECT TO_CHAR(MAX(V.DHALTER), 'DD/MM/YYYY HH24:MI:SS') DH FROM TGFTPV V WHERE V.CODTIPVENDA = $cod",
            ).firstOrNull()?.get("DH")?.trim()?.takeIf { it.isNotEmpty() }?.let { cod to it }
                ?: error("tipo de negociação $cod (ajuste de peso) não existe no Sankhya")
        }
        return DadosCab(
            codemp = compra["CODEMP"].inteiro() ?: error("nota $nunotaCompra sem empresa"),
            codParc = compra["CODPARC"].inteiro() ?: error("nota $nunotaCompra sem parceiro"),
            codTipVenda = tpvFixo?.first ?: compra["CODTIPVENDA"].inteiro() ?: 0,
            dhTipVenda = tpvFixo?.second ?: compra["DHTIPVENDA"]?.trim().orEmpty(),
            codNat = compra["CODNAT"]?.trim()?.substringBefore('.').orEmpty(),
            codCenCus = compra["CODCENCUS"]?.trim()?.substringBefore('.').orEmpty().ifEmpty { "0" },
            codTipOper = codTipOper,
            tipMov = top["TIPMOV"]?.trim().orEmpty().ifEmpty { error("TOP $codTipOper sem TIPMOV") },
            dhTipOper = top["DH"]?.trim().orEmpty(),
        )
    }

    private suspend fun buscarPorMarcador(tenantSlug: String, codTipOper: Int, marca: String): Int? =
        SankhyaDbExplorerClient.executarQuery(
            tenantSlug,
            "SELECT MAX(C.NUNOTA) NUNOTA FROM TGFCAB C WHERE C.CODTIPOPER = $codTipOper AND C.DTNEG >= TRUNC(SYSDATE) - 30 " +
                "AND C.OBSERVACAO LIKE '%${marca.replace("'", "''")}%'",
        ).firstOrNull()?.get("NUNOTA").inteiro()

    private suspend fun quantidadeItens(tenantSlug: String, nunota: Int): Int =
        SankhyaDbExplorerClient.executarQuery(tenantSlug, "SELECT COUNT(1) QTD FROM TGFITE WHERE NUNOTA = $nunota")
            .firstOrNull()?.get("QTD").inteiro() ?: 0

    private suspend fun statusNota(tenantSlug: String, nunota: Int): String? =
        SankhyaDbExplorerClient.executarQuery(tenantSlug, "SELECT STATUSNOTA FROM TGFCAB WHERE NUNOTA = $nunota")
            .firstOrNull()?.get("STATUSNOTA")?.trim()

    private fun String?.inteiro(): Int? = this?.trim()?.substringBefore('.')?.toIntOrNull()

    // ─── Payloads (capturados da Central de Notas, nota 79759 / TOP 1195, 04/11/2026) ───────────────────

    private val TX_PROPS_CAB = listOf(
        "br.com.sankhya.mgefin.checarfinanceiro.VlrEntrada" to 0,
        "br.com.sankhya.mgefin.recalculo.custopreco.Automatico" to false,
        "cabecalhoNota.inserindo.pedidoWeb" to false,
        "br.com.sankhya.mgefin.checarfinanceiro.RecalcularVencimento" to false,
    )

    /** Avisos que a Central de Notas declara saber responder (subconjunto do payload nativo). */
    private val CLIENT_EVENTS = listOf(
        "br.com.sankhya.comercial.recalcula.pis.cofins",
        "br.com.sankhya.actionbutton.clientconfirm",
        "br.com.sankhya.exibir.variacao.valor.item",
        "br.com.sankhya.mgecomercial.event.estoque.insuficiente.produto",
        "br.com.utiliza.dtneg.servidor",
        "br.com.sankhya.exibe.msg.variacao.preco",
        "central.save.grade.itens.mostrar.popup.serie",
        "central.save.grade.itens.mostrar.popup.info.lote",
        "br.com.sankhya.mgecomercial.event.estoque.componentes",
    )

    private fun clientEvents() = buildJsonObject {
        putJsonArray("clientEvent") { CLIENT_EVENTS.forEach { e -> add(buildJsonObject { put("$", e) }) } }
    }

    internal fun payloadCabecalho(cab: DadosCab, observacao: String): JsonObject = buildJsonObject {
        putJsonObject("nota") {
            putJsonObject("txProperties") {
                putJsonArray("prop") {
                    TX_PROPS_CAB.forEach { (k, v) ->
                        add(buildJsonObject { put("name", k); if (v is Boolean) put("value", v) else put("value", v as Int) })
                    }
                }
            }
            putJsonObject("cabecalho") {
                fun campo(nome: String, valor: String) = putJsonObject(nome) { put("$", valor) }
                campo("NUNOTA", "")
                campo("CODEMP", cab.codemp.toString())
                campo("CODCENCUS", cab.codCenCus)
                campo("CODPARC", cab.codParc.toString())
                campo("CODTIPVENDA", cab.codTipVenda.toString())
                if (cab.codNat.isNotEmpty()) campo("CODNAT", cab.codNat)
                campo("TIPMOV", cab.tipMov)
                campo("CODTIPOPER", cab.codTipOper.toString())
                campo("NUMNOTA", "0")
                campo("DTNEG", "${cab.dtNeg} 00:00:00")
                campo("DTMOV", "${cab.dtNeg} 00:00:00")
                campo("DTENTSAI", "${cab.dtNeg} ${cab.hora}")
                cab.serie?.trim()?.takeIf { it.isNotEmpty() }?.let { campo("SERIENOTA", it) }
                campo("IRFRETIDO", "S")
                campo("TIPFRETE", "N")
                campo("ISSRETIDO", "N")
                campo("TIPIPIEMB", "N")
                campo("CIF_FOB", "F")
                if (cab.dhTipOper.isNotEmpty()) campo("DHTIPOPER", cab.dhTipOper)
                if (cab.dhTipVenda.isNotEmpty()) campo("DHTIPVENDA", cab.dhTipVenda)
                campo("INDPRESNFCE", "0")
                campo("OBSERVACAO", observacao.take(4000))
            }
        }
        put("clientEventList", clientEvents())
    }

    internal fun payloadItens(nunotaAjuste: Int, codemp: Int?, itens: List<Pair<ItemAjuste, PrecoItem>>): JsonObject = buildJsonObject {
        putJsonObject("nota") {
            put("NUNOTA", nunotaAjuste.toString())
            putJsonObject("txProperties") {
                putJsonArray("prop") {
                    add(buildJsonObject { put("name", "br.com.sankhya.mgefin.checarfinanceiro.VlrEntrada"); put("value", 0) })
                    add(buildJsonObject { put("name", "br.com.sankhya.mgefin.recalculo.custopreco.Automatico"); put("value", false) })
                    add(buildJsonObject { put("name", "br.com.sankhya.mgecom.gradeItens.pedidoWeb"); put("value", false) })
                }
            }
            putJsonObject("itens") {
                put("ATUALIZACAO_ONLINE", "false")
                putJsonArray("item") {
                    itens.forEach { (i, preco) ->
                        val qtd = quantidadeAjuste(i.quantidade, preco.decQtd)
                        val total = (qtd * preco.vlrUnit).setScale(2, RoundingMode.HALF_UP)
                        add(buildJsonObject {
                            fun campo(nome: String, valor: String) = putJsonObject(nome) { put("$", valor) }
                            campo("NUNOTA", nunotaAjuste.toString())
                            campo("SEQUENCIA", "")
                            campo("CODPROD", i.codprod.toString())
                            if (codemp != null) campo("CODEMP", codemp.toString())
                            campo("QTDNEG", qtd.toPlainString())
                            campo("CODVOL", preco.codvol)
                            campo("VLRUNIT", preco.vlrUnit.toPlainString())
                            campo("VLRTOT", total.toPlainString())
                            campo("PERCDESC", "0")
                            campo("VLRDESC", "0")
                            campo("CODLOCALORIG", preco.codLocal?.toString() ?: "")
                            campo("CONTROLE", i.controle.trim())
                        })
                    }
                }
            }
        }
        put("clientEventList", clientEvents())
    }

    /** NUNOTA criado: `responseBody.pk.NUNOTA.$` (formato do CACSP). */
    internal fun nunotaDaResposta(resposta: JsonObject): Int? =
        runCatching { resposta["pk"]?.jsonObject?.get("NUNOTA")?.jsonObject?.get("$")?.jsonPrimitive?.contentOrNull?.trim()?.toIntOrNull() }
            .getOrNull()
}
