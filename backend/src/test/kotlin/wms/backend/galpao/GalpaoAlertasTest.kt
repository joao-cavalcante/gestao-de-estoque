package wms.backend.galpao

import java.time.Instant
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Regras de SyncService.generateAlerts do Negri Planta Log 3D, portadas em GalpaoAlertas.ocorrencias. */
class GalpaoAlertasTest {
    private val hoje = LocalDate.of(2026, 10, 30)
    private val p = ParametrosAnaliseDto()

    private fun produto(cod: Int, setor: Int? = 1, peso: Double? = 1.0, m3: Double? = 0.01) =
        GalpaoAnalisesService.LinhaProduto(cod, "P$cod", true, "UN", 10, "G", setor, null, peso, m3)

    private fun endereco(
        id: String,
        setor: String = "SECO",
        saldos: List<SaldoEnderecoDto> = emptyList(),
        capQtd: Double? = 100.0,
        ocupacao: Double? = 0.0,
        bloqueado: Boolean = false,
    ) = EnderecoDto(
        id = id, codigo = "E-$id", setor = setor, estrutura = "R01", modulo = 1, nivel = 1, tipo = "PICKING",
        x = 0.0, y = 0.0, z = 0.0, rotacao = 0.0, largura = 1.0, comprimento = 1.0, altura = 1.0,
        capQtd = capQtd, capPeso = null, capVolume = null, bloqueado = bloqueado,
        quantidade = saldos.sumOf { it.quantidade }, ocupacao = ocupacao, baseOcupacao = null, saldos = saldos, contagensPendentes = 0,
    )

    private fun saldo(cod: Int, qtd: Double, controle: String = "") = SaldoEnderecoDto(cod, "P$cod", controle, qtd, 1)

    private fun cenario(
        produtos: List<GalpaoAnalisesService.LinhaProduto>,
        estoque: List<GalpaoAnalisesService.LinhaEstoque>,
        vendas: List<GalpaoAnalises.LinhaVenda>,
        enderecos: List<EnderecoDto>,
        setores: List<SetorPlanta> = listOf(SetorPlanta("SECO", "Seco", tipoSeparacao = 1)),
    ): List<Ocorrencia> {
        val leitura = GalpaoAnalisesService.Leitura(produtos, estoque, vendas, emptyList(), p, Instant.now())
        val enderecado = enderecos.flatMap { it.saldos }.groupBy { it.codprod }.mapValues { (_, v) -> v.sumOf { it.quantidade } }
        val analises = GalpaoAnalisesService.montar(produtos, estoque, vendas, emptyList(), enderecado, p, hoje)
        val planta = PlantaDto("p", "Planta", 1, 101, 10.0, 10.0, 1, enderecos.size, false)
        val visao = VisaoPlantaDto(planta, GeometriaPlanta(10.0, 10.0, setores = setores), enderecos)
        return GalpaoAlertas.ocorrencias(leitura, analises, visao)
    }

    private fun List<Ocorrencia>.codigos() = map { it.codigo }.toSet()

    @Test
    fun `estoque negativo, reserva excedente e validade ausente por lote`() {
        val r = cenario(
            listOf(produto(1)),
            listOf(GalpaoAnalisesService.LinhaEstoque(1, "L1", -2.0, 1.0, null, null)),
            emptyList(),
            emptyList(),
        )
        assertTrue(setOf("ESTOQUE_NEGATIVO", "RESERVA_EXCEDENTE", "VALIDADE_AUSENTE").all { it in r.codigos() })
        assertEquals("CRITICO", r.first { it.codigo == "ESTOQUE_NEGATIVO" }.severidade)
    }

    @Test
    fun `ruptura com demanda e sem disponivel, sem setor e sem dimensoes`() {
        val r = cenario(
            listOf(produto(1, setor = null, m3 = null)),
            listOf(GalpaoAnalisesService.LinhaEstoque(1, "", 5.0, 5.0, hoje.plusDays(200), null)),
            listOf(GalpaoAnalises.LinhaVenda(1, 10.0, 90.0, 3, hoje)),
            emptyList(),
        )
        assertTrue("RUPTURA" in r.codigos())
        assertTrue("SEM_SETOR" in r.codigos())
        assertTrue("DIMENSOES_AUSENTES" in r.codigos())
        assertFalse("ESTOQUE_PARADO" in r.codigos())
    }

    @Test
    fun `parado, cobertura alta e validade proxima`() {
        val r = cenario(
            listOf(produto(1), produto(2)),
            listOf(
                GalpaoAnalisesService.LinhaEstoque(1, "", 10.0, 0.0, hoje.plusDays(20), null),
                GalpaoAnalisesService.LinhaEstoque(2, "", 1000.0, 0.0, hoje.plusDays(200), null),
            ),
            listOf(GalpaoAnalises.LinhaVenda(2, 10.0, 90.0, 3, hoje)),
            emptyList(),
        )
        assertTrue(r.any { it.codigo == "ESTOQUE_PARADO" && it.codprod == 1 })
        assertTrue(r.any { it.codigo == "VALIDADE_ATE_30" && it.codprod == 1 })
        assertTrue(r.any { it.codigo == "COBERTURA_ALTA" && it.codprod == 2 }) // 1000 / (90/90) = 1000 dias
    }

    @Test
    fun `endereco - sobrecapacidade, bloqueado com saldo e setor incompativel pelo tipo do setor`() {
        val e1 = endereco("1", saldos = listOf(saldo(2, 150.0)), ocupacao = 1.5, bloqueado = true)
        val r = cenario(
            listOf(produto(2, setor = 3)),
            listOf(GalpaoAnalisesService.LinhaEstoque(2, "", 150.0, 0.0, null, null)),
            emptyList(),
            listOf(e1),
        )
        assertTrue("SOBRECAPACIDADE" in r.codigos())
        assertTrue("BLOQUEADO_COM_SALDO_TAREFA" in r.codigos())
        assertTrue(r.any { it.codigo == "SETOR_INCOMPATIVEL" && it.codprod == 2 && it.enderecoId == "1" })
        // Sem tipo no setor da planta: não há como comparar, não alerta.
        val semTipo = cenario(listOf(produto(2, setor = 3)), emptyList(), emptyList(), listOf(e1), listOf(SetorPlanta("SECO", "Seco")))
        assertFalse("SETOR_INCOMPATIVEL" in semTipo.codigos())
    }

    @Test
    fun `capacidade ausente e conciliacao contabil x enderecado`() {
        val r = cenario(
            listOf(produto(1), produto(2)),
            listOf(
                GalpaoAnalisesService.LinhaEstoque(1, "", 10.0, 0.0, null, null),
                GalpaoAnalisesService.LinhaEstoque(2, "", 10.0, 0.0, null, null),
            ),
            emptyList(),
            listOf(endereco("9", capQtd = null, ocupacao = null, saldos = listOf(saldo(2, 4.0)))),
        )
        assertTrue("CAPACIDADE_AUSENTE" in r.codigos())
        assertTrue(r.any { it.codigo == "SALDO_SEM_ENDERECO" && it.codprod == 1 })
        assertFalse(r.any { it.codigo == "SALDO_SEM_ENDERECO" && it.codprod == 2 })
        assertTrue(r.any { it.codigo == "RECONCILIACAO" && it.codprod == 2 })
        // Chaves únicas: a mesma ocorrência não aparece duas vezes.
        assertEquals(r.size, r.map { it.chave }.toSet().size)
    }
}
