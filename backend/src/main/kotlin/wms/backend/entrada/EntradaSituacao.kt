package wms.backend.entrada

import wms.backend.erp.SankhyaDbExplorerClient
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Situação no Sankhya das compras já conferidas — a Entrada (lista e mesa) só lê a base local, então isto é um cache
 * em memória por tenant, atualizado no ciclo de sincronização (~60 s), igual ao NotasFiscaisPedido da venda.
 *
 * Por que precisa: a compra conferida sai do critério da fila de conferência (TGFCON2 F/D), e o espelho local não vê
 * mais a nota. A confirmação (STATUSNOTA 'L') e as notas que a conferência gerou (devolução NUNOTADEV, complemento
 * NUPEDCOMP — as mesmas colunas que o MotivoDivergenciaCompra usa) só aparecem relendo a nota.
 *
 * Só leitura (DbExplorer). Falha na leitura mantém o cache anterior — nunca derruba o sync.
 */
object EntradaSituacao {

    data class Situacao(
        val statusNota: String?,
        val nuconfAtual: Int?,
        val statusConferencia: String?,
        val devolucao: NotaDerivadaDto?,
        val complemento: NotaDerivadaDto?,
    ) {
        val confirmada: Boolean get() = statusNota == "L"
    }

    private val cache = ConcurrentHashMap<UUID, Map<Long, Situacao>>()
    private val lidoEm = ConcurrentHashMap<UUID, Instant>()

    fun doCache(tenantId: UUID): Map<Long, Situacao> = cache[tenantId].orEmpty()

    fun lidoEm(tenantId: UUID): Instant? = lidoEm[tenantId]

    /** Relê no Sankhya a situação das [compras] (em lotes de 500) e troca o cache do tenant. */
    suspend fun atualizar(tenantSlug: String, tenantId: UUID, compras: Collection<Long>) {
        val distintas = compras.distinct()
        if (distintas.isEmpty()) {
            cache[tenantId] = emptyMap()
            lidoEm[tenantId] = Instant.now()
            return
        }
        val novo = HashMap<Long, Situacao>()
        for (lote in distintas.chunked(500)) {
            SankhyaDbExplorerClient.executarQuery(tenantSlug, sql(lote)).forEach { r ->
                val nunota = longo(r["NUNOTA"]) ?: return@forEach
                novo[nunota] = Situacao(
                    statusNota = r["STATUSNOTA"]?.trim()?.takeIf { it.isNotEmpty() },
                    nuconfAtual = longo(r["NUCONFATUAL"])?.toInt(),
                    statusConferencia = r["STATUS_CONF"]?.trim()?.takeIf { it.isNotEmpty() },
                    devolucao = longo(r["NUNOTADEV"])?.let { NotaDerivadaDto(it, longo(r["NUMNOTA_DEV"]), r["STATUS_DEV"]?.trim() == "L") },
                    complemento = longo(r["NUPEDCOMP"])?.let { NotaDerivadaDto(it, longo(r["NUMNOTA_COMP"]), r["STATUS_COMP"]?.trim() == "L") },
                )
            }
        }
        cache[tenantId] = novo
        lidoEm[tenantId] = Instant.now()
    }

    internal fun sql(nunotas: List<Long>): String =
        "SELECT C.NUNOTA, C.STATUSNOTA, C.NUCONFATUAL, K.STATUS AS STATUS_CONF, K.NUNOTADEV, K.NUPEDCOMP, " +
            "D.NUMNOTA AS NUMNOTA_DEV, D.STATUSNOTA AS STATUS_DEV, P.NUMNOTA AS NUMNOTA_COMP, P.STATUSNOTA AS STATUS_COMP " +
            "FROM TGFCAB C " +
            "LEFT JOIN TGFCON2 K ON K.NUCONF = C.NUCONFATUAL " +
            "LEFT JOIN TGFCAB D ON D.NUNOTA = K.NUNOTADEV " +
            "LEFT JOIN TGFCAB P ON P.NUNOTA = K.NUPEDCOMP " +
            "WHERE C.NUNOTA IN (${nunotas.joinToString(",")})"

    /** "123", "123.0" ou "123,0" → 123; vazio/0 → null. */
    internal fun longo(v: String?): Long? =
        v?.trim()?.replace(',', '.')?.toBigDecimalOrNull()?.toLong()?.takeIf { it > 0 }
}
