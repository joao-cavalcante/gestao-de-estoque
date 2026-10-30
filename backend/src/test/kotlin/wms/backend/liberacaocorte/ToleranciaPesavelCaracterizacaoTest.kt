package wms.backend.liberacaocorte

import wms.backend.configconferencia.ConferenciaToleranciaRepository
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Caracterização (Entrega 1, Fase 0) — conta da auto-liberação de pesável como roda HOJE. Inclui o que é
 * inadequado pro produto: NUCCO sem configuração = acima SEM LIMITE, e o limite exato que falha por ponto flutuante.
 */
class ToleranciaPesavelCaracterizacaoTest {
    private fun ok(conf: Double?, ped: Double?, acima: Double?, abaixo: Double?) =
        LiberacaoCorteService.dentroDaTolerancia(conf, ped, acima, abaixo)

    // Padrão de NUCCO sem linha na V50 (copiado pra sessão): acima null (sem limite), abaixo 5%.
    private val acimaPadrao = ConferenciaToleranciaRepository.PADRAO.acimaPct
    private val abaixoPadrao = ConferenciaToleranciaRepository.PADRAO.abaixoPct

    @Test
    fun `padrao sem NUCCO e acima sem limite e abaixo 5 por cento`() {
        assertNull(acimaPadrao)
        assertEquals(5.0, abaixoPadrao)
        assertFalse(ConferenciaToleranciaRepository.PADRAO.configurada)
    }

    @Test
    fun `sem NUCCO - abaixo do previsto`() {
        assertTrue(ok(95.0, 100.0, acimaPadrao, abaixoPadrao)) // -5,00% (limite exato)
        assertFalse(ok(94.99, 100.0, acimaPadrao, abaixoPadrao)) // -5,01%
        assertFalse(ok(90.0, 100.0, acimaPadrao, abaixoPadrao))
    }

    @Test
    fun `sem NUCCO - igual ao previsto`() {
        assertTrue(ok(100.0, 100.0, acimaPadrao, abaixoPadrao))
    }

    @Test
    fun `sem NUCCO - acima do previsto libera qualquer excesso`() {
        assertTrue(ok(100.01, 100.0, acimaPadrao, abaixoPadrao))
        assertTrue(ok(150.0, 100.0, acimaPadrao, abaixoPadrao))
        assertTrue(ok(10_000.0, 100.0, acimaPadrao, abaixoPadrao))
    }

    @Test
    fun `NUCCO configurado - acima 2 e abaixo 0`() {
        assertTrue(ok(102.0, 100.0, 2.0, 0.0)) // limite exato
        assertFalse(ok(102.01, 100.0, 2.0, 0.0))
        assertTrue(ok(100.0, 100.0, 2.0, 0.0))
        assertFalse(ok(99.99, 100.0, 2.0, 0.0))
    }

    @Test
    fun `NUCCO configurado com sem limite explicito nos dois sentidos`() {
        assertTrue(ok(1.0, 100.0, null, null))
        assertTrue(ok(500.0, 100.0, null, null))
    }

    @Test
    fun `limite exato depende do ponto flutuante - 1,00 x 0,95 nao libera, 100 x 95 libera`() {
        assertFalse(ok(0.95, 1.0, acimaPadrao, 5.0))
        assertTrue(ok(95.0, 100.0, acimaPadrao, 5.0))
        assertFalse(ok(1.05, 1.0, 5.0, 5.0))
    }

    @Test
    fun `peso zero e valores nulos`() {
        assertFalse(ok(0.0, 0.0, null, null)) // pedido e conferido zerados
        assertTrue(ok(5.0, 0.0, null, 5.0)) // pedido zero, conferido 5: conta como A MAIOR sobre o conferido; sem limite libera
        assertFalse(ok(5.0, 0.0, 2.0, 5.0)) // ... com limite de 2% não libera (100%)
        assertTrue(ok(0.0, 5.0, null, null)) // conferido zero, abaixo sem limite: libera
        assertFalse(ok(0.0, 5.0, null, 5.0))
        assertFalse(ok(null, 100.0, null, null))
        assertFalse(ok(100.0, null, null, null))
    }
}
