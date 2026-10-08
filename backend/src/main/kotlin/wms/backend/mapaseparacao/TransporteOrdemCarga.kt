package wms.backend.mapaseparacao

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import wms.backend.erp.LoadRecordsRequest
import wms.backend.erp.SankhyaLoadRecordsClient
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Motorista e veículo da Ordem de Carga (TGFORD.CODPARCMOTORISTA → Parceiro, TGFORD.CODVEICULO →
 * Veiculo) pros cards da fila, cabeçalho da conferência e etiqueta de volume. Mesmas entidades do
 * Mapa de Separação. Cache em memória por OC ([TTL]): a fila (GET /api/tarefas) é só leitura local,
 * então ela usa [doCache] — devolve o que já tem e busca o resto em segundo plano (aparece no
 * próximo poll). A etiqueta usa [buscar], que espera a consulta. Falha no Sankhya nunca derruba quem
 * chama — fica sem motorista/veículo.
 */
object TransporteOrdemCarga {
    /** [situacao] = TGFORD.SITUACAO ('A' aberta, 'F' fechada) — a fila esconde a OC fechada. */
    data class Transporte(val motorista: String?, val placa: String?, val veiculo: String?, val situacao: String? = null)

    private val TTL: Duration = Duration.ofMinutes(10)
    private val escopo = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val cache = ConcurrentHashMap<Pair<UUID, Long>, Pair<Instant, Transporte>>()
    private val emVoo = ConcurrentHashMap.newKeySet<Pair<UUID, Long>>()

    /**
     * TGFORD.SITUACAO relida a cada ciclo de sync (~60s) — o cache de motorista/veículo vale 10 min, curto
     * demais pra "aberta/fechada": OC reaberta no Sankhya sumia da fila até o cache vencer (OC 317, 08/10/2026).
     */
    private val situacoes = ConcurrentHashMap<Pair<UUID, Long>, String>()

    /** Relê a situação (A/F) das [ocs] numa consulta só. Chamado pelo ciclo de sync de fundo. */
    suspend fun atualizarSituacoes(tenantSlug: String, tenantId: UUID, ocs: Collection<Long>) {
        val distintas = ocs.distinct()
        if (distintas.isEmpty()) return
        for (lote in distintas.chunked(500)) {
            wms.backend.erp.SankhyaDbExplorerClient.executarQuery(
                tenantSlug,
                "SELECT ORDEMCARGA, MIN(NVL(SITUACAO, 'A')) AS SITUACAO FROM TGFORD WHERE ORDEMCARGA IN (${lote.joinToString()}) GROUP BY ORDEMCARGA",
            ).forEach { r ->
                val oc = r["ORDEMCARGA"]?.toBigDecimalOrNull()?.toLong() ?: return@forEach
                r["SITUACAO"]?.trim()?.takeIf { it.isNotEmpty() }?.let { situacoes[tenantId to oc] = it }
            }
        }
    }

    private val FIELDS_ORDEM = listOf("ORDEMCARGA", "CODVEICULO", "CODPARCMOTORISTA", "SITUACAO")
    private val FIELDS_VEICULO = listOf("CODVEICULO", "MARCAMODELO", "PLACA")
    private val FIELDS_PARCEIRO = listOf("CODPARC", "NOMEPARC")

    private fun fresco(tenantId: UUID, oc: Long): Transporte? =
        cache[tenantId to oc]?.takeIf { it.first.isAfter(Instant.now().minus(TTL)) }?.second

    /** Só o que está em cache; o que faltar é buscado em segundo plano (sem esperar). */
    fun doCache(tenantSlug: String, tenantId: UUID, ocs: Collection<Long>): Map<Long, Transporte> {
        val distintas = ocs.distinct()
        val faltando = distintas.filter { fresco(tenantId, it) == null }.filter { emVoo.add(tenantId to it) }
        if (faltando.isNotEmpty()) {
            escopo.launch {
                try {
                    carregar(tenantSlug, tenantId, faltando)
                } catch (e: Exception) {
                    println("AVISO: falha ao buscar motorista/veículo das OCs $faltando (tenant $tenantSlug): ${e.message}")
                } finally {
                    faltando.forEach { emVoo.remove(tenantId to it) }
                }
            }
        }
        // Valor vencido ainda serve enquanto o novo não chega (motorista raramente muda). A situação
        // (aberta/fechada) vem do ciclo de sync quando já lida — é mais recente que o cache de 10 min.
        return distintas.mapNotNull { oc ->
            val t = cache[tenantId to oc]?.second
            val sit = situacoes[tenantId to oc]
            when {
                t != null -> oc to (if (sit != null) t.copy(situacao = sit) else t)
                sit != null -> oc to Transporte(motorista = null, placa = null, veiculo = null, situacao = sit)
                else -> null
            }
        }.toMap()
    }

    /** Esquece a OC (ex.: acabou de ser fechada) — o próximo poll da fila busca de novo. */
    fun invalidar(tenantId: UUID, oc: Long) {
        cache.remove(tenantId to oc)
        situacoes.remove(tenantId to oc)
    }

    /** Espera a consulta (etiqueta). Null se a OC não existe ou o Sankhya falhou. */
    suspend fun buscar(tenantSlug: String, tenantId: UUID, oc: Long): Transporte? {
        fresco(tenantId, oc)?.let { return it }
        return runCatching { carregar(tenantSlug, tenantId, listOf(oc))[oc] }
            .onFailure { println("AVISO: falha ao buscar motorista/veículo da OC $oc (tenant $tenantSlug): ${it.message}") }
            .getOrNull() ?: cache[tenantId to oc]?.second
    }

    private suspend fun carregar(tenantSlug: String, tenantId: UUID, ocs: List<Long>): Map<Long, Transporte> {
        if (ocs.isEmpty()) return emptyMap()
        val ordens = SankhyaLoadRecordsClient.parseRows(
            SankhyaLoadRecordsClient.loadRecords(
                tenantSlug,
                LoadRecordsRequest(entityName = "OrdemCarga", fields = FIELDS_ORDEM, criteriaExpression = "ORDEMCARGA IN (${ocs.joinToString(",")})"),
            ),
            FIELDS_ORDEM,
        )
        // OC pode repetir por empresa (chave ORDEMCARGA+CODEMP) — fica a primeira com motorista/veículo.
        val porOc = ordens.groupBy { it["ORDEMCARGA"]?.toLongOrNull() }
            .filterKeys { it != null }
            .mapValues { (_, rs) -> rs.firstOrNull { it["CODPARCMOTORISTA"] != null || it["CODVEICULO"] != null } ?: rs.first() }
        val codVeiculos = porOc.values.mapNotNull { it["CODVEICULO"]?.toIntOrNull() }.distinct()
        val codMotoristas = porOc.values.mapNotNull { it["CODPARCMOTORISTA"]?.toIntOrNull() }.distinct()

        val veiculos = if (codVeiculos.isEmpty()) emptyMap() else SankhyaLoadRecordsClient.parseRows(
            SankhyaLoadRecordsClient.loadRecords(
                tenantSlug,
                LoadRecordsRequest(entityName = "Veiculo", fields = FIELDS_VEICULO, criteriaExpression = "CODVEICULO IN (${codVeiculos.joinToString(",")})"),
            ),
            FIELDS_VEICULO,
        ).mapNotNull { r -> r["CODVEICULO"]?.toIntOrNull()?.let { it to r } }.toMap()
        val motoristas = if (codMotoristas.isEmpty()) emptyMap() else SankhyaLoadRecordsClient.parseRows(
            SankhyaLoadRecordsClient.loadRecords(
                tenantSlug,
                LoadRecordsRequest(entityName = "Parceiro", fields = FIELDS_PARCEIRO, criteriaExpression = "CODPARC IN (${codMotoristas.joinToString(",")})"),
            ),
            FIELDS_PARCEIRO,
        ).mapNotNull { r -> r["CODPARC"]?.toIntOrNull()?.let { cp -> r["NOMEPARC"]?.trim()?.takeIf { it.isNotEmpty() }?.let { cp to it } } }.toMap()

        val agora = Instant.now()
        val resultado = ocs.associateWith { oc ->
            val ordem = porOc[oc]
            val veiculo = ordem?.get("CODVEICULO")?.toIntOrNull()?.let { veiculos[it] }
            Transporte(
                motorista = ordem?.get("CODPARCMOTORISTA")?.toIntOrNull()?.let { motoristas[it] },
                placa = veiculo?.get("PLACA")?.trim()?.takeIf { it.isNotEmpty() },
                veiculo = veiculo?.get("MARCAMODELO")?.trim()?.takeIf { it.isNotEmpty() },
                situacao = ordem?.get("SITUACAO")?.trim()?.takeIf { it.isNotEmpty() },
            )
        }
        resultado.forEach { (oc, t) -> cache[tenantId to oc] = agora to t }
        return resultado
    }
}
