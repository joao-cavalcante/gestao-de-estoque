package wms.backend.usuarios

import wms.backend.tenancy.ParametrosTenant
import wms.backend.tenancy.ParametrosUsuarioSankhya
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Entrega 1, Fase 5 (M24) — criação de usuário no Sankhya só com configuração explícita do tenant. */
class SankhyaUsuarioConfiguracaoTest {
    private val outroCliente = ParametrosUsuarioSankhya(habilitado = true, usuarioModelo = 55, codGrupo = 7, dominioEmail = "cliente.com.br")

    @Test
    fun `instalacao padrao - criacao indisponivel`() {
        val padrao = ParametrosTenant().usuarioSankhya
        assertFalse(padrao.habilitado)
        assertEquals("criação de usuário no Sankhya não habilitada pra esta instalação", SankhyaUsuarioService.motivoIndisponivel(padrao))
    }

    @Test
    fun `configuracao incompleta - indisponivel, nada escolhido sozinho`() {
        assertEquals("usuário-modelo não configurado", SankhyaUsuarioService.motivoIndisponivel(outroCliente.copy(usuarioModelo = null)))
        assertEquals("grupo do usuário não configurado", SankhyaUsuarioService.motivoIndisponivel(outroCliente.copy(codGrupo = null)))
        assertEquals("domínio de e-mail não configurado", SankhyaUsuarioService.motivoIndisponivel(outroCliente.copy(dominioEmail = " ")))
    }

    @Test
    fun `grupo ou modelo invalidos na configuracao - nao grava e fica indisponivel`() {
        assertTrue(outroCliente.copy(codGrupo = -1).validar().isNotEmpty())
        assertTrue(outroCliente.copy(usuarioModelo = 0).validar().isNotEmpty())
        assertTrue(outroCliente.copy(dominioEmail = "sem dominio").validar().isNotEmpty())
        assertTrue(outroCliente.copy(camposFixos = mapOf("CODGRUPO" to "1")).validar().isNotEmpty())
        assertNotNull(SankhyaUsuarioService.motivoIndisponivel(outroCliente.copy(codGrupo = -1)))
        assertTrue(ParametrosTenant(usuarioSankhya = outroCliente.copy(codGrupo = -1)).validar().isNotEmpty())
    }

    @Test
    fun `perfil com usuario-modelo - valores lidos do modelo, grupo e dominio do tenant`() {
        assertNull(SankhyaUsuarioService.motivoIndisponivel(outroCliente))
        val modelo = mapOf(
            "NIVEL" to "50", "LOCALE" to "PT_BR", "CODVEND" to " 0 ", "ABREGAVETA" to null,
            // identidade e campos fora da lista nunca são copiados do modelo
            "CODUSU" to "55", "NOMEUSU" to "MODELO", "ACCOUNTEMAIL" to "modelo@cliente.com.br", "INTERNO" to "x", "CODGRUPO" to "99",
        )
        val campos = SankhyaUsuarioService.montarCampos(outroCliente, 300, "ANA SOUZA", "Ana Souza", null, modelo)
        assertEquals(
            mapOf(
                "NIVEL" to "50", "LOCALE" to "PT_BR", "CODVEND" to "0", "CODGRUPO" to "7", "CODUSU" to "300",
                "NOMEUSU" to "ANA SOUZA", "ACCOUNTEMAIL" to "ana.souza@cliente.com.br",
            ),
            campos.toMap(),
        )
    }

    @Test
    fun `dominio aceita arroba na frente`() {
        assertEquals("ana.souza@cliente.com.br", SankhyaUsuarioService.emailSankhya("Ana Souza", "@cliente.com.br"))
    }

    @Test
    fun `nenhum codigo da Negri vale pra outro tenant`() {
        val campos = SankhyaUsuarioService.montarCampos(outroCliente, 300, "ANA SOUZA", "Ana Souza", null, mapOf("NIVEL" to "50"))
        assertFalse(campos.values.any { "negri" in it.lowercase() })
        assertFalse(campos["CODGRUPO"] == "32")
        val padrao = ParametrosTenant()
        assertNull(padrao.usuarioSankhya.usuarioModelo)
        assertNull(padrao.usuarioSankhya.codGrupo)
        assertNull(padrao.usuarioSankhya.dominioEmail)
        assertTrue(padrao.usuarioSankhya.camposFixos.isEmpty())
    }
}
