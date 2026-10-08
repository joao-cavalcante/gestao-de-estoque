package wms.backend.tarefas

import kotlinx.serialization.Serializable
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
)

object NotasFiscaisPedido {
    /** Rótulos do Sankhya (TDDOPC de TGFCAB.STATUSNFE). */
    private val SITUACAO = mapOf(
        "A" to "Aprovada", "D" to "Denegada", "E" to "Aguardando Autorização", "I" to "Enviada", "M" to "Não é NF-e",
        "P" to "Pendente de Retorno", "R" to "Aguardando Correção", "S" to "Enviada EPEC", "T" to "NF-e Terceiro",
        "V" to "Com erro de Validação",
    )

    private val cache = ConcurrentHashMap<UUID, Map<Long, NotaFiscalPedidoDto>>()

    fun doCache(tenantId: UUID): Map<Long, NotaFiscalPedidoDto> = cache[tenantId].orEmpty()

    /** Relê no Sankhya as notas dos [pedidos] (em lotes) e troca o cache do tenant. */
    suspend fun atualizar(tenantSlug: String, tenantId: UUID, pedidos: Collection<Long>) {
        if (pedidos.isEmpty()) {
            cache[tenantId] = emptyMap()
            return
        }
        val novo = HashMap<Long, NotaFiscalPedidoDto>()
        for (lote in pedidos.distinct().chunked(500)) {
            SankhyaDbExplorerClient.executarQuery(
                tenantSlug,
                "SELECT V.NUNOTAORIG, N.NUNOTA, N.NUMNOTA, N.SERIENOTA, N.STATUSNFE, N.STATUSNOTA, " +
                    "(SELECT T.DESCROPER FROM TGFTOP T WHERE T.CODTIPOPER = N.CODTIPOPER AND T.DHALTER = N.DHTIPOPER) AS DESCROPER " +
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
                )
                // Mais de uma nota por pedido (raro): fica a mais recente.
                val atual = novo[orig]
                if (atual == null || nota.nunota > atual.nunota) novo[orig] = nota
            }
        }
        cache[tenantId] = novo
    }
}
