package wms.backend.usuarios

import kotlin.test.Test
import kotlin.test.assertEquals

class NomeUsuarioTest {
    @Test
    fun `maiusculas viram capitalizado com particulas em minusculo`() {
        assertEquals("Mateus Vitor de Lima Silva", NomeUsuario.padronizar("MATEUS VITOR DE LIMA SILVA"))
        assertEquals("Yuri Antônio da Silva Xavier", NomeUsuario.padronizar("YURI ANTÔNIO DA SILVA XAVIER"))
        assertEquals("Linaldo da Cruz Castro", NomeUsuario.padronizar("  LINALDO  DA CRUZ CASTRO "))
    }

    @Test
    fun `nome ja padronizado nao muda`() {
        assertEquals("Marcos Vinicius Gomes Pereira da Silva", NomeUsuario.padronizar("Marcos Vinicius Gomes Pereira da Silva"))
        assertEquals("Stage 1", NomeUsuario.padronizar("Stage 1"))
    }
}
