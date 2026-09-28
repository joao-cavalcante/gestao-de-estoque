package wms.backend.produtos

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Decisão pura de RegraPesavel — Negri (módulo PESAVEL_POR_PRODUTO) e regra padrão por unidade. */
class RegraPesavelTest {
    private val unidadesPesaveis = setOf("KG")

    // ─── Módulo ligado (Negri): só TGFPRO.AD_PESAVEL ───────────────────────

    @Test
    fun `modulo - AD_PESAVEL S e pesavel`() {
        assertTrue(RegraPesavel.ehPesavel(true, "S", null, null, emptySet()))
        assertTrue(RegraPesavel.ehPesavel(true, " s ", null, null, emptySet()))
    }

    @Test
    fun `modulo - AD_PESAVEL N nao e pesavel`() {
        assertFalse(RegraPesavel.ehPesavel(true, "N", null, null, emptySet()))
    }

    @Test
    fun `modulo - AD_PESAVEL nulo ou vazio nao e pesavel`() {
        assertFalse(RegraPesavel.ehPesavel(true, null, null, null, emptySet()))
        assertFalse(RegraPesavel.ehPesavel(true, "  ", null, null, emptySet()))
    }

    @Test
    fun `modulo - unidade pesavel na TGFVOL nao conta`() {
        // Produto em KG (UTILICONFPESO='S') com AD_PESAVEL='N': a unidade não decide mais.
        assertFalse(RegraPesavel.ehPesavel(true, "N", "KG", "KG", unidadesPesaveis))
        // E o contrário: unidade não pesável com AD_PESAVEL='S' é pesável.
        assertTrue(RegraPesavel.ehPesavel(true, "S", "UN", "UN", unidadesPesaveis))
    }

    // ─── Demais tenants: regra por unidade (inalterada) ────────────────────

    @Test
    fun `padrao - unidade de cadastro pesavel`() {
        assertTrue(RegraPesavel.ehPesavel(false, null, "KG", "PC", unidadesPesaveis))
    }

    @Test
    fun `padrao - unidade de cadastro nao pesavel`() {
        assertFalse(RegraPesavel.ehPesavel(false, null, "UN", "KG", unidadesPesaveis))
    }

    @Test
    fun `padrao - sem unidade de cadastro cai na unidade da linha`() {
        assertTrue(RegraPesavel.ehPesavel(false, null, null, "KG", unidadesPesaveis))
        assertFalse(RegraPesavel.ehPesavel(false, null, null, "UN", unidadesPesaveis))
    }

    @Test
    fun `padrao - AD_PESAVEL e ignorado`() {
        assertFalse(RegraPesavel.ehPesavel(false, "S", "UN", "UN", unidadesPesaveis))
    }
}
