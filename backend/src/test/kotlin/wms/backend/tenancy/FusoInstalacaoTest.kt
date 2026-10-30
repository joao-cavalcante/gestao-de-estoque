package wms.backend.tenancy

import wms.backend.separacao.SeparacaoService
import java.time.Instant
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Entrega 1, Fase 4 (M17) — data de faturamento no fuso do tenant, com relógio controlado. */
class FusoInstalacaoTest {
    private fun brt(iso: String) = Instant.parse("$iso-03:00")
    private fun data(iso: String, fusoConfigurado: String?) =
        SeparacaoService.dataFaturamento(brt(iso), FusoInstalacao.resolver(fusoConfigurado))

    @Test
    fun `fuso configurado America Sao_Paulo - 20h59, 21h01 e 23h59 ficam no mesmo dia`() {
        assertEquals("27/10/2026", data("2026-10-27T20:59:00", "America/Sao_Paulo"))
        assertEquals("27/10/2026", data("2026-10-27T21:01:00", "America/Sao_Paulo"))
        assertEquals("27/10/2026", data("2026-10-27T23:59:00", "America/Sao_Paulo"))
    }

    @Test
    fun `virada do dia e do mes`() {
        assertEquals("31/10/2026", data("2026-10-31T23:59:59", "America/Sao_Paulo"))
        assertEquals("01/11/2026", data("2026-11-01T00:00:00", "America/Sao_Paulo"))
    }

    @Test
    fun `fuso ausente - padrao America Sao_Paulo, nunca o fuso do processo`() {
        assertEquals(ZoneId.of("America/Sao_Paulo"), FusoInstalacao.resolver(null))
        assertEquals(ZoneId.of("America/Sao_Paulo"), FusoInstalacao.resolver("  "))
        assertEquals("27/10/2026", data("2026-10-27T23:32:00", null))
    }

    @Test
    fun `fuso invalido - vale o padrao e nao grava`() {
        assertEquals(FusoInstalacao.PADRAO, FusoInstalacao.resolver("Brasil/Hora"))
        assertTrue(ParametrosTenant(fuso = "Brasil/Hora").validar().isNotEmpty())
        assertTrue(ParametrosTenant(fuso = "America/Sao_Paulo").validar().isEmpty())
    }

    @Test
    fun `outro fuso configurado - Manaus vira o dia uma hora depois`() {
        assertEquals("27/10/2026", data("2026-10-27T23:59:00", "America/Manaus")) // 22h59 em Manaus
        assertEquals("28/10/2026", data("2026-10-28T01:00:00", "America/Manaus")) // 00h00 em Manaus
    }

    @Test
    fun `Negri - parametros da V65 resolvem America Sao_Paulo`() {
        val p = ParametrosTenant.ler("""{"fuso": "America/Sao_Paulo"}""")
        assertEquals(ZoneId.of("America/Sao_Paulo"), FusoInstalacao.resolver(p.fuso))
    }
}
