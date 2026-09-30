package wms.backend.permissoes

import wms.backend.auth.ClaimsToken
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Regra pura de PermissoesRecurso — cenários do pedido (TOP X / Balança 01, usuários A, B, C). */
class PermissoesRecursoTest {
    private val a = UUID.randomUUID()
    private val b = UUID.randomUUID()
    private val c = UUID.randomUUID()
    private val topX = 1001
    private val balanca01 = UUID.randomUUID()

    private val tops = mapOf(topX to setOf(a, c))
    private val balancas = mapOf(balanca01 to setOf(a, b))

    @Test
    fun `cenario 1 - usuario vinculado usa a TOP`() = assertTrue(PermissoesRecurso.podeUsarTop(tops, a, topX))

    @Test
    fun `cenario 2 - usuario nao vinculado nao usa a TOP`() = assertFalse(PermissoesRecurso.podeUsarTop(tops, b, topX))

    @Test
    fun `cenario 3 - usuario vinculado usa a balanca`() = assertTrue(PermissoesRecurso.podeUsarBalanca(balancas, a, balanca01))

    @Test
    fun `cenario 4 e 5 - C usa a TOP X mas nao a balanca 01`() {
        assertTrue(PermissoesRecurso.podeUsarTop(tops, c, topX))
        assertFalse(PermissoesRecurso.podeUsarBalanca(balancas, c, balanca01))
    }

    @Test
    fun `recurso sem usuario vinculado e sem restricao`() {
        assertTrue(PermissoesRecurso.podeUsarTop(tops, b, 1401))
        assertTrue(PermissoesRecurso.podeUsarBalanca(balancas, c, UUID.randomUUID()))
        assertTrue(PermissoesRecurso.podeUsarTop(tops, b, null))
    }

    @Test
    fun `sem usuario conhecido so usa recurso sem restricao`() {
        assertFalse(PermissoesRecurso.podeUsarTop(tops, null, topX))
        assertTrue(PermissoesRecurso.podeUsarTop(tops, null, 1401))
    }

    @Test
    fun `admin acessa tudo, estacao conta pela propria conta`() {
        val tenant = UUID.randomUUID()
        assertTrue(PermissoesRecurso.podeUsarTop(ClaimsToken(b, tenant, "ADMINISTRADOR"), tops, topX))
        assertTrue(PermissoesRecurso.podeUsarBalanca(ClaimsToken(c, tenant, "ADMINISTRADOR"), balancas, balanca01))
        assertFalse(PermissoesRecurso.podeUsarTop(ClaimsToken(b, tenant, "ESTACAO"), tops, topX))
        assertTrue(PermissoesRecurso.podeUsarTop(ClaimsToken(a, tenant, "ESTACAO"), tops, topX))
    }
}
