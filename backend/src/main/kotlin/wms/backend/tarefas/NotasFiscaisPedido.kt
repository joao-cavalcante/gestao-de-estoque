package wms.backend.tarefas

import kotlinx.serialization.Serializable
import wms.backend.erp.MensagemOperador
import wms.backend.erp.SankhyaDbExplorerClient
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Nota fiscal gerada a partir do pedido (TGFVAR → TGFCAB da nota), pra fila mostrar "NF 3837 · Aprovada" em cada
 * pedido (usuário, 08/10/2026). A fila é só leitura local, então isto é um cache em memória por tenant, atualizado
 * no ciclo de sync de fundo (~60s) só pros pedidos já conferidos que ainda aparecem na fila.
 *
 * Situação = TGFCAB.STATUSNFE com os rótulos do próprio Sankhya (TDDOPC); NULL = "Não enviada".
 */
@Serializable
data class NotaFiscalPedidoDto(
    val nunota: Long,
    val numero: Long?,
    val serie: String?,
    /** TGFCAB.STATUSNFE cru (A, E, R, D, ...); null = não enviada. */
    val statusNfe: String?,
    /** Rótulo do Sankhya pro STATUSNFE (ex.: "Aprovada", "Aguardando Correção"). */
    val situacao: String,
    /** TGFCAB.STATUSNOTA = 'L'. */
    val confirmada: Boolean,
    /** true = NFC-e (descrição da TOP com "NFC"). */
    val nfce: Boolean,
    /** Rejeitada/denegada/erro de validação: o que aconteceu e o que fazer (MensagemOperador.rejeicaoSefaz). */
    val motivoRejeicao: String? = null,
)

object NotasFiscaisPedido {
    /** Rótulos do Sankhya (TDDOPC de TGFCAB.STATUSNFE). */
    private val SITUACAO = mapOf(
        "A" to "Aprovada", "D" to "Denegada pela SEFAZ", "E" to "Aguardando Autorização", "I" to "Enviada", "M" to "Não é NF-e",
        "P" to "Pendente de Retorno", "R" to "Rejeitada pela SEFAZ", "S" to "Enviada EPEC", "T" to "NF-e Terceiro",
        "V" to "Com erro de validação",
    )

    private val cache = ConcurrentHashMap<UUID, Map<Long, NotaFiscalPedidoDto>>()

    /**
     * OC ATUAL de cada pedido conferido (TGFCAB.ORDEMCARGA; 0 = sem OC). Pedido conferido sai do critério do
     * sync, então o espelho local ficava com a OC antiga — pedido trocado de OC depois da conferência sumia da
     * OC nova na fila (pedido 65276: espelho 313, Sankhya 334 — 08/10/2026).
     */
    private val ocAtual = ConcurrentHashMap<UUID, Map<Long, Long>>()

    fun doCache(tenantId: UUID): Map<Long, NotaFiscalPedidoDto> = cache[tenantId].orEmpty()

    fun ocAtualDoCache(tenantId: UUID): Map<Long, Long> = ocAtual[tenantId].orEmpty()

    /** Releitura de TODOS os pedidos (inclusive os de nota já aprovada) — no máximo a cada 10 min. */
    private const val RELEITURA_COMPLETA_MS = 10 * 60_000L
    private val ultimaCompleta = ConcurrentHashMap<UUID, Long>()

    /**
     * Relê no Sankhya as notas dos [pedidos] (em lotes) e troca o cache do tenant. Pedido com nota CONFIRMADA e
     * APROVADA pela SEFAZ não muda mais: fica com o valor do cache e só é relido na releitura completa (10 min).
     * 10/11/2026: relia a cada ciclo todos os conferidos dos últimos 15 dias (~8 lotes, ~2,3 s — a etapa mais
     * pesada do sync).
     */
    suspend fun atualizar(tenantSlug: String, tenantId: UUID, pedidos: Collection<Long>) {
        if (pedidos.isEmpty()) {
            cache[tenantId] = emptyMap()
            return
        }
        val agora = System.currentTimeMillis()
        val distintos = pedidos.distinct()
        val anterior = cache[tenantId].orEmpty()
        val ocsAnterior = ocAtual[tenantId].orEmpty()
        val completa = (ultimaCompleta[tenantId] ?: 0L) + RELEITURA_COMPLETA_MS <= agora
        val estaveis = if (completa) emptySet() else distintos.filter { p ->
            anterior[p]?.let { it.confirmada && it.statusNfe == "A" } == true && p in ocsAnterior
        }.toSet()
        val novo = HashMap<Long, NotaFiscalPedidoDto>()
        val ocs = HashMap<Long, Long>()
        estaveis.forEach { p -> novo[p] = anterior.getValue(p); ocs[p] = ocsAnterior.getValue(p) }
        for (lote in distintos.filterNot { it in estaveis }.chunked(500)) {
            SankhyaDbExplorerClient.executarQuery(
                tenantSlug,
                "SELECT NUNOTA, NVL(ORDEMCARGA, 0) AS ORDEMCARGA FROM TGFCAB WHERE NUNOTA IN (${lote.joinToString()})",
            ).forEach { r ->
                val n = r["NUNOTA"]?.toBigDecimalOrNull()?.toLong() ?: return@forEach
                ocs[n] = r["ORDEMCARGA"]?.toBigDecimalOrNull()?.toLong() ?: 0L
            }
            SankhyaDbExplorerClient.executarQuery(
                tenantSlug,
                "SELECT V.NUNOTAORIG, N.NUNOTA, N.NUMNOTA, N.SERIENOTA, N.STATUSNFE, N.STATUSNOTA, " +
                    "(SELECT T.DESCROPER FROM TGFTOP T WHERE T.CODTIPOPER = N.CODTIPOPER AND T.DHALTER = N.DHTIPOPER) AS DESCROPER, " +
                    // Código da rejeição da SEFAZ (cStat) — só interessa com a nota rejeitada/denegada/com erro.
                    "(SELECT MAX(R.CODREJEICAO) FROM TGFREJNFE R WHERE R.NUNOTA = N.NUNOTA) AS CODREJEICAO " +
                    "FROM TGFVAR V JOIN TGFCAB N ON N.NUNOTA = V.NUNOTA " +
                    "WHERE V.NUNOTAORIG IN (${lote.joinToString()}) AND V.NUNOTA <> V.NUNOTAORIG AND N.TIPMOV = 'V'",
            ).forEach { r ->
                val orig = r["NUNOTAORIG"]?.toBigDecimalOrNull()?.toLong() ?: return@forEach
                val status = r["STATUSNFE"]?.trim()?.takeIf { it.isNotEmpty() }
                val nota = NotaFiscalPedidoDto(
                    nunota = r["NUNOTA"]?.toBigDecimalOrNull()?.toLong() ?: return@forEach,
                    numero = r["NUMNOTA"]?.toBigDecimalOrNull()?.toLong(),
                    serie = r["SERIENOTA"]?.trim()?.takeIf { it.isNotEmpty() },
                    statusNfe = status,
                    situacao = status?.let { SITUACAO[it] ?: it } ?: "Não enviada",
                    confirmada = r["STATUSNOTA"]?.trim() == "L",
                    nfce = r["DESCROPER"]?.uppercase()?.contains("NFC") == true,
                    motivoRejeicao = if (status in setOf("R", "D", "V")) {
                        MensagemOperador.rejeicaoSefaz(r["CODREJEICAO"]?.toBigDecimalOrNull()?.toInt(), r["NUMNOTA"]?.trim())
                    } else null,
                )
                // Mais de uma nota por pedido (raro): fica a mais recente.
                val atual = novo[orig]
                if (atual == null || nota.nunota > atual.nunota) novo[orig] = nota
            }
        }
        cache[tenantId] = novo
        ocAtual[tenantId] = ocs
        if (completa) ultimaCompleta[tenantId] = agora
    }
}
