package wms.backend.separacao

import java.time.Instant
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Caracterização (Entrega 1, Fase 0) — `dtFaturamento` usa o fuso PADRÃO DO PROCESSO. O container de produção
 * não define TZ (UTC): depois das 21h de Brasília a data sai com o dia seguinte. Confirmado em produção em
 * 30/10/2026 (somente leitura): 33 notas do WMS com DTFATUR = DTNEG + 1, todas faturadas entre 21h e 24h.
 */
class DataFaturamentoCaracterizacaoTest {
    private val utc = ZoneId.of("UTC")
    private val brasilia = ZoneId.of("America/Sao_Paulo")
    private fun brt(iso: String) = Instant.parse("$iso-03:00")

    @Test
    fun `processo em UTC - 20h59 de Brasilia ainda e o mesmo dia`() {
        assertEquals("27/10/2026", SeparacaoService.dataFaturamento(brt("2026-10-27T20:59:00"), utc))
    }

    @Test
    fun `processo em UTC - 21h01 de Brasilia sai com o dia seguinte`() {
        assertEquals("28/10/2026", SeparacaoService.dataFaturamento(brt("2026-10-27T21:01:00"), utc))
    }

    @Test
    fun `processo em UTC - 23h59 de Brasilia sai com o dia seguinte`() {
        assertEquals("28/10/2026", SeparacaoService.dataFaturamento(brt("2026-10-27T23:59:00"), utc))
    }

    @Test
    fun `processo em UTC - virada do dia e do mes`() {
        assertEquals("01/11/2026", SeparacaoService.dataFaturamento(brt("2026-10-31T23:30:00"), utc))
        assertEquals("01/11/2026", SeparacaoService.dataFaturamento(brt("2026-11-01T00:01:00"), utc))
    }

    @Test
    fun `processo em Brasilia - sempre o dia de Brasilia`() {
        assertEquals("27/10/2026", SeparacaoService.dataFaturamento(brt("2026-10-27T21:01:00"), brasilia))
        assertEquals("27/10/2026", SeparacaoService.dataFaturamento(brt("2026-10-27T23:59:00"), brasilia))
        assertEquals("28/10/2026", SeparacaoService.dataFaturamento(brt("2026-10-28T00:00:00"), brasilia))
    }
}
