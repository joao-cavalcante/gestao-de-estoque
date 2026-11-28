package wms.backend.paletes

import java.math.BigDecimal
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** V84 — "Fechar palete" com vários conferentes: cada um vê o que leu + o bolso comum (leituras antigas sem dono). */
class BolsosConferenteTest {
    private val a = UUID.randomUUID()
    private val b = UUID.randomUUID()
    private val p1 = 1 to " "
    private val p2 = 2 to " "
    private fun d(n: Int) = BigDecimal(n)

    @Test
    fun `cada conferente so ve o que leu`() {
        val (proprio, comum) = Paletes.bolsos(
            listOf(Triple(a, p1, d(10)), Triple(b, p1, d(7)), Triple(b, p2, d(3))),
            listOf(Triple(a, p1, d(4))),
        )
        assertEquals(d(6), proprio[a]!![p1])
        assertEquals(d(7), proprio[b]!![p1])
        assertEquals(d(3), proprio[b]!![p2])
        assertTrue(comum.isEmpty())
    }

    @Test
    fun `palete acima do proprio consome o bolso comum`() {
        val (proprio, comum) = Paletes.bolsos(
            listOf(Triple(null, p1, d(10)), Triple(a, p1, d(2))),
            listOf(Triple(a, p1, d(5))),
        )
        assertTrue(proprio[a]!!.isEmpty())
        assertEquals(d(7), comum[p1])
    }

    @Test
    fun `tudo em palete zera`() {
        val (proprio, comum) = Paletes.bolsos(
            listOf(Triple(a, p1, d(5)), Triple(null, p2, d(2))),
            listOf(Triple(a, p1, d(5)), Triple(null, p2, d(2))),
        )
        assertTrue(proprio[a]!!.isEmpty())
        assertTrue(comum.isEmpty())
    }
}
