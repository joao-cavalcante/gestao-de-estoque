package wms.backend.ordemcarga

import io.ktor.http.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import wms.backend.aguardandonota.AguardandoNotaService
import wms.backend.auth.exigirAuth
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import wms.backend.erp.SankhyaDbExplorerClient
import wms.backend.erp.SankhyaSpClient
import wms.backend.mapaseparacao.TransporteOrdemCarga
import wms.backend.reconferencia.ReconferenciaService
import wms.backend.separacao.SeparacaoService
import wms.backend.tarefas.StatusOperacional
import wms.backend.tarefas.TarefasRepository
import wms.backend.tenancy.TenantRepository
import wms.backend.tipooperacao.TipoOperacaoRepository
import wms.backend.tipooperacao.TipoOperacaoSyncService
import wms.backend.tipooperacao.TopDestinoDto
import java.util.UUID

/**
 * Fechamento da Ordem de Carga (usuário, 08/10/2026): a nota NÃO sai pedido a pedido — sai no fechamento da OC.
 * "Fechar OC" = (1) fatura cada pedido da OC na sua TOP de destino, (2) confirma as notas (CACSP.confirmarNota,
 * dentro do SeparacaoService.faturar) e (3) fecha a OC no Sankhya — só se TODOS os pedidos saíram com nota.
 * Fechar no Sankhya = TGFORD.SITUACAO 'A' → 'F' (usuário, 08/10/2026), pela entidade OrdemCarga (saveRecord),
 * em cada empresa da OC (chave ORDEMCARGA + CODEMP).
 *
 * TOP de destino por pedido, automática: parceiro com TGFPAR.CODTIPPARC = 10401002 é NFC-e (regra da Negri);
 * NFC-e vai pro destino da TOP do pedido cuja descrição tem "NFC", NF-e pro que tem "NF-E"/"NFE" (ex.: 1001 →
 * 1102 VENDA - NFC-E / 1101 VENDA NF-E). Sem exatamente um destino do tipo, o pedido fica "TOP não definida".
 *
 * Falha num pedido não para os outros; a OC só fecha com todos ok ("Tentar de novo" refaz só o que faltou —
 * pedido já faturado e confirmado sai sozinho, porque a sessão dele deixa de estar "aguardando nota").
 */
@Serializable
data class FechamentoPedidoDto(
    val nunota: Long,
    val numeroNota: Long? = null,
    val cliente: String? = null,
    /** true = NFC-e (parceiro CODTIPPARC 10401002); false = NF-e. Null = não deu pra consultar. */
    val nfce: Boolean? = null,
    val codTipOper: Int? = null,
    val descricaoTop: String? = null,
    /** faturar | pronto (já tem nota confirmada / CCO sem faturamento) | bloqueado */
    val situacao: String,
    val motivo: String? = null,
    /** Preenchidos depois do fechamento. */
    val notasGeradas: List<Long> = emptyList(),
    val ok: Boolean? = null,
    val erro: String? = null,
)

@Serializable
data class FechamentoOcDto(
    val oc: Long,
    val pedidos: List<FechamentoPedidoDto>,
    /** Todos conferidos e carregados, e todo pedido a faturar com TOP definida. */
    val podeFechar: Boolean,
    val ocFechada: Boolean = false,
    val mensagem: String? = null,
)

/** Progresso da emissão (Fechar OC / Gerar nota) — a tela consulta enquanto o POST não volta. */
@Serializable
data class ProgressoEmissaoDto(
    /** faturando | fechando | null (nada em andamento) */
    val fase: String? = null,
    val feitos: Int = 0,
    val total: Int = 0,
    /** Pedido sendo faturado agora ("65326 — PIZZARIA TRIUNFO"). */
    val atual: String? = null,
)

/** Em memória, por chave "oc:333" / "pedido:65528". */
object ProgressoEmissao {
    private val estados = java.util.concurrent.ConcurrentHashMap<String, ProgressoEmissaoDto>()
    fun atualizar(chave: String, estado: ProgressoEmissaoDto) { estados[chave] = estado }
    fun limpar(chave: String) { estados.remove(chave) }
    fun obter(chave: String): ProgressoEmissaoDto = estados[chave] ?: ProgressoEmissaoDto()
}

/** Já tem emissão rodando pra esta OC/pedido (outro clique ou aparelho) — a tela acompanha o progresso. */
class EmissaoEmAndamentoException(message: String) : Exception(message)

@Serializable
data class EmissaoEmAndamentoDto(val erro: String, val emAndamento: Boolean = true)

object FechamentoOcService {
    /** Uma emissão por OC/pedido de cada vez (OC 338, 09/10/2026: 4 "Fechar OC" simultâneos da mesma OC). */
    private val travas = java.util.concurrent.ConcurrentHashMap<String, kotlinx.coroutines.sync.Mutex>()

    private suspend fun <T> comTrava(chave: String, descricao: String, bloco: suspend () -> T): T {
        val trava = travas.computeIfAbsent(chave) { kotlinx.coroutines.sync.Mutex() }
        if (!trava.tryLock()) throw EmissaoEmAndamentoException("$descricao já está sendo processada (outro clique ou aparelho) — aguarde terminar")
        try {
            return bloco()
        } finally {
            trava.unlock()
        }
    }

    private const val CODTIPPARC_NFCE = 10401002L
    private val CONFERIDA = setOf(
        StatusOperacional.CONCLUIDO.codigo, StatusOperacional.CONCLUIDO_DIVERGENTE.codigo,
        StatusOperacional.RECONTAGEM_CONCLUIDA.codigo, StatusOperacional.RECONTAGEM_CONCLUIDA_DIVERGENTE.codigo,
    )

    /** NUNOTA -> é NFC-e (parceiro do tipo 10401002), numa consulta só. */
    suspend fun nfcePorNunota(tenantSlug: String, nunotas: Collection<Long>): Map<Long, Boolean> {
        if (nunotas.isEmpty()) return emptyMap()
        return SankhyaDbExplorerClient.executarQuery(
            tenantSlug,
            "SELECT C.NUNOTA, CASE WHEN EXISTS (SELECT 1 FROM TGFPAR PAR WHERE PAR.CODPARC = C.CODPARC " +
                "AND PAR.CODTIPPARC = $CODTIPPARC_NFCE) THEN 'S' ELSE 'N' END AS NFCE " +
                "FROM TGFCAB C WHERE C.NUNOTA IN (${nunotas.joinToString()})",
        ).mapNotNull { r -> r["NUNOTA"]?.toBigDecimalOrNull()?.toLong()?.let { it to (r["NFCE"]?.trim() == "S") } }.toMap()
    }

    /** Destino NFC-e = descrição com "NFC"; NF-e = descrição com "NF-E"/"NFE" e sem "NFC". Null se não houver exatamente um. */
    fun escolherTop(destinos: List<TopDestinoDto>, nfce: Boolean): TopDestinoDto? {
        val norm = { d: TopDestinoDto -> d.descricao.uppercase().replace(" ", "") }
        val candidatos = if (nfce) destinos.filter { "NFC" in norm(it) }
        else destinos.filter { ("NF-E" in norm(it) || "NFE" in norm(it)) && "NFC" !in norm(it) }
        return candidatos.singleOrNull()
    }

    private suspend fun destinos(tenantSlug: String, tenantId: UUID, topOrigem: Int): List<TopDestinoDto> =
        withContext(Dispatchers.IO) { TipoOperacaoRepository.destinosDe(tenantId, topOrigem) }
            .ifEmpty { TipoOperacaoSyncService.buscarDestinos(tenantSlug, listOf(topOrigem))[topOrigem].orEmpty() }

    /** Pedido de venda da OC como está no Sankhya agora (fonte da verdade do fechamento). */
    private data class PedidoOc(
        val nunota: Long, val numNota: Long?, val cliente: String?, val codTipOper: Int?, val nfce: Boolean,
        val statusConf: String?, val liberacoesPendentes: Int, val notas: List<Long>, val notasSemConfirmar: List<Long>,
    )

    private suspend fun pedidosDaOc(tenantSlug: String, oc: Long): List<PedidoOc> = pedidosVenda(tenantSlug, "C.ORDEMCARGA = $oc")

    /** Pedidos de venda (TIPMOV 'P') que atendem [filtro] (SQL sobre TGFCAB C), com conferência, notas e NF-e/NFC-e. */
    private suspend fun pedidosVenda(tenantSlug: String, filtro: String): List<PedidoOc> {
        val cab = SankhyaDbExplorerClient.executarQuery(
            tenantSlug,
            "SELECT C.NUNOTA, C.NUMNOTA, C.CODTIPOPER, P.NOMEPARC, " +
                "CASE WHEN EXISTS (SELECT 1 FROM TGFPAR PAR WHERE PAR.CODPARC = C.CODPARC AND PAR.CODTIPPARC = $CODTIPPARC_NFCE) THEN 'S' ELSE 'N' END AS NFCE, " +
                "(SELECT F.STATUS FROM TGFCON2 F WHERE F.NUCONF = C.NUCONFATUAL) AS STATUS_CONF, " +
                "(SELECT COUNT(*) FROM TSILIB L WHERE L.NUCHAVE = C.NUCONFATUAL AND L.TABELA = 'TGFCOI2' AND L.EVENTO = 64 AND L.DHLIB IS NULL) AS LIB_PEND " +
                "FROM TGFCAB C JOIN TGFPAR P ON P.CODPARC = C.CODPARC WHERE $filtro AND C.TIPMOV = 'P'",
        )
        if (cab.isEmpty()) return emptyList()
        val nunotas = cab.mapNotNull { it["NUNOTA"]?.toBigDecimalOrNull()?.toLong() }
        val notas = SankhyaDbExplorerClient.executarQuery(
            tenantSlug,
            "SELECT DISTINCT V.NUNOTAORIG, V.NUNOTA, N.STATUSNOTA FROM TGFVAR V JOIN TGFCAB N ON N.NUNOTA = V.NUNOTA " +
                "WHERE V.NUNOTAORIG IN (${nunotas.joinToString()}) AND V.NUNOTA <> V.NUNOTAORIG",
        ).mapNotNull { r ->
            val orig = r["NUNOTAORIG"]?.toBigDecimalOrNull()?.toLong() ?: return@mapNotNull null
            val nota = r["NUNOTA"]?.toBigDecimalOrNull()?.toLong() ?: return@mapNotNull null
            Triple(orig, nota, r["STATUSNOTA"]?.trim() == "L")
        }.groupBy { it.first }
        return cab.mapNotNull { r ->
            val nunota = r["NUNOTA"]?.toBigDecimalOrNull()?.toLong() ?: return@mapNotNull null
            val n = notas[nunota].orEmpty()
            PedidoOc(
                nunota = nunota,
                numNota = r["NUMNOTA"]?.toBigDecimalOrNull()?.toLong(),
                cliente = r["NOMEPARC"]?.trim(),
                codTipOper = r["CODTIPOPER"]?.toBigDecimalOrNull()?.toInt(),
                nfce = r["NFCE"]?.trim() == "S",
                statusConf = r["STATUS_CONF"]?.trim()?.takeIf { it.isNotEmpty() },
                liberacoesPendentes = r["LIB_PEND"]?.toBigDecimalOrNull()?.toInt() ?: 0,
                notas = n.map { it.second }.distinct().sorted(),
                notasSemConfirmar = n.filter { !it.third }.map { it.second }.distinct().sorted(),
            )
        }.sortedBy { it.cliente ?: "" }
    }

    private val STATUS_CONF_FINALIZADA = setOf("F", "D", "RF", "RD")

    /**
     * Prévia do fechamento — TODO pedido de venda da OC no Sankhya (inclusive conferido fora do WMS), independente
     * da CCO (usuário, 08/10/2026: OC 333 fechou sem faturar porque a regra antiga dependia de FATAOCONCLUIR):
     * já com nota confirmada = pronto; senão fatura (ou só confirma) na TOP NF-e/NFC-e do parceiro.
     */
    suspend fun previa(tenantSlug: String, tenantId: UUID, oc: Long): FechamentoOcDto {
        val pedidos = pedidosDaOc(tenantSlug, oc)
        if (pedidos.isEmpty()) return FechamentoOcDto(oc, emptyList(), podeFechar = false, mensagem = "nenhum pedido de venda na OC $oc no Sankhya")
        val itens = avaliar(tenantSlug, tenantId, pedidos)
        return FechamentoOcDto(oc, itens, podeFechar = itens.none { it.situacao == "bloqueado" })
    }

    /** Situação de cada pedido pro faturamento: pronto (já tem nota confirmada) · faturar (TOP automática) · bloqueado. */
    private suspend fun avaliar(tenantSlug: String, tenantId: UUID, pedidos: List<PedidoOc>): List<FechamentoPedidoDto> {
        val carregamento = withContext(Dispatchers.IO) { ReconferenciaService.resumoCarregamento(tenantId, pedidos.map { it.nunota }, 7) }
        val destinosPorTop = pedidos.mapNotNull { it.codTipOper }.distinct().associateWith { destinos(tenantSlug, tenantId, it) }

        val itens = pedidos.map { p ->
            val base = FechamentoPedidoDto(nunota = p.nunota, numeroNota = p.numNota, cliente = p.cliente, nfce = p.nfce, situacao = "pronto", notasGeradas = p.notas)
            val c = carregamento[p.nunota]
            when {
                p.notas.isNotEmpty() && p.notasSemConfirmar.isEmpty() -> base
                p.statusConf == "C" || p.liberacoesPendentes > 0 -> base.copy(situacao = "bloqueado", motivo = "corte aguardando liberação")
                p.statusConf !in STATUS_CONF_FINALIZADA && p.notas.isEmpty() ->
                    base.copy(situacao = "bloqueado", motivo = if (p.statusConf == null) "não conferido" else "conferência não finalizada (${p.statusConf})")
                c != null && c.carregados < c.total -> base.copy(situacao = "bloqueado", motivo = "falta carregar ${c.total - c.carregados} item(ns)")
                else -> {
                    val top = p.codTipOper?.let { escolherTop(destinosPorTop[it].orEmpty(), p.nfce) }
                    if (top == null) base.copy(situacao = "bloqueado", motivo = "TOP de destino ${if (p.nfce) "NFC-e" else "NF-e"} não definida para a TOP ${p.codTipOper ?: "?"}")
                    else base.copy(codTipOper = top.codtop, descricaoTop = top.descricao, situacao = "faturar",
                        motivo = if (c == null) "conferido fora do WMS (sem registro de carregamento)" else null)
                }
            }
        }
        return itens
    }

    /** Fatura + confirma cada item "faturar" (um por vez, sem retry). Falha num não para os outros. */
    private suspend fun faturarItens(
        tenantSlug: String,
        tenantId: UUID,
        itens: List<FechamentoPedidoDto>,
        contexto: String,
        chaveProgresso: String,
    ): List<FechamentoPedidoDto> {
        val total = itens.count { it.situacao == "faturar" }
        var feitos = 0
        return itens.map { p ->
            if (p.situacao != "faturar") return@map p
            ProgressoEmissao.atualizar(chaveProgresso, ProgressoEmissaoDto("faturando", feitos, total, "${p.nunota}${p.cliente?.let { " — $it" } ?: ""}"))
            val r = runCatching { SeparacaoService.faturarPedido(tenantSlug, tenantId, p.nunota, p.codTipOper!!) }.fold(
                onSuccess = { (notas, aviso) -> p.copy(notasGeradas = notas, ok = aviso == null && notas.isNotEmpty(), erro = aviso) },
                onFailure = { e ->
                    println("AVISO: $contexto — faturar nunota ${p.nunota} TOP ${p.codTipOper} falhou: ${e.message}")
                    p.copy(ok = false, erro = e.message ?: "falha ao faturar no Sankhya")
                },
            )
            feitos++
            ProgressoEmissao.atualizar(chaveProgresso, ProgressoEmissaoDto("faturando", feitos, total, null))
            r
        }
    }

    /**
     * Pedido SEM OC (retira/express): mesma lógica do Fechar OC (usuário, 08/10/2026) — TOP automática pelo
     * parceiro (NF-e/NFC-e), fatura e confirma num toque, independente da CCO. Devolve no formato do fechamento (oc = 0).
     */
    suspend fun previaPedido(tenantSlug: String, tenantId: UUID, nunota: Long): FechamentoOcDto {
        val pedidos = pedidosVenda(tenantSlug, "C.NUNOTA = $nunota")
        if (pedidos.isEmpty()) return FechamentoOcDto(0, emptyList(), podeFechar = false, mensagem = "pedido $nunota não encontrado no Sankhya")
        val itens = avaliar(tenantSlug, tenantId, pedidos)
        return FechamentoOcDto(0, itens, podeFechar = itens.none { it.situacao == "bloqueado" })
    }

    suspend fun faturarPedidoAvulso(tenantSlug: String, tenantId: UUID, nunota: Long): FechamentoOcDto =
        comTrava("pedido:$nunota", "A nota do pedido $nunota") { faturarPedidoAvulsoSemTrava(tenantSlug, tenantId, nunota) }

    private suspend fun faturarPedidoAvulsoSemTrava(tenantSlug: String, tenantId: UUID, nunota: Long): FechamentoOcDto {
        val previa = previaPedido(tenantSlug, tenantId, nunota)
        if (!previa.podeFechar) return previa.copy(mensagem = "pedido bloqueado — resolva antes de gerar a nota")
        val chave = "pedido:$nunota"
        val resultado = try {
            faturarItens(tenantSlug, tenantId, previa.pedidos, "nota do pedido $nunota", chave)
        } finally {
            ProgressoEmissao.limpar(chave)
        }
        val falha = resultado.any { it.ok == false }
        return previa.copy(
            pedidos = resultado,
            mensagem = if (falha) "A nota não saiu — veja o motivo e tente de novo." else "Nota faturada e confirmada.",
        )
    }

    /**
     * Fecha a OC: fatura + confirma cada pedido "faturar" (um por vez, sem retry — faturar não é idempotente) e, com
     * todos ok, fecha a OC no Sankhya. TRAVA FINAL: antes de fechar, relê no Sankhya — todo pedido da OC precisa ter
     * nota confirmada; senão a OC NÃO fecha.
     */
    suspend fun fechar(tenantSlug: String, tenantId: UUID, oc: Long): FechamentoOcDto =
        comTrava("oc:$oc", "O fechamento da OC $oc") {
            try {
                fecharComProgresso(tenantSlug, tenantId, oc)
            } finally {
                ProgressoEmissao.limpar("oc:$oc")
            }
        }

    private suspend fun fecharComProgresso(tenantSlug: String, tenantId: UUID, oc: Long): FechamentoOcDto {
        val previa = previa(tenantSlug, tenantId, oc)
        if (!previa.podeFechar) return previa.copy(mensagem = "há pedidos bloqueados — resolva antes de fechar a OC")

        val resultado = faturarItens(tenantSlug, tenantId, previa.pedidos, "fechamento OC $oc", "oc:$oc")
        val falhas = resultado.count { it.ok == false }
        if (falhas > 0) {
            return previa.copy(pedidos = resultado, mensagem = "$falhas pedido(s) sem nota — a OC não foi fechada. Corrija e tente de novo.")
        }

        // Trava final: a OC só fecha se TODO pedido dela tiver nota confirmada no Sankhya.
        val semNota = pedidosDaOc(tenantSlug, oc).filter { it.notas.isEmpty() || it.notasSemConfirmar.isNotEmpty() }
        if (semNota.isNotEmpty()) {
            println("AVISO: fechamento OC $oc — trava final: sem nota confirmada ${semNota.map { it.nunota }}; OC não fechada")
            return previa.copy(
                pedidos = resultado,
                mensagem = "A OC não foi fechada: pedido(s) ${semNota.joinToString { it.nunota.toString() }} ainda sem nota confirmada no Sankhya.",
            )
        }

        // (3) Fecha a OC no Sankhya (TGFORD.SITUACAO = 'F').
        val faturados = resultado.count { it.situacao == "faturar" }
        ProgressoEmissao.atualizar("oc:$oc", ProgressoEmissaoDto("fechando", faturados, faturados, null))
        return runCatching { fecharNoSankhya(tenantSlug, tenantId, oc) }.fold(
            onSuccess = { previa.copy(pedidos = resultado, ocFechada = true, mensagem = "Notas faturadas e confirmadas e OC $oc fechada no Sankhya.") },
            onFailure = { e ->
                println("AVISO: fechamento OC $oc — notas ok, mas fechar a TGFORD falhou: ${e.message}")
                previa.copy(pedidos = resultado, mensagem = "Notas faturadas e confirmadas, mas o Sankhya não fechou a OC $oc (${e.message}). Tente de novo.")
            },
        )
    }

    /** TGFORD.SITUACAO = 'F' em toda empresa da OC ainda aberta; confere lendo de volta. */
    private suspend fun fecharNoSankhya(tenantSlug: String, tenantId: UUID, oc: Long) {
        val empresas = SankhyaDbExplorerClient.executarQuery(
            tenantSlug,
            "SELECT CODEMP FROM TGFORD WHERE ORDEMCARGA = $oc AND NVL(SITUACAO, 'A') <> 'F'",
        ).mapNotNull { it["CODEMP"]?.toBigDecimalOrNull()?.toInt() }
        for (codemp in empresas) {
            SankhyaSpClient.chamarRaw(
                tenantSlug, "CRUDServiceProvider.saveRecord", "mge",
                buildJsonObject {
                    putJsonObject("dataSet") {
                        put("rootEntity", "OrdemCarga")
                        put("includePresentationFields", "N")
                        putJsonObject("dataRow") {
                            putJsonObject("localFields") { putJsonObject("SITUACAO") { put("\$", "F") } }
                            putJsonObject("key") {
                                putJsonObject("CODEMP") { put("\$", codemp.toString()) }
                                putJsonObject("ORDEMCARGA") { put("\$", oc.toString()) }
                            }
                        }
                        putJsonObject("entity") { putJsonObject("fieldset") { put("list", "CODEMP,ORDEMCARGA,SITUACAO") } }
                    }
                },
                retentar = false,
            )
        }
        val aindaAbertas = SankhyaDbExplorerClient.executarQuery(
            tenantSlug,
            "SELECT COUNT(*) AS QTD FROM TGFORD WHERE ORDEMCARGA = $oc AND NVL(SITUACAO, 'A') <> 'F'",
        ).firstOrNull()?.get("QTD")?.toBigDecimalOrNull()?.toInt() ?: 0
        if (aindaAbertas > 0) throw IllegalStateException("a OC continua aberta no Sankhya")
        TransporteOrdemCarga.invalidar(tenantId, oc)
        println("INFO: OC $oc fechada no Sankhya (TGFORD.SITUACAO = 'F', empresas $empresas)")
    }
}

fun Route.fechamentoOcRoutes() {
    route("/api/pedidos/{nunota}/nota") {
        /** Prévia da nota do pedido sem OC: NF-e/NFC-e, TOP automática e bloqueios. */
        get {
            val claims = call.exigirAuth() ?: return@get
            val nunota = call.parameters["nunota"]?.toLongOrNull()
                ?: return@get call.respond(HttpStatusCode.BadRequest, mapOf("erro" to "pedido inválido"))
            val slug = TenantRepository.buscarPorId(claims.tenantId)?.slug
                ?: return@get call.respond(HttpStatusCode.NotFound, mapOf("erro" to "tenant não encontrado"))
            try {
                call.respond(FechamentoOcService.previaPedido(slug, claims.tenantId, nunota))
            } catch (e: Exception) {
                call.respond(HttpStatusCode.BadGateway, mapOf("erro" to (e.message ?: "falha ao consultar o Sankhya")))
            }
        }

        /** Progresso da emissão em andamento (a tela consulta enquanto o POST não volta). */
        get("/progresso") {
            call.exigirAuth() ?: return@get
            val nunota = call.parameters["nunota"]?.toLongOrNull()
                ?: return@get call.respond(HttpStatusCode.BadRequest, mapOf("erro" to "pedido inválido"))
            call.respond(ProgressoEmissao.obter("pedido:$nunota"))
        }

        /** Fatura + confirma o pedido sem OC na TOP automática. */
        post {
            val claims = call.exigirAuth() ?: return@post
            val nunota = call.parameters["nunota"]?.toLongOrNull()
                ?: return@post call.respond(HttpStatusCode.BadRequest, mapOf("erro" to "pedido inválido"))
            val slug = TenantRepository.buscarPorId(claims.tenantId)?.slug
                ?: return@post call.respond(HttpStatusCode.NotFound, mapOf("erro" to "tenant não encontrado"))
            try {
                call.respond(FechamentoOcService.faturarPedidoAvulso(slug, claims.tenantId, nunota))
            } catch (e: EmissaoEmAndamentoException) {
                call.respond(HttpStatusCode.Conflict, EmissaoEmAndamentoDto(erro = e.message ?: "emissão em andamento"))
            } catch (e: Exception) {
                println("AVISO: nota do pedido $nunota falhou: ${e.message}")
                call.respond(HttpStatusCode.BadGateway, mapOf("erro" to (e.message ?: "falha ao gerar a nota")))
            }
        }
    }

    route("/api/ordens-carga/{oc}") {
        /** Prévia do "Fechar OC": pedidos, NF-e/NFC-e, TOP de destino e bloqueios. */
        get("/fechamento") {
            val claims = call.exigirAuth() ?: return@get
            val oc = call.parameters["oc"]?.toLongOrNull()
                ?: return@get call.respond(HttpStatusCode.BadRequest, mapOf("erro" to "OC inválida"))
            val slug = TenantRepository.buscarPorId(claims.tenantId)?.slug
                ?: return@get call.respond(HttpStatusCode.NotFound, mapOf("erro" to "tenant não encontrado"))
            try {
                call.respond(FechamentoOcService.previa(slug, claims.tenantId, oc))
            } catch (e: Exception) {
                call.respond(HttpStatusCode.BadGateway, mapOf("erro" to (e.message ?: "falha ao consultar o Sankhya")))
            }
        }

        /** Progresso do fechamento em andamento (a tela consulta enquanto o POST não volta). */
        get("/fechamento/progresso") {
            call.exigirAuth() ?: return@get
            val oc = call.parameters["oc"]?.toLongOrNull()
                ?: return@get call.respond(HttpStatusCode.BadRequest, mapOf("erro" to "OC inválida"))
            call.respond(ProgressoEmissao.obter("oc:$oc"))
        }

        /** Fecha a OC: fatura + confirma os pedidos e, com todos ok, fecha no Sankhya. */
        post("/fechar") {
            val claims = call.exigirAuth() ?: return@post
            val oc = call.parameters["oc"]?.toLongOrNull()
                ?: return@post call.respond(HttpStatusCode.BadRequest, mapOf("erro" to "OC inválida"))
            val slug = TenantRepository.buscarPorId(claims.tenantId)?.slug
                ?: return@post call.respond(HttpStatusCode.NotFound, mapOf("erro" to "tenant não encontrado"))
            try {
                call.respond(FechamentoOcService.fechar(slug, claims.tenantId, oc))
            } catch (e: EmissaoEmAndamentoException) {
                call.respond(HttpStatusCode.Conflict, EmissaoEmAndamentoDto(erro = e.message ?: "fechamento em andamento"))
            } catch (e: Exception) {
                println("AVISO: fechamento da OC $oc falhou: ${e.message}")
                call.respond(HttpStatusCode.BadGateway, mapOf("erro" to (e.message ?: "falha ao fechar a OC")))
            }
        }
    }
}
