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
 * SEFAZ chega segundos/minutos depois — o worker tenta de novo a cada ciclo.
 *  - Até [CARENCIA]: espera a OC inteira autorizada (o normal é sair tudo junto).
 *  - Depois: imprime o romaneio + as notas autorizadas e cada nota que faltou vira um job 'nota_pendente',
 *    que imprime só aquele DANFE quando a SEFAZ autorizar — o carregamento não fica parado por uma nota.
 *  - Não desiste em 30 min: segue tentando (mais espaçado depois de [ATRASO]; a tela mostra em vermelho)
 *    até [LIMITE_ESPERA]. Nota cancelada/denegada sai da OC (o romaneio não lista) e não trava nada.
 */
object ImpressaoOcWorker {
    private val escopo = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private const val INTERVALO_MS = 15_000L
    private val CARENCIA: Duration = Duration.ofMinutes(3)
    private val ATRASO: Duration = Duration.ofMinutes(30)
    private val INTERVALO_ATRASADO: Duration = Duration.ofMinutes(2)
    private val LIMITE_ESPERA: Duration = Duration.ofHours(24)

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
        val agora = Instant.now()
        val idade = Duration.between(Instant.parse(job.criadoEm), agora)
        // Atrasado: não precisa consultar o Sankhya a cada 15 s.
        val ultimaTentativa = job.atualizadoEm?.let { Instant.parse(it) }
        if (idade > ATRASO && ultimaTentativa != null && Duration.between(ultimaTentativa, agora) < INTERVALO_ATRASADO) return
        val vencido = idade > LIMITE_ESPERA
        val somente = job.nunotas?.split(',')?.mapNotNull { it.trim().toLongOrNull() }?.toSet()
        try {
            val r = ImpressaoOcService.gerar(slug, job.ordemCarga, somente, aceitarParcial = somente == null && idade > CARENCIA)
            when {
                r.pdf != null -> {
                    val faltaram = r.pendentes
                    val aviso = faltaram.takeIf { it.isNotEmpty() }?.let { f ->
                        "NF ${f.joinToString { it.numNota }} aguardando SEFAZ — sai separada quando autorizar"
                    }
                    withContext(Dispatchers.IO) {
                        ImpressaoOcRepository.marcarPronto(tenantId, id, r.pdf, r.impressas.size, aviso)
                        faltaram.forEach { ImpressaoOcRepository.enfileirar(tenantId, job.ordemCarga, "nota_pendente", null, listOf(it.nunota)) }
                    }
                    println("INFO: impressão OC ${job.ordemCarga} — PDF pronto (${r.impressas.size} nota(s), ${r.pdf.size} bytes)" +
                        (aviso?.let { " — $it" } ?: ""))
                }
                r.notas.isEmpty() -> withContext(Dispatchers.IO) {
                    ImpressaoOcRepository.marcarErro(
                        tenantId, id,
                        if (somente == null) "OC ${job.ordemCarga} sem nota de venda" else "nota cancelada/denegada ou fora da OC — nada a imprimir",
                    )
                }
                else -> {
                    val motivo = "aguardando SEFAZ: NF " + r.pendentes.joinToString { "${it.numNota} (${it.statusNfe.ifBlank { "sem status" }})" }
                    withContext(Dispatchers.IO) {
                        if (vencido) ImpressaoOcRepository.marcarErro(tenantId, id, "SEFAZ não autorizou em ${LIMITE_ESPERA.toHours()} h — $motivo")
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
