package wms.backend.separacao

import kotlinx.coroutines.runBlocking
import wms.backend.tenancy.Modulos
import wms.backend.tenancy.ParametrosLiberacaoHerdada
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Caracterização (Entrega 1, Fase 0) — liberação herdada como rodava antes do módulo `liberacao_herdada`.
 * Desde a Fase 1 o mesmo comportamento só existe no perfil da Negri (módulo + evento 3 + valor não comparado,
 * V62) — estes testes rodam com esse perfil e provam que ele não mudou.
 * Sem Sankhya: a [LiberacaoHerdada.Porta] falsa responde a consulta e registra o que seria liberado.
 */
class LiberacaoHerdadaCaracterizacaoTest {

    private class PortaFalsa(
        val linhas: List<Map<String, String?>> = emptyList(),
        val credencial: Pair<String, String>? = "liberador" to "segredo",
        val validacaoFalha: Boolean = false,
        val falhaNaSequencia: String? = null,
    ) : LiberacaoHerdada.Porta {
        override fun modulos(tenantId: UUID) = setOf(Modulos.LIBERACAO_HERDADA)
        override fun parametros(tenantId: UUID) = ParametrosLiberacaoHerdada(eventos = listOf(3), compararValor = false)
        var consultas = 0
        var credencialLida = false
        var validacoes = 0
        val liberadas = mutableListOf<Pair<Map<String, String?>, String>>()

        override suspend fun pendentes(tenantSlug: String, sql: String): List<Map<String, String?>> { consultas++; return linhas }
        override fun credenciais(): Pair<String, String>? { credencialLida = true; return credencial }
        override suspend fun validar(tenantSlug: String, usuario: String, senha: String): String {
            validacoes++
            if (validacaoFalha) throw IllegalStateException("Usuário ou senha inválidos no Sankhya.")
            return "27"
        }
        override suspend fun liberar(tenantSlug: String, linha: Map<String, String?>, codusu: String, obs: String) {
            if (linha["SEQUENCIA"] == falhaNaSequencia) throw IllegalStateException("recusado")
            liberadas += linha to obs
        }
    }

    private val tenant = UUID.randomUUID()

    private fun pendente(seq: String, tabela: String = "TGFCAB") = mapOf(
        "NUCHAVE" to "70000", "TABELA" to tabela, "EVENTO" to "3.0", "SEQUENCIA" to seq, "SEQCASCATA" to "0", "NUCLL" to "0",
        "VLRATUAL" to "21215.21", "NUNOTAORIG" to "69995", "CODUSULIB" to "8", "VLRORIG" to "19953.92",
        "DHLIBORIG" to "15/10/2026 10:00", "NOMEUSU" to "LIBERADOR",
    )

    @Test
    fun `existe liberacao na origem - libera cada pendencia com o liberador de servico`() = runBlocking {
        val porta = PortaFalsa(linhas = listOf(pendente("0"), pendente("2", "TGFITE")))
        assertEquals(2, LiberacaoHerdada.herdar("t", tenant, 70000, porta))
        assertEquals(1, porta.validacoes)
        val (linha, obs) = porta.liberadas.first()
        // chaves normalizadas pra inteiro puro ("3.0" -> "3")
        assertEquals(mapOf("NUCHAVE" to "70000", "EVENTO" to "3", "NUCLL" to "0", "SEQCASCATA" to "0", "SEQUENCIA" to "0", "TABELA" to "TGFCAB"), linha)
        assertEquals(
            "Liberação automática: evento 3.0 já liberado no Nro. Único 69995 por LIBERADOR em 15/10/2026 10:00 " +
                "(liberado lá: 19953.92; aqui: 21215.21).",
            obs,
        )
    }

    @Test
    fun `valor NAO e comparado - aqui maior que o liberado na origem ainda libera`() = runBlocking {
        val porta = PortaFalsa(linhas = listOf(pendente("0")))
        assertEquals(1, LiberacaoHerdada.herdar("t", tenant, 70000, porta))
        val sql = LiberacaoHerdada.sqlPendentesComOrigem(70000, listOf(3))
        assertTrue("O.VLRLIBERADO > 0" in sql)
        assertFalse("L.VLRATUAL <= O.VLRLIBERADO" in sql)
    }

    @Test
    fun `nao existe liberacao na origem - nada a fazer, nem le o liberador`() = runBlocking {
        val porta = PortaFalsa(linhas = emptyList())
        assertEquals(0, LiberacaoHerdada.herdar("t", tenant, 70000, porta))
        assertEquals(1, porta.consultas)
        assertFalse(porta.credencialLida)
    }

    @Test
    fun `so evento 3 - perfil Negri`() {
        val sql = LiberacaoHerdada.sqlPendentesComOrigem(70000, listOf(3))
        assertTrue("L.EVENTO IN (3)" in sql)
        assertTrue("L.NUCHAVE = 70000" in sql)
        assertTrue("L.TABELA IN ('TGFCAB', 'TGFITE')" in sql)
    }

    @Test
    fun `liberador nao configurado - nao libera`() = runBlocking {
        val porta = PortaFalsa(linhas = listOf(pendente("0")), credencial = null)
        assertEquals(0, LiberacaoHerdada.herdar("t", tenant, 70000, porta))
        assertEquals(0, porta.validacoes)
        assertTrue(porta.liberadas.isEmpty())
    }

    @Test
    fun `liberador invalido - nao libera`() = runBlocking {
        val porta = PortaFalsa(linhas = listOf(pendente("0")), validacaoFalha = true)
        assertEquals(0, LiberacaoHerdada.herdar("t", tenant, 70000, porta))
        assertTrue(porta.liberadas.isEmpty())
    }

    @Test
    fun `falha numa pendencia nao para as outras`() = runBlocking {
        val porta = PortaFalsa(linhas = listOf(pendente("1", "TGFITE"), pendente("2", "TGFITE")), falhaNaSequencia = "1")
        assertEquals(1, LiberacaoHerdada.herdar("t", tenant, 70000, porta))
        assertEquals("2", porta.liberadas.single().first["SEQUENCIA"])
    }
}
