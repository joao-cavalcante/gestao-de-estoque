package wms.backend.tenancy

import java.time.DateTimeException
import java.time.ZoneId
import java.util.UUID

/**
 * Fuso da operação do tenant — o ÚNICO lugar que decide em que dia "estamos" pra regra fiscal (data de faturamento).
 * O processo roda em UTC no container; usar o fuso do processo fazia nota faturada depois das 21h de Brasília sair
 * com DTFATUR do dia seguinte (33 notas da Negri, 19 a 29/10/2026).
 *
 * `parametros.fuso` (ID IANA, ex.: America/Sao_Paulo, America/Manaus). Ausente = [PADRAO]. Inválido não grava
 * (ParametrosTenant.validar); se mesmo assim estiver gravado, vale o [PADRAO] com AVISO.
 */
object FusoInstalacao {
    val PADRAO: ZoneId = ZoneId.of("America/Sao_Paulo")

    /** Decisão pura — testável sem banco. */
    internal fun resolver(configurado: String?): ZoneId {
        val id = configurado?.trim()?.takeIf { it.isNotEmpty() } ?: return PADRAO
        return try {
            ZoneId.of(id)
        } catch (e: DateTimeException) {
            println("AVISO: fuso '$id' do tenant é inválido — usando $PADRAO")
            PADRAO
        }
    }

    fun valido(configurado: String): Boolean = runCatching { ZoneId.of(configurado.trim()) }.isSuccess

    fun zona(tenantId: UUID): ZoneId = resolver(runCatching { TenantRepository.parametros(tenantId).fuso }.getOrNull())
}
