package wms.backend.liberacaocorte

import wms.backend.liberacaocorte.LiberacaoCorteService.Limite
import wms.backend.tenancy.ParametrosTenant
import wms.backend.tenancy.PoliticaToleranciaPesavel
import wms.backend.tenancy.ToleranciaPadrao
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Entrega 1, Fase 3 (M23) — política de auto-liberação de pesável. Matriz: peso pedido, conferido, divergência,
 * tolerância do NUCCO (cópia da sessão), política do tenant, resultado esperado — conferido contra o obtido.
 */
class ToleranciaPesavelPoliticaTest {
    private val semPolitica = PoliticaToleranciaPesavel()
    private val negri = ParametrosTenant.ler(
        """{"toleranciaPesavel": {"semConfiguracaoNucco": {"acimaSemLimite": true, "abaixoPct": 5}}}""",
    ).toleranciaPesavel
    private val conservadora = PoliticaToleranciaPesavel(ToleranciaPadrao(acimaPct = 2.0, abaixoPct = 2.0))

    /** origem: "nucco" | "padrao" | null (sessão anterior à V64) | "sem-sessao". */
    private data class Caso(
        val pedido: Double?, val conferido: Double?, val nuccoAcima: Double?, val nuccoAbaixo: Double?,
        val origem: String?, val politica: PoliticaToleranciaPesavel, val esperado: Boolean, val nome: String,
    )

    private fun obtido(c: Caso): Boolean {
        val limites = LiberacaoCorteService.limitesAutoLiberacao(
            sessaoExiste = c.origem != "sem-sessao", c.nuccoAcima, c.nuccoAbaixo, c.origem.takeIf { it != "sem-sessao" }, c.politica,
        )
        return LiberacaoCorteService.liberaSozinho(c.conferido, c.pedido, limites)
    }

    // Sessão com NUCCO sem configuração recebe a cópia do PADRAO da V50 (acima null, abaixo 5) — igual a antes.
    private val casos = listOf(
        // ── NUCCO configurado: prioridade, igual a antes, qualquer política ──
        Caso(100.0, 102.0, 2.0, 0.0, "nucco", semPolitica, true, "nucco +2% no limite"),
        Caso(100.0, 102.01, 2.0, 0.0, "nucco", semPolitica, false, "nucco +2,01%"),
        Caso(100.0, 99.99, 2.0, 0.0, "nucco", semPolitica, false, "nucco -0,01% com abaixo 0"),
        Caso(100.0, 100.0, 2.0, 0.0, "nucco", semPolitica, true, "nucco igual"),
        Caso(100.0, 300.0, null, null, "nucco", semPolitica, true, "nucco sem limite explícito"),
        // ── NUCCO sem configuração, padrão do produto: nada sozinho ──
        Caso(100.0, 100.01, null, 5.0, "padrao", semPolitica, false, "padrão produto +0,01%"),
        Caso(100.0, 10_000.0, null, 5.0, "padrao", semPolitica, false, "padrão produto +9900% (antes liberava)"),
        Caso(100.0, 97.0, null, 5.0, "padrao", semPolitica, false, "padrão produto -3%"),
        Caso(100.0, 100.0, null, 5.0, "padrao", semPolitica, false, "padrão produto igual"),
        // ── NUCCO sem configuração, Negri (explícito): a regra de antes ──
        Caso(100.0, 10_000.0, null, 5.0, "padrao", negri, true, "Negri acima sem limite"),
        Caso(100.0, 95.0, null, 5.0, "padrao", negri, true, "Negri -5% no limite"),
        Caso(100.0, 94.99, null, 5.0, "padrao", negri, false, "Negri -5,01%"),
        Caso(100.0, 100.0, null, 5.0, "padrao", negri, true, "Negri igual"),
        // ── NUCCO sem configuração, política conservadora de outro tenant ──
        Caso(100.0, 102.0, null, 5.0, "padrao", conservadora, true, "conservadora +2% no limite"),
        Caso(100.0, 102.5, null, 5.0, "padrao", conservadora, false, "conservadora +2,5%"),
        Caso(100.0, 97.0, null, 5.0, "padrao", conservadora, false, "conservadora -3% (a cópia 5% não vale)"),
        // ── Sessão anterior à V64: segue a cópia, como antes ──
        Caso(100.0, 10_000.0, null, 5.0, null, semPolitica, true, "sessão antiga acima sem limite"),
        Caso(100.0, 94.99, null, 5.0, null, semPolitica, false, "sessão antiga -5,01%"),
        // ── Sessão não encontrada: política do tenant ──
        Caso(100.0, 150.0, null, null, "sem-sessao", semPolitica, false, "sem sessão, padrão produto"),
        Caso(100.0, 150.0, null, null, "sem-sessao", negri, true, "sem sessão, Negri"),
        // ── Zero e nulos ──
        Caso(0.0, 0.0, null, null, "nucco", semPolitica, false, "pedido e conferido zero"),
        Caso(0.0, 5.0, null, 5.0, "padrao", semPolitica, false, "pedido zero, padrão produto"),
        Caso(0.0, 5.0, null, 5.0, "padrao", negri, true, "pedido zero, Negri (acima sem limite)"),
        Caso(null, 5.0, null, null, "nucco", negri, false, "pedido nulo"),
        Caso(100.0, null, null, null, "nucco", negri, false, "conferido nulo"),
    )

    @Test
    fun `matriz de tolerancia - esperado igual ao obtido`() {
        val divergentes = casos.filter { obtido(it) != it.esperado }.map { c ->
            val pct = if (c.pedido != null && c.conferido != null && c.pedido != 0.0) "%.2f%%".format((c.conferido - c.pedido) / c.pedido * 100) else "-"
            "${c.nome}: pedido ${c.pedido}, conferido ${c.conferido} ($pct), nucco ${c.nuccoAcima}/${c.nuccoAbaixo}, " +
                "origem ${c.origem}, esperado ${c.esperado}, obtido ${!c.esperado}"
        }
        assertTrue(divergentes.isEmpty(), divergentes.joinToString("\n"))
    }

    @Test
    fun `politica registrada em cada decisao`() {
        fun politica(origem: String?, p: PoliticaToleranciaPesavel, existe: Boolean = true) =
            LiberacaoCorteService.limitesAutoLiberacao(existe, null, 5.0, origem, p).politica
        assertEquals("NUCCO", politica("nucco", semPolitica))
        assertEquals("tolerância da sessão (anterior à V64)", politica(null, semPolitica))
        assertEquals("padrão do produto (sem auto-liberação)", politica("padrao", semPolitica))
        assertEquals("padrão da instalação", politica("padrao", negri))
        assertEquals("padrão do produto (sem auto-liberação)", politica(null, semPolitica, existe = false))
    }

    @Test
    fun `Negri explicita - acima sem limite e abaixo 5`() {
        val l = LiberacaoCorteService.limitesAutoLiberacao(true, null, 5.0, "padrao", negri)
        assertEquals(Limite.SemLimite, l.acima)
        assertEquals(Limite.Pct(5.0), l.abaixo)
    }

    @Test
    fun `ausencia de configuracao nunca e sem limite`() {
        val l = LiberacaoCorteService.limitesAutoLiberacao(true, null, null, "padrao", ParametrosTenant.ler("{}").toleranciaPesavel)
        assertEquals(Limite.NaoLibera, l.acima)
        assertEquals(Limite.NaoLibera, l.abaixo)
        assertEquals(Limite.NaoLibera, LiberacaoCorteService.limitesAutoLiberacao(true, null, null, "padrao", ParametrosTenant.ler("{nao json").toleranciaPesavel).acima)
    }

    @Test
    fun `validacao da politica`() {
        assertTrue(ParametrosTenant(toleranciaPesavel = negri).validar().isEmpty())
        assertEquals(1, PoliticaToleranciaPesavel(ToleranciaPadrao(acimaPct = -1.0)).validar().size)
        assertEquals(1, PoliticaToleranciaPesavel(ToleranciaPadrao(acimaPct = 2.0, acimaSemLimite = true)).validar().size)
    }
}
