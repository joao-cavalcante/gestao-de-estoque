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

object FechamentoOcService {
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

    /** Prévia do fechamento: um item por pedido da OC, com a TOP que vai ser usada e o que bloqueia. */
    suspend fun previa(tenantSlug: String, tenantId: UUID, oc: Long): FechamentoOcDto {
        val daOc = withContext(Dispatchers.IO) { TarefasRepository.listar(tenantId) }.filter { it.ordemCarga == oc }
        if (daOc.isEmpty()) return FechamentoOcDto(oc, emptyList(), podeFechar = false, mensagem = "nenhum pedido da OC $oc na fila")
        val nunotas = daOc.map { it.nunota }
        val carregamento = withContext(Dispatchers.IO) { ReconferenciaService.resumoCarregamento(tenantId, nunotas, 7) }
        val pendentes = withContext(Dispatchers.IO) { AguardandoNotaService.pendentesLocal(tenantId) }
        val aFaturar = nunotas.filter { it in pendentes }
        val nfce = runCatching { nfcePorNunota(tenantSlug, aFaturar) }.getOrElse { emptyMap() }
        val topOrigem = withContext(Dispatchers.IO) { TarefasRepository.codTipOperPorNunota(tenantId, aFaturar) }
        val destinosPorTop = topOrigem.values.distinct().associateWith { destinos(tenantSlug, tenantId, it) }

        val pedidos = daOc.sortedBy { it.nomeParceiro ?: "" }.map { t ->
            val base = FechamentoPedidoDto(nunota = t.nunota, numeroNota = t.numeroNota, cliente = t.nomeParceiro, situacao = "pronto")
            val c = carregamento[t.nunota]
            when {
                t.statusOperacional !in CONFERIDA -> base.copy(situacao = "bloqueado", motivo = "conferência não finalizada")
                c != null && c.carregados < c.total -> base.copy(situacao = "bloqueado", motivo = "falta carregar ${c.total - c.carregados} item(ns)")
                t.nunota !in pendentes -> base
                else -> {
                    val ehNfce = nfce[t.nunota]
                    val top = ehNfce?.let { f -> topOrigem[t.nunota]?.let { escolherTop(destinosPorTop[it].orEmpty(), f) } }
                    when {
                        ehNfce == null -> base.copy(situacao = "bloqueado", motivo = "não foi possível consultar o parceiro no Sankhya")
                        top == null -> base.copy(nfce = ehNfce, situacao = "bloqueado", motivo = "TOP de destino ${if (ehNfce) "NFC-e" else "NF-e"} não definida para a TOP ${topOrigem[t.nunota] ?: "?"}")
                        else -> base.copy(nfce = ehNfce, codTipOper = top.codtop, descricaoTop = top.descricao, situacao = "faturar")
                    }
                }
            }
        }
        val podeFechar = pedidos.none { it.situacao == "bloqueado" }
        return FechamentoOcDto(oc, pedidos, podeFechar)
    }

    /**
     * Fecha a OC: fatura + confirma cada pedido "faturar" (um por vez, sem retry — faturar não é idempotente) e,
     * com todos ok, fecha a OC no Sankhya. Pedido com nota gerada mas sem confirmar só é confirmado.
     */
    suspend fun fechar(tenantSlug: String, tenantId: UUID, oc: Long): FechamentoOcDto {
        val previa = previa(tenantSlug, tenantId, oc)
        if (!previa.podeFechar) return previa.copy(mensagem = "há pedidos bloqueados — resolva antes de fechar a OC")
        val pendentes = withContext(Dispatchers.IO) { AguardandoNotaService.pendentesLocal(tenantId) }

        val resultado = previa.pedidos.map { p ->
            if (p.situacao != "faturar") return@map p
            val sessaoId = pendentes[p.nunota]?.sessaoId?.let(UUID::fromString)
                ?: return@map p.copy(ok = false, erro = "sessão da conferência não encontrada")
            runCatching {
                try {
                    SeparacaoService.faturar(tenantSlug, tenantId, sessaoId, p.codTipOper!!, null)
                } catch (e: SeparacaoService.NotaSemConfirmacaoException) {
                    SeparacaoService.confirmarNotaPendente(tenantSlug, tenantId, sessaoId)
                }
            }.fold(
                onSuccess = { (notas, aviso) -> p.copy(notasGeradas = notas, ok = aviso == null && notas.isNotEmpty(), erro = aviso) },
                onFailure = { e ->
                    println("AVISO: fechamento OC $oc — faturar nunota ${p.nunota} TOP ${p.codTipOper} falhou: ${e.message}")
                    p.copy(ok = false, erro = e.message ?: "falha ao faturar no Sankhya")
                },
            )
        }
        val falhas = resultado.count { it.ok == false }
        if (falhas > 0) {
            return previa.copy(pedidos = resultado, mensagem = "$falhas pedido(s) sem nota — a OC não foi fechada. Corrija e tente de novo.")
        }
        // (3) Fecha a OC no Sankhya (TGFORD.SITUACAO = 'F').
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

        /** Fecha a OC: fatura + confirma os pedidos e, com todos ok, fecha no Sankhya. */
        post("/fechar") {
            val claims = call.exigirAuth() ?: return@post
            val oc = call.parameters["oc"]?.toLongOrNull()
                ?: return@post call.respond(HttpStatusCode.BadRequest, mapOf("erro" to "OC inválida"))
            val slug = TenantRepository.buscarPorId(claims.tenantId)?.slug
                ?: return@post call.respond(HttpStatusCode.NotFound, mapOf("erro" to "tenant não encontrado"))
            try {
                call.respond(FechamentoOcService.fechar(slug, claims.tenantId, oc))
            } catch (e: Exception) {
                println("AVISO: fechamento da OC $oc falhou: ${e.message}")
                call.respond(HttpStatusCode.BadGateway, mapOf("erro" to (e.message ?: "falha ao fechar a OC")))
            }
        }
    }
}
