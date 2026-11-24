package wms.backend.cargas

import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CargasRegrasTest {
    private val zona = ZoneId.of("America/Sao_Paulo")
    private val hoje = LocalDate.of(2026, 11, 24)

    private fun pedido(nunota: Long, status: String = "concluido", oc: Long = 100, total: Int? = null, feito: Int? = null,
                       nota: CargasRegras.NotaResumo? = null, dtneg: LocalDate = hoje) =
        CargasRegras.Pedido(nunota, oc, status, nunota.toString(), "CLIENTE $nunota", 100.0, 10, false, false, dtneg, total, feito, nota)

    private fun fechamento(oc: Long, h: Int, dia: LocalDate = hoje, motivo: String = "fechamento", status: String = "impresso") =
        CargasRegras.Fechamento(oc, ZonedDateTime.of(dia.atTime(h, 5), zona).toInstant(), "Ana", motivo, status, null, null)

    private fun montar(pedidos: List<CargasRegras.Pedido>, fech: List<CargasRegras.Fechamento> = emptyList(),
                       fechadas: Set<Long> = emptySet(), dia: LocalDate = hoje) =
        CargasRegras.montar(pedidos, fech,
            pedidos.map { it.ordemCarga }.associateWith { CargasRegras.Transporte("JOAO", "ABC1D23", null, it in fechadas, 1000.0, hoje) },
            dia, hoje, zona)

    @Test
    fun `pedido a conferir, corte ou a carregar deixa a OC pendente e diz o que falta`() {
        val c = montar(listOf(
            pedido(1, "aguardando"), pedido(2, "aguardando_corte"), pedido(3, total = 4, feito = 1), pedido(4), pedido(5, "andamento"),
        )).single()
        assertEquals("pendente", c.situacao)
        assertEquals(FaltasDto(corte = 1, aConferir = 1, emConferencia = 1, aCarregar = 1), c.faltas)
        assertEquals(1, c.prontos)
        assertEquals(500.0, c.pesoKg)
        assertEquals("corte", c.pedidos.first().situacao)
    }

    @Test
    fun `tudo conferido e carregado e sem Fechar OC = pronta`() {
        assertEquals("pronta", montar(listOf(pedido(1, total = 3, feito = 3), pedido(2, "concluido_divergente"))).single().situacao)
    }

    @Test
    fun `Fechar OC no dia = saiu com hora e quem fechou e impressao do ultimo job`() {
        val c = montar(listOf(pedido(1)), listOf(fechamento(100, 14), fechamento(100, 15, motivo = "reimpressao", status = "erro")), fechadas = setOf(100)).single()
        assertEquals("saiu", c.situacao)
        assertEquals("Ana", c.fechadaPor)
        assertEquals(ZonedDateTime.of(hoje.atTime(14, 5), zona).toInstant().toString(), c.saiuEm)
        assertEquals("erro", c.impressao?.status)
    }

    @Test
    fun `saiu em outro dia ou fechada sem registro nao aparece`() {
        val ontem = hoje.minusDays(1)
        val pedidos = listOf(pedido(1), pedido(2, oc = 200))
        assertTrue(montar(pedidos, listOf(fechamento(100, 18, ontem)), fechadas = setOf(100, 200)).isEmpty())
        assertEquals(listOf(100L), montar(pedidos, listOf(fechamento(100, 18, ontem)), fechadas = setOf(100, 200), dia = ontem).map { it.ordemCarga })
    }

    @Test
    fun `OC aberta antiga com todos os pedidos ja com nota fica de fora`() {
        val nota = CargasRegras.NotaResumo(1, "Aprovada", "A", true)
        assertTrue(montar(listOf(pedido(1, nota = nota, dtneg = hoje.minusDays(30)))).isEmpty())
        val c = montar(listOf(pedido(1, nota = nota))).single()
        assertEquals("pronta", c.situacao)
        assertEquals(NotasResumoDto(total = 1, aprovadas = 1, aguardando = 0, problema = 0), c.notas)
    }

    @Test
    fun `periodos de entrega da OC sem repetir`() {
        val r = montar(listOf(pedido(1).copy(turno = "N"), pedido(2).copy(turno = "D"), pedido(3).copy(turno = "N"), pedido(4))).single()
        assertEquals(listOf("D", "N"), r.turnos)
        assertEquals("N", r.pedidos.first { it.nunota == 1L }.turno)
    }

    @Test
    fun `pendentes antes de saiu, por saida prevista e OC`() {
        val r = montar(listOf(pedido(1, oc = 300, status = "aguardando"), pedido(2, oc = 100), pedido(3, oc = 200, status = "aguardando")),
            listOf(fechamento(100, 10)), fechadas = setOf(100))
        assertEquals(listOf(200L, 300L, 100L), r.map { it.ordemCarga })
    }
}
