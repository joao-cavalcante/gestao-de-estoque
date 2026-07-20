package wms.backend.configconferencia

import wms.backend.erp.LoadRecordsRequest
import wms.backend.erp.SankhyaLoadRecordsClient
import wms.backend.tenancy.TenantRepository
import java.util.UUID

/**
 * Os 55 campos nativos da TGFCCO (Configuração de Conferência) — mesmos 53 campos de
 * comportamento documentados no catálogo (que vive no FRONTEND, não aqui — ver
 * ConfigConferenciaRepository) + DESCRICAO + NUCCO (identificação, sempre presentes).
 * Lista plana de propósito: o backend só sincroniza VALORES, não interpreta rótulo/aba.
 */
private val CAMPOS_TGFCCO = listOf(
    "NUCCO", "DESCRICAO",
    // bloco principal
    "MOMENTOCONFERENCIA", "BUSCARCODBARRAPOR", "TIPOCONTAGEM", "EXPLODIRLOTE",
    // regras gerais
    "CONFPORSERIE", "IGNORACOMPONENTKIT", "EXIGEIDENTIFPROD", "PROIBEALTERARDOCUMENTO", "ABRIRCONFERENCIA",
    // comportamento da interface
    "EXIBIRPROD", "EXIBIRQTD", "EXIBIRCODBARRAS", "EXIBIRPRODCONF", "EXIBIRQTDCONF",
    "FEEDBACKAUTOMATICO", "EXIBIRIMGPROD", "EXIBIRALERTASONORO", "IGNORAMSGCONF",
    // faturamento/confirmação
    "MULTENTREGAS", "CONSIDERAESTCONF", "FATAOCONCLUIR", "ABRIRNOTAFAT", "SOLRECNOCORTE",
    // lote
    "USASEQCODBAR", "SEPSEQCODBAR", "VALLOTCONEST", "IGNORACONTROLELOTE",
    // peso/balança
    "REGPESOTOTAL", "OBTERQTDBALANCA",
    // recontagem
    "EXIBIRPRODDIVER", "TIPORECONTAGEM", "ABRANGRECONTAGEM",
    // corte/divergência
    "CORTECONF", "QTDAMAIOR", "QTDMINRECONT",
    // divergência a maior
    "GERARPEDCOMPL", "MODNOTAPEDCOMPL", "PEDCOMPLCONFIRMADO", "PRODUTOSFORAPED",
    // divergência a menor
    "PROCEDCORTE", "CORTEPARCIAL", "MODNOTADEVOLUCAO", "NOTADEVCONFIRMADA",
    "LIBCORTE", "TIPOLIBERACAO", "AOLIBERAR",
    // formação de volumes
    "FORMACAOVOLUMES", "APRESFILASEMPRE", "IMPDANFEVOL", "IMPREETIQUETAS", "IMPETIQVOL",
    "NURFEETIQ", "NUETIQPESO",
)

object ConfigConferenciaSyncService {

    /** Traz TODAS as Configurações de Conferência do tenant (sem criteriaExpression) e substitui o espelho local. */
    suspend fun sincronizarTenant(tenantSlug: String, tenantId: UUID): Int {
        val raw = SankhyaLoadRecordsClient.loadRecords(
            tenantSlug,
            LoadRecordsRequest(entityName = "ConfiguracaoConferencia", fields = CAMPOS_TGFCCO),
        )
        val linhas = SankhyaLoadRecordsClient.parseRows(raw, CAMPOS_TGFCCO)
        return ConfigConferenciaRepository.upsertDoSankhya(tenantId, linhas)
    }

    /** Chamado pelo worker periódico — ignora silenciosamente tenant sem conexão Sankhya configurada. */
    suspend fun sincronizarTodosOsTenants() {
        TenantRepository.listar().forEach { tenant ->
            val slug = tenant.slug
            val tenantId = tenant.id?.let { UUID.fromString(it) } ?: return@forEach
            try {
                sincronizarTenant(slug, tenantId)
            } catch (e: Exception) {
                // tenant sem Sankhya configurado, ou temporariamente fora do ar — próximo ciclo tenta de novo
            }
        }
    }
}
