package wms.backend.impressao

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import wms.backend.tenancy.TenantRepository
import java.time.Duration
import java.time.Instant
import java.util.UUID

/**
 * Monta o PDF dos jobs 'aguardando_sefaz': a OC fecha logo depois do confirmarNota, mas a autorização da
 * SEFAZ chega segundos/minutos depois — o worker tenta de novo a cada ciclo e só gera quando TODAS as
 * notas da OC estão autorizadas. Passou do limite sem autorizar = erro (aparece no card, reimprimir refaz).
 */
object ImpressaoOcWorker {
    private val escopo = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private const val INTERVALO_MS = 15_000L
    private val LIMITE_ESPERA: Duration = Duration.ofMinutes(30)

    fun iniciar() {
        escopo.launch { loop() }
    }

    private suspend fun loop() {
        while (escopo.isActive) {
            try {
                val tenants = withContext(Dispatchers.IO) { TenantRepository.listar() }
                for (t in tenants) {
                    val tenantId = UUID.fromString(t.id ?: continue)
                    val jobs = withContext(Dispatchers.IO) { ImpressaoOcRepository.aguardandoSefaz(tenantId) }
                    jobs.forEach { processar(t.slug, tenantId, it) }
                }
            } catch (e: Exception) {
                println("AVISO: impressão OC — ciclo falhou: ${e.message}")
            }
            delay(INTERVALO_MS)
        }
    }

    private suspend fun processar(slug: String, tenantId: UUID, job: ImpressaoOcDto) {
        val id = UUID.fromString(job.id)
        val vencido = Duration.between(Instant.parse(job.criadoEm), Instant.now()) > LIMITE_ESPERA
        try {
            val r = ImpressaoOcService.gerar(slug, job.ordemCarga)
            when {
                r.pdf != null -> {
                    withContext(Dispatchers.IO) { ImpressaoOcRepository.marcarPronto(tenantId, id, r.pdf, r.notas.size) }
                    println("INFO: impressão OC ${job.ordemCarga} — PDF pronto (${r.notas.size} nota(s), ${r.pdf.size} bytes)")
                }
                r.notas.isEmpty() -> withContext(Dispatchers.IO) {
                    ImpressaoOcRepository.marcarErro(tenantId, id, "OC ${job.ordemCarga} sem nota de venda")
                }
                else -> {
                    val motivo = "aguardando SEFAZ: NF " + r.pendentes.joinToString { "${it.numNota} (${it.statusNfe.ifBlank { "sem status" }})" }
                    withContext(Dispatchers.IO) {
                        if (vencido) ImpressaoOcRepository.marcarErro(tenantId, id, "SEFAZ não autorizou em ${LIMITE_ESPERA.toMinutes()} min — $motivo")
                        else ImpressaoOcRepository.registrarEspera(tenantId, id, r.notas.size, motivo)
                    }
                }
            }
        } catch (e: Exception) {
            println("AVISO: impressão OC ${job.ordemCarga} — falhou ao montar o PDF: ${e.message}")
            withContext(Dispatchers.IO) {
                if (vencido) ImpressaoOcRepository.marcarErro(tenantId, id, "falha ao montar o PDF: ${e.message}")
                else ImpressaoOcRepository.registrarEspera(tenantId, id, job.notas, "falha ao montar o PDF: ${e.message}")
            }
        }
    }
}
