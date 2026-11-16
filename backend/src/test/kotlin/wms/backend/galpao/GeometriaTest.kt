package wms.backend.galpao

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class GeometriaTest {

    private val setores = listOf(SetorPlanta("SECO", "Seco"), SetorPlanta("FRIO", "Resfriado"))

    private fun estrutura(codigo: String, x: Double, z: Double, rotacao: Double = 0.0, setor: String? = "SECO") = ObjetoPlanta(
        codigo = codigo, tipo = TipoObjeto.ESTRUTURA, x = x, z = z, largura = 1.2, profundidade = 12.0, altura = 6.0,
        rotacao = rotacao, setor = setor, modulos = 4, niveis = 3, nivelPicking = 1, capQtd = 100.0,
    )

    private fun planta(vararg objetos: ObjetoPlanta) = GeometriaPlanta(largura = 50.0, profundidade = 40.0, setores = setores, objetos = objetos.toList())

    @Test
    fun `desenho valido nao tem erros e gera modulo vezes nivel enderecos`() {
        val g = planta(estrutura("R01", 5.0, 10.0), estrutura("R02", 10.0, 10.0))
        assertEquals(emptyList(), ValidadorGeometria.validar(g))
        val enderecos = GeradorEnderecos.gerar(g)
        assertEquals(2 * 4 * 3, enderecos.size)
        assertEquals("R01-M01-N01", enderecos.first().codigo)
        assertEquals("R02-M04-N03", enderecos.last().codigo)
    }

    @Test
    fun `nivel 1 e picking e acima e pulmao`() {
        val e = GeradorEnderecos.gerar(estrutura("R01", 5.0, 10.0))
        assertTrue(e.filter { it.nivel == 1 }.all { it.tipo == "PICKING" })
        assertTrue(e.filter { it.nivel > 1 }.all { it.tipo == "PULMAO" })
    }

    @Test
    fun `modulos ficam distribuidos ao longo da estrutura e giram com ela`() {
        val reto = GeradorEnderecos.gerar(estrutura("R01", 10.0, 10.0)).filter { it.nivel == 1 }
        assertEquals(listOf(5.5, 8.5, 11.5, 14.5), reto.map { it.z })
        assertTrue(reto.all { it.x == 10.0 })

        val girado = GeradorEnderecos.gerar(estrutura("R01", 10.0, 10.0, rotacao = 90.0)).filter { it.nivel == 1 }
        girado.zip(listOf(14.5, 11.5, 8.5, 5.5)).forEach { (e, x) -> assertEquals(x, e.x, 1e-9) }
        assertTrue(girado.all { kotlin.math.abs(it.z - 10.0) < 1e-9 })
    }

    @Test
    fun `estruturas sobrepostas sao recusadas, encostadas nao`() {
        val sobrepostas = planta(estrutura("R01", 5.0, 10.0), estrutura("R02", 5.5, 10.0))
        assertTrue(ValidadorGeometria.validar(sobrepostas).any { "sobrepostas" in it })

        val encostadas = planta(estrutura("R01", 5.0, 10.0), estrutura("R02", 6.2, 10.0))
        assertFalse(ValidadorGeometria.validar(encostadas).any { "sobrepostas" in it })
    }

    @Test
    fun `objeto fora da planta, codigo repetido e setor desconhecido`() {
        val erros = ValidadorGeometria.validar(planta(estrutura("R01", 49.9, 10.0), estrutura("R01", 20.0, 10.0, setor = "XX")))
        assertTrue(erros.any { "fora da planta" in it })
        assertTrue(erros.any { "repetido" in it })
        assertTrue(erros.any { "sem setor" in it })
    }

    @Test
    fun `estrutura sem capacidade e corredor estreito`() {
        val semCap = estrutura("R01", 5.0, 10.0).copy(capQtd = null)
        val corredor = ObjetoPlanta("C01", TipoObjeto.CORREDOR, 20.0, 10.0, 1.5, 20.0, 0.1)
        val erros = ValidadorGeometria.validar(planta(semCap, corredor))
        assertTrue(erros.any { "sem capacidade" in it })
        assertTrue(erros.any { "Corredor abaixo" in it })
    }

    @Test
    fun `padrao de endereco sem nivel gera codigos repetidos e e recusado`() {
        val g = planta(estrutura("R01", 5.0, 10.0)).copy(padraoEndereco = "{estrutura}-{modulo}")
        assertTrue(ValidadorGeometria.validar(g).any { "Padrão de endereço" in it })
    }

    @Test
    fun `hash e estavel e muda com o desenho`() {
        val a = planta(estrutura("R01", 5.0, 10.0))
        assertEquals(GeometriaJson.hash(a), GeometriaJson.hash(GeometriaJson.ler(GeometriaJson.escrever(a))))
        assertFalse(GeometriaJson.hash(a) == GeometriaJson.hash(a.copy(largura = 51.0)))
    }
}
