package wms.backend.erp

import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals

/** Renovação do token: margem de 5 min OU 1/5 da validade, o que for menor (token de 300 s do gateway, 05/11/2026). */
class SankhyaAuthServiceRenovacaoTest {
    private val t0 = Instant.parse("2026-11-05T14:00:00Z")

    @Test
    fun `token de 300 s renova aos 240 s - antes nunca ficava em cache`() {
        assertEquals(t0.plusSeconds(240), SankhyaAuthService.momentoRenovacao(t0, 300))
    }

    @Test
    fun `token de 1 h renova 5 min antes`() {
        assertEquals(t0.plusSeconds(3300), SankhyaAuthService.momentoRenovacao(t0, 3600))
    }

    @Test
    fun `validade zero ou negativa nao quebra`() {
        assertEquals(true, SankhyaAuthService.momentoRenovacao(t0, 0).isAfter(t0))
    }
}
