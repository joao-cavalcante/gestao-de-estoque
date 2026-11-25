package wms.backend.permissoes

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Acesso por empresa (V80) — a regra pura que todas as telas usam. */
class EmpresasAcessoTest {

    @Test
    fun `sem restricao ve tudo, inclusive nota de empresa desconhecida`() {
        assertTrue(EmpresasAcesso.permite(null, 1))
        assertTrue(EmpresasAcesso.permite(null, null))
    }

    @Test
    fun `com restricao ve so as empresas dele e nunca a desconhecida`() {
        val p = setOf(2)
        assertTrue(EmpresasAcesso.permite(p, 2))
        assertFalse(EmpresasAcesso.permite(p, 1))
        assertFalse(EmpresasAcesso.permite(p, null))
    }

    @Test
    fun `codemp aceita os formatos que vem do Sankhya`() {
        assertEquals(1, EmpresasAcesso.codemp("1"))
        assertEquals(1, EmpresasAcesso.codemp(" 1.0 "))
        assertEquals(12, EmpresasAcesso.codemp("12,0"))
        assertNull(EmpresasAcesso.codemp(""))
        assertNull(EmpresasAcesso.codemp(null))
    }
}
