package wms.backend.paletes

import java.math.BigDecimal
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class PaletesTest {
    @Test
    fun `codigo da etiqueta tem P e 7 digitos`() {
        assertEquals("P0000042", Paletes.uma(42))
        assertEquals("P1234567", Paletes.uma(1234567))
    }

    @Test
    fun `quantidade na unidade da compra`() {
        assertEquals(0, BigDecimal("30").compareTo(Paletes.comercial(BigDecimal("180"), "GF", "FD", "M", BigDecimal("6"))))
        assertEquals(0, BigDecimal("24").compareTo(Paletes.comercial(BigDecimal("2"), "CX", "UN", "D", BigDecimal("12"))))
        assertNull(Paletes.comercial(BigDecimal("19"), "CX", "CX", null, null))
        assertNull(Paletes.comercial(BigDecimal("10"), "KG", "CX", "M", null))
    }
}
